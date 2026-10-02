package ani.sanin.media

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.connections.anilist.AnilistFranchiseRanks
import ani.sanin.connections.anizip.AniZip
import ani.sanin.databinding.ActivityFranchiseBinding
import ani.sanin.databinding.ItemFranchiseEntryBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.px
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.toPx
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * The franchise screen: everything in one franchise, in order, as a flowing sequence.
 *
 * ## Shape of the layout
 *
 * Filter pills, then the franchise's own name, then one row per entry where a landscape card
 * alternates sides with a teaser synopsis. Between them runs [FranchiseConnectorView], which is
 * what makes the order legible at a glance rather than something you have to read.
 *
 * ## Rows are not recycled
 *
 * The rows go into a LinearLayout, not a RecyclerView. That looks wasteful and is not: the
 * connector is drawn from the measured positions of the rows above it, and a recycling container
 * would detach and rebind those rows as the user scrolls, making the line flicker and re-route
 * under unrelated cards. A franchise is at most a few dozen entries, so holding them all is
 * bounded, and the line can be drawn in one pass.
 *
 * ## Two sources, one screen
 *
 * The same screen serves anime and movies. A Kitsu-grouped card is enriched from AniList for its
 * synopsis, score and format; a Trakt card carries most of that already. [FranchiseType] folds
 * both vocabularies into the five categories the pill shows, and a filter chip is only added when
 * at least one entry actually has that category — an OVA chip on a franchise with no OVAs would be
 * a dead control.
 */
class FranchiseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFranchiseBinding

    /** Null until the intent's parcel is read; the screen refuses to open without one. */
    private var franchise: Franchise? = null

    /**
     * The categories currently offered by the pill.
     *
     * Derived from the entries rather than fixed, so the row shows only filters that can change
     * what is on screen. "All" is always present and always first.
     */
    private val availableTypes = LinkedHashSet<FranchiseType>()

    /** The category being shown, or null for All. */
    private var selectedType: FranchiseType? = null

    /** How many entries the franchise has before any filter, for the "3 of 12" count. */
    private var totalEntries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        val card = intent.getFranchiseExtra(EXTRA_FRANCHISE)
        if (card == null) {
            // Nothing to show. Finishing here rather than rendering an empty shell means Back
            // from here lands on Explore instead of on a blank screen.
            snackString(getString(R.string.franchise_load_failed))
            finish()
            return
        }
        franchise = card

        binding = ActivityFranchiseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.franchiseName.text = card.name
        binding.franchiseBg.loadImage(card.bannerUrl)
        binding.franchiseBack.setSafeOnClickListener { finish() }
        applyWindowInsets()

        // Entries in air-date order, with the curated position winning where the user has asked
        // for it. The pill and the connector both read this same list, so they cannot disagree.
        val ordered = orderedEntries(card)
        totalEntries = ordered.size
        binding.franchiseEntryCount.text =
            getString(R.string.franchise_entries_count, totalEntries)

        // The categories actually present, in the order the entries first introduce them, which
        // is the order the pill offers. `add` rather than `+=` because the set is a val: the
        // pill is rebuilt from scratch whenever the filters change, so nothing is ever cleared
        // back to a previous value here.
        ordered.mapNotNull { it.type }.forEach { availableTypes.add(it) }

        buildFilterPills()
        buildRows(ordered)

        if (ordered.any { it.synopsis.isNullOrBlank() } && ordered.any { it.anilistId != null }) {
            enrichFromAnilist()
        }
        loadLogoArt(card)
    }

    /**
     * Puts the franchise's wordmark above the name.
     *
     * Trakt serves a logo on the card, and it is used as-is. AniList has no logo field at all,
     * so an anime franchise's wordmark comes from AniZip's clearart for the representative entry.
     * The representative is the last entry, matching where the banner is taken from: for a
     * series it is the most recent title, which is the one whose branding a reader expects to see.
     *
     * A franchise with no logo anywhere simply shows its name. Nothing is substituted, since a
     * cropped poster standing in for a wordmark reads as a rendering fault rather than a design.
     */
    private fun loadLogoArt(card: Franchise) {
        val logo = card.logoUrl
        if (logo != null) {
            showLogo(logo)
            return
        }

        val id = card.sortOrder.lastOrNull { it.anilistId != null }?.anilistId ?: return
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) { AniZip.getImages(id) }
            showLogo(images.logoUrl)
        }
    }

    /**
     * Shows [url] as the header's logo, or leaves the header with just the name.
     *
     * Gone rather than an empty box: an ImageView with nothing in it still claims its 56dp and
     * pushes the name down, so a franchise with no wordmark would sit lower than one that has it.
     */
    private fun showLogo(url: String?) {
        val logo = binding.franchiseLogo
        if (url.isNullOrBlank()) {
            logo.isVisible = false
            return
        }
        logo.isVisible = true
        logo.loadImage(url)
    }

    /**
     * Keeps the header clear of the status bar and the last row clear of the navigation bar.
     *
     * The activity is edge to edge, so without this the back button sits under the clock and the
     * final card is half-hidden behind the gesture bar. Done as padding on the two containers
     * rather than on the root, so the backdrop still runs to the physical edges.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.franchiseRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.franchiseHeader.updatePadding(top = bars.top + 8.toPx)
            binding.franchiseRows.updatePadding(bottom = bars.bottom + 24.toPx)
            insets
        }
    }

    /**
     * The entry order the screen draws.
     *
     * Air date is the honest ordering, and the pill is drawn from it. But when the user has
     * turned on Kitsu's curated ordering, that ordering is better informed than a date sort —
     * it is the source's own sequence, including for entries whose dates are unknown or wrong —
     * so it wins, exactly as it does on the row itself.
     */
    private fun orderedEntries(card: Franchise): List<FranchiseEntry> {
        // getVal's type parameter appears only in its return type, so a bare val gives the
        // compiler nothing to infer it from. Every other call site in the app is either in an
        // if or already annotated, which is why this one was the only one that failed.
        val curated: Boolean = PrefManager.getVal(PrefName.KitsuCuratedOrder)
        return if (curated) card.entries else card.sortOrder
    }

    /**
     * Repaints one chip for the dark backdrop this screen sits on.
     *
     * The theme's own chip colours are chosen for a light or dark *surface*, and this screen's
     * content floats over a dimmed banner, so a chip in its stock state can come out a pale slab
     * against a dark image. Colours are resolved from the theme here rather than hard-coded, so
     * the selected chip still follows the user's accent, and are applied as a state list so
     * selection is a property of the chip rather than something the rebuild has to reproduce.
     */
    private fun Chip.applyPillColors() {
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        val onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary)

        chipBackgroundColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(primary, Color.TRANSPARENT),
        )
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onPrimary, Color.WHITE),
            )
        )
        chipStrokeColor = ColorStateList.valueOf(Color.WHITE)
        chipStrokeWidth = 1f.px.toFloat()
    }

    /** One colour out of the current theme. */
    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId)
        else value.data
    }

    /**
     * One chip per category actually present, plus All.
     *
     * Built from the entries so an OVA chip never appears on a franchise that has none. A filter
     * that cannot change anything is worse than no filter, because it reads as broken rather
     * than as absent.
     */
    private fun buildFilterPills() {
        val row = binding.franchiseFilterRow
        row.removeAllViews()

        addPill(row, getString(R.string.franchise_filter_all), selectedType == null) {
            selectedType = null
            refilter()
        }
        availableTypes.forEach { type ->
            addPill(row, getString(type.labelRes()), selectedType == type) {
                selectedType = type
                refilter()
            }
        }
    }

    /**
     * Adds one filter chip.
     *
     * A [Chip] rather than a button: it already draws a checkable pill whose background and
     * text colour come from a state list, so selection is a property of the chip instead of a
     * drawable that has to be swapped and a text colour baked in at build time.
     *
     * @param label the chip's text, already localised.
     * @param selected whether this is the active filter.
     * @param onPick run on click, after the selection has been recorded.
     */
    private fun addPill(row: LinearLayout, label: String, selected: Boolean, onPick: () -> Unit) {
        val pill = Chip(this).apply {
            text = label
            isCheckable = true
            // No tick on the selected chip: the filled background is the whole signal, and a
            // tick on top of it would be a second one.
            isCheckedIconVisible = false
            isChecked = selected
            applyPillColors()
            setOnClickListener {
                onPick()
                buildFilterPills()
            }
        }
        row.addView(pill)
    }

    /**
     * Rebuilds the rows for the current filter.
     *
     * The connector is redrawn from scratch because filtering changes which cards are present
     * and where they sit.
     */
    private fun refilter() {
        val all = franchise?.let { orderedEntries(it) } ?: return
        binding.franchiseConnector.resetNodes()
        buildRows(all)
    }

    /**
     * Adds one row per entry that passes the current filter, alternating card sides.
     *
     * Filtered-out entries are left out of the layout entirely rather than dimmed in place, so
     * the line connects the entries that are actually on screen. What was hidden stays legible
     * from the count under the title, which reads "3 of 12" while a filter is on, so nothing
     * disappears without leaving a trace.
     *
     * @param ordered every entry, in franchise order.
     */
    private fun buildRows(ordered: List<FranchiseEntry>) {
        val container = binding.franchiseRows
        container.removeAllViews()

        val shown = ordered.filter { matches(it) }
        binding.franchiseEmpty.isVisible = shown.isEmpty()
        binding.franchiseProgress.isVisible = false

        // The count follows the filter. A row that says "12 entries" above three cards is a
        // claim the screen cannot back, and the unfiltered total is the thing a reader wants
        // back from a filter chip anyway.
        binding.franchiseEntryCount.text = if (selectedType == null) {
            getString(R.string.franchise_entries_count, totalEntries)
        } else {
            getString(R.string.franchise_entries_count_filtered, shown.size, totalEntries)
        }

        // Alternation is by position among the *visible* entries, not by franchise index. Keeping
        // the original index would let two rows in a row land on the same side once a filter
        // thinned the sequence, and the rhythm of card-left/card-right is what makes the flow
        // readable; a filter that broke the rhythm would read as a bug.
        shown.forEachIndexed { index, entry ->
            val row = ItemFranchiseEntryBinding.inflate(layoutInflater, container, false)
            bindRow(row, entry, index)
            container.addView(row.root)
        }

        // Positions are only known after the rows have been measured, so the connector waits for
        // the next layout pass. One post, not a listener: the rows are not animated or rebound
        // afterwards, so there is exactly one layout to wait for.
        container.post { drawConnector(shown) }
    }

    /** Whether this entry passes the active filter. */
    private fun matches(entry: FranchiseEntry) =
        selectedType == null || entry.type == selectedType

    /** Fills one row and flips it when the index is odd, which is the alternating rhythm. */
    private fun bindRow(row: ItemFranchiseEntryBinding, entry: FranchiseEntry, index: Int) {
        val cardOnLeft = index % 2 == 0

        // Swap the two halves by weight rather than by reordering views: the card and the
        // synopsis are siblings with fixed weights, and moving them keeps both columns the
        // same width in both directions.
        row.franchiseRowCardHolder.updateLayoutParamsWeight(if (cardOnLeft) 58 else 42)
        row.franchiseRowSynopsisHolder.updateLayoutParamsWeight(if (cardOnLeft) 42 else 58)

        row.franchiseRowBackdrop.loadImage(entry.backdropUrl ?: entry.posterUrl)

        // Every field is optional and omitted rather than blanked, because a row that reads
        // "•  •" looks broken where one that simply omits the rating does not.
        row.franchiseRowAirDate.text = entry.airDate ?: entry.year
        row.franchiseRowAirDate.isVisible = entry.airDate != null || entry.year.isNotBlank()

        row.franchiseRowDuration.text = entry.durationMinutes?.let {
            getString(R.string.franchise_minutes, it)
        }
        row.franchiseRowDuration.isVisible = entry.durationMinutes != null

        row.franchiseRowScore.text = entry.score?.let {
            getString(R.string.franchise_score, it)
        }
        row.franchiseRowScore.isVisible = entry.score != null

        row.franchiseRowTitle.text = entry.title
        row.franchiseRowType.text = entry.type?.let { getString(it.labelRes()).uppercase() }
        row.franchiseRowType.isVisible = entry.type != null

        // A teaser, not the text: clamped and ellipsised, with the full synopsis behind the
        // info affordance. One entry's length must not stretch its row and break the
        // alternation for the rows around it, so the clamp is the constraint that matters.
        row.franchiseRowSynopsis.text = entry.synopsis
            ?: getString(R.string.franchise_empty_synopsis)

        // Clamped in code rather than by maxLines in the layout because the limit is "as much as
        // the card is tall", which maxLines cannot express: a fixed count would leave a short
        // card with a short teaser and a tall card with a clipped one, breaking the rhythm the
        // whole screen depends on.
        clampSynopsisToCard(row)

        bindListStatus(row, entry)
        bindInfo(row, entry)

        // The whole card is the tap target, as on the row it came from.
        row.franchiseRowCard.setSafeOnClickListener { openEntry(entry) }
    }

    /**
     * Caps the teaser at the card's height.
     *
     * The text is measured unclamped to learn how many lines it would take, then re-clamped to
     * the tallest run that fits beside the card, with a floor of one line so a teaser is always
     * readable. Measured in a post because the card's height is only known after layout.
     *
     * Where a full run would be a near-exact fit, the last line is dropped rather than
     * ellipsised mid-word: `maxLines` would otherwise leave a line that is mostly whitespace.
     */
    private fun clampSynopsisToCard(row: ItemFranchiseEntryBinding) {
        val card = row.franchiseRowCard
        val text = row.franchiseRowSynopsis
        val scrim = row.franchiseRowSynopsisScrim

        text.post firstPass@{
            if (!text.isAttachedToWindow || card.height <= 0) return@firstPass

            val lineHeight = text.lineHeight
            if (lineHeight <= 0) return@firstPass

            val previous = text.maxLines
            // A line needs its height plus the holder's padding, since the scrim wraps the text.
            val fits = ((card.height - scrim.paddingTop - scrim.paddingBottom) / lineHeight)
                .coerceAtLeast(1)

            // lineCount only means anything after a measure pass, so the unclamped count has to
            // be taken across a layout: lift the limit, ask for one, and read the count on the
            // far side of it. Reading it in the same pass would return the old, clamped count.
            text.maxLines = Int.MAX_VALUE
            text.requestLayout()
            text.post {
                if (!text.isAttachedToWindow) return@post

                val needed = text.lineCount
                val chosen = when {
                    needed <= fits -> fits
                    // One line short of fitting leaves room for the ellipsis on the same run.
                    fits > 1 -> fits - 1
                    else -> 1
                }
                text.maxLines = chosen
                if (text.maxLines != previous) text.requestLayout()
            }
        }
    }

    /**
     * The list-status chip, matching the info tab's control.
     *
     * Shows the live status when the title is on the list and "Add to List" when it is not. The
     * status is read from the enriched detail rather than fetched per row, because the chip is on
     * every card and a request each would be the N+1 this screen must not have.
     */
    private fun bindListStatus(row: ItemFranchiseEntryBinding, entry: FranchiseEntry) {
        row.franchiseRowListStatus.text = entry.listStatus ?: getString(R.string.add_list)
        row.franchiseRowListStatus.setSafeOnClickListener {
            if (entry.anilistId == null) {
                snackString(getString(R.string.franchise_load_failed))
            } else {
                showListEditor(entry.anilistId)
            }
        }
    }

    /** The styled circular "i", which opens the full detail for this entry. */
    private fun bindInfo(row: ItemFranchiseEntryBinding, entry: FranchiseEntry) {
        row.franchiseRowInfo.setSafeOnClickListener { openEntry(entry) }
    }

    /**
     * Opens the list editor for one entry.
     *
     * Reuses [MediaListDialogFragment] rather than building a second editor: the whole point of
     * matching the info tab's control is that it opens the same thing.
     */
    private fun showListEditor(anilistId: Int) {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(LIST_DIALOG_TAG) != null) return

        // The editor observes the activity's MediaDetailsViewModel rather than taking arguments,
        // so the media has to be in that ViewModel before the dialog is shown or it renders an
        // empty sheet. A stub is enough: the ViewModel fetches the rest itself and the stub's
        // blank title is never drawn, since the sheet is laid out before the fetch lands but the
        // dialog is only shown with the loaded media.
        val model: MediaDetailsViewModel by viewModels()
        model.loadMedia(
            Media(
                id = anilistId,
                name = "",
                nameRomaji = "",
                userPreferredName = "",
                isAdult = false,
            )
        )
        MediaListDialogFragment().show(fm, LIST_DIALOG_TAG)
    }

    /**
     * Opens this entry's own detail screen.
     *
     * Anime goes to the app's media screen by AniList id. A movie has no AniList equivalent worth
     * sending the user to, so it goes to TMDB or Trakt in the browser when the source gave us an
     * id, and does nothing rather than opening an unrelated page when it did not.
     */
    private fun openEntry(entry: FranchiseEntry) {
        val id = entry.anilistId
        if (id != null) {
            startActivity(
                Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("mediaId", id)
                }
            )
            return
        }

        val url = entry.tmdbId?.let {
            "https://www.themoviedb.org/movie/$it"
        } ?: entry.traktId?.let { "https://trakt.tv/movies/$it" }

        if (url == null) {
            snackString(getString(R.string.franchise_load_failed))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
    }

    /**
     * Draws the connector from the rows' measured positions.
     *
     * @param shown the entries the rows were built from, in the same order.
     */
    private fun drawConnector(shown: List<FranchiseEntry>) {
        val connector = binding.franchiseConnector
        val container = binding.franchiseRows
        connector.resetNodes()

        // The connector is a sibling of the rows inside the scrolling content, so it has to be
        // as tall as the rows. Left as wrap_content it would measure to zero height against a
        // FrameLayout that has not been sized yet, and the line would never be drawn.
        if (container.height <= 0) return
        connector.updateLayoutParams { height = container.height }
        connector.requestLayout()

        // One more hop: the height above has only been requested, not applied, so the nodes are
        // added on the pass after the connector has been laid out to the full flow. Drawing them
        // now would place them correctly but against a stale height, and the line would be
        // clipped to whatever the last layout left behind.
        connector.post { addConnectorNodes(shown) }
    }

    /**
     * Gives the connector one node per row, in the rows' own coordinate space.
     *
     * @param shown the entries the rows were built from, in the same order, so row *i* here is
     *   row *i* in the container.
     */
    private fun addConnectorNodes(shown: List<FranchiseEntry>) {
        val connector = binding.franchiseConnector
        val container = binding.franchiseRows
        for (i in 0 until minOf(shown.size, container.childCount)) {
            val child = container.getChildAt(i)
            // The card, not the row: the line is meant to run card to card, and the row's centre
            // sits in the gap between the card and its synopsis, so using it would draw the line
            // from the middle of the whitespace.
            val card = child.findViewById<View>(R.id.franchiseRowCard) ?: continue
            connector.addNode(
                centerX = (child.left + card.left + card.width / 2f),
                // card.top is relative to the row, so the row's own top is added to bring it
                // into the connector's space, which is the FrameLayout the rows sit in.
                top = (child.top + card.top).toFloat(),
                bottom = (child.top + card.bottom).toFloat(),
            )
        }
        connector.invalidate()
    }

    /**
     * Fills in synopsis, score, format and list status from AniList, then rebinds.
     *
     * Only fires when there is something to gain, which the caller checks: a Trakt-backed card
     * carries all of this already, and a Kitsu card with no AniList ids cannot be enriched at
     * all. One batched query for the whole screen, not one per entry.
     *
     * The order is recomputed rather than taken from the caller, since enrichment can fill in an
     * air date that changes where an entry sorts.
     */
    private fun enrichFromAnilist() {
        val card = franchise ?: return
        binding.franchiseProgress.isVisible = true
        lifecycleScope.launch {
            val enriched = withContext(Dispatchers.IO) {
                AnilistFranchiseRanks.enrich(listOf(card))
            }
            // enrich() hands back what it was given even when the request failed, but first()
            // would throw on an empty list rather than degrading, so the fallback is explicit.
            val resolved = enriched.firstOrNull() ?: card
            franchise = resolved

            val updated = orderedEntries(resolved)
            binding.franchiseProgress.isVisible = false

            // Rebuilt rather than patched in place: enrichment can change an entry's type, which
            // can change which filters are available, which means the whole row set is stale.
            availableTypes.clear()
            updated.mapNotNull { it.type }.forEach { availableTypes.add(it) }
            buildFilterPills()
            buildRows(updated)
        }
    }

    private fun ViewGroup.updateLayoutParamsWeight(weight: Int) {
        val lp = layoutParams as? LinearLayout.LayoutParams ?: return
        lp.width = 0
        lp.weight = weight.toFloat()
        layoutParams = lp
    }

    @Suppress("DEPRECATION")
    private fun Intent.getFranchiseExtra(name: String): Franchise? =
        getSerializableExtra(name) as? Franchise

    companion object {
        /** Intent extra carrying the pressed [Franchise]. Serializable, matching [Media]. */
        const val EXTRA_FRANCHISE = "franchise"

        private const val LIST_DIALOG_TAG = "franchise_list_dialog"

        /**
         * Builds the intent that opens this screen.
         *
         * @param franchise the card that was pressed.
         */
        fun intent(context: Context, franchise: Franchise) =
            Intent(context, FranchiseActivity::class.java)
                .putExtra(EXTRA_FRANCHISE, franchise as Serializable)
    }
}