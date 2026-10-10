package ani.sanin.media

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.anilist.AnilistFranchiseRanks
import ani.sanin.connections.LogoApi
import ani.sanin.databinding.ActivityFranchiseBinding
import ani.sanin.databinding.ItemFranchiseEntryBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.toPx
import ani.sanin.ui.LensButtonBackground
import ani.sanin.ui.components.LibraryStatusTab
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * The franchise screen: everything in one franchise, in order.
 *
 * ## Shape of the layout
 *
 * Filter pills, then the franchise's own name, then one row per entry where a portrait poster
 * alternates sides with a teaser synopsis. The alternation is what makes the order legible at a
 * glance: a line used to run between the cards to say so, and it was removed, because a
 * primary-coloured curve over a screen that is otherwise flat page colour read as a diagram of
 * the sequence rather than as the sequence itself.
 *
 * ## Rows are not recycled
 *
 * The rows go into a LinearLayout, not a RecyclerView. With the connector gone nothing measures
 * the rows against each other, so a recycling container would be the obvious choice here, and is
 * left for later: a franchise is at most a few dozen entries, the count is in the header, and
 * holding them all costs nothing anyone can see.
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

    /** The library status pill is Compose, so its inputs live as Compose state. */
    private var pillTabs = mutableStateOf<List<LibraryStatusTab>>(emptyList())
    private var pillSelected = mutableIntStateOf(0)

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
        binding.franchiseBack.setSafeOnClickListener { finish() }
        applyWindowInsets()

        // Entries in air-date order, with the curated position winning where the user has asked
        // for it. The pill and the rows both read this same list, so they cannot disagree.
        val ordered = orderedEntries(card)
        totalEntries = ordered.size
        binding.franchiseEntryCount.text =
            getString(R.string.franchise_entries_count, totalEntries)

        // The categories actually present, in the order the entries first introduce them, which
        // is the order the pill offers. `add` rather than `+=` because the set is a val: the
        // pill is rebuilt from scratch whenever the filters change, so nothing is ever cleared
        // back to a previous value here.
        ordered.mapNotNull { it.type }.forEach { availableTypes.add(it) }

        // The filter row is the same capsule the library status tabs use, so the travelling
        // selection indicator is identical rather than a per-chip background approximation.
        binding.franchiseFilterPill.bind(
            tabs = pillTabs.value,
            selectedIndex = pillSelected.value,
            onTabSelected = { selectFilter(it) },
        )
        rebuildPillTabs()
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
     * so an anime franchise's wordmark comes from Fanart for the representative entry.
     * The representative is the last entry, matching where the banner is taken from: for a
     * series it is the most recent title, which is the one whose branding a reader expects to see.
     *
     * Clear art is asked for ahead of the logo because it is the asset that reads as a franchise
     * banner, but Fanart carries none of it for anime, so the logo is what this actually gets in
     * practice. Both are tried so a title that does gain clear art is picked up without a further
     * change.
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
            val art = withContext(Dispatchers.IO) { LogoApi.getClearartUrl(id) }
            showLogo(art)
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
     * Rebuilds the library pill's tabs: All plus one per category actually present.
     *
     * Built from the entries so an OVA tab never appears on a franchise that has none. A filter
     * that cannot change anything is worse than no filter, because it reads as broken rather
     * than as absent. Counts are the entry counts per category, matching the library pill's own.
     */
    private fun rebuildPillTabs() {
        val card = franchise ?: return
        val all = orderedEntries(card)
        val tabs = ArrayList<LibraryStatusTab>()
        tabs.add(LibraryStatusTab(getString(R.string.franchise_filter_all), all.size))
        availableTypes.forEach { type ->
            tabs.add(LibraryStatusTab(getString(type.labelRes()), all.count { it.type == type }))
        }
        pillTabs.value = tabs
        if (pillSelected.value > tabs.lastIndex) pillSelected.value = 0
        selectedType = availableTypes.toList().getOrNull(pillSelected.value - 1)
        binding.franchiseFilterPill.bind(
            tabs = pillTabs.value,
            selectedIndex = pillSelected.value,
            onTabSelected = { selectFilter(it) },
        )
    }

    /** Applies the pill's selection; index 0 is All, the rest are [availableTypes] in order. */
    private fun selectFilter(index: Int) {
        selectedType = availableTypes.toList().getOrNull(index - 1)
        pillSelected.value = index
        refilter()
    }

    /** Rebuilds the rows for the current filter. */
    private fun refilter() {
        val all = franchise?.let { orderedEntries(it) } ?: return
        buildRows(all)
    }

    /**
     * Adds one row per entry that passes the current filter, alternating card sides.
     *
     * Filtered-out entries are left out of the layout entirely rather than dimmed in place, so
     * what is on screen is the sequence and nothing else. What was hidden stays legible from the
     * count under the title, which reads "3 of 12" while a filter is on, so nothing disappears
     * without leaving a trace.
     *
     * @param ordered every entry, in franchise order.
     */
    private fun buildRows(ordered: List<FranchiseEntry>) {
        val container = binding.franchiseRows
        container.removeAllViews()

        val shown = ordered.filter { matches(it) }
        binding.franchiseEmpty.isVisible = shown.isEmpty()
        binding.franchiseProgress.isVisible = false

        // The count follows the filter, and then says what the entries it is counting are made
        // of. "5 entries · 2 movies · 3 series" is one line of arithmetic rather than three
        // unrelated facts, and every part of it is read off what is on screen, so a filter
        // narrows the whole line instead of leaving the breakdown describing entries nobody
        // can see any more.
        binding.franchiseEntryCount.text = entryCountText(shown)

        // Alternation is by position among the *visible* entries, not by franchise index. Keeping
        // the original index would let two rows in a row land on the same side once a filter
        // thinned the sequence, and the rhythm of card-left/card-right is what makes the flow
        // readable; a filter that broke the rhythm would read as a bug.
        shown.forEachIndexed { index, entry ->
            val row = ItemFranchiseEntryBinding.inflate(layoutInflater, container, false)
            bindRow(row, entry, index)
            container.addView(row.root)
        }
    }

    /** Whether this entry passes the active filter. */
    private fun matches(entry: FranchiseEntry) =
        selectedType == null || entry.type == selectedType

    /**
     * The line under the name: how many entries, and what they are.
     *
     * Both halves are counted from [shown] rather than from the franchise, so the line always
     * describes the screen in front of the reader. Under a filter the unfiltered total is still
     * given, as "3 of 5 entries", because a count that shrinks with no way back to the number
     * it came from reads as data loss rather than as a filter.
     */
    private fun entryCountText(shown: List<FranchiseEntry>): String {
        val total = shown.size
        val head = if (selectedType == null) {
            resources.getQuantityString(R.plurals.franchise_count_total, total, total)
        } else {
            resources.getQuantityString(
                R.plurals.franchise_count_total_filtered,
                total,
                total,
                totalEntries,
            )
        }

        // Only the categories that are actually present. "0 movies" is arithmetic, not news,
        // and a franchise of twelve series does not need to be told it has no films.
        val parts = shown.mapNotNull { it.type }
            .groupingBy { it }
            .eachCount()
            .map { (type, count) -> typeCountPart(type, count) }

        // The separator lives in the string, as a leading space on each part, so the join has
        // no glue of its own and a translator can change the mark without touching this.
        return parts.joinToString(separator = "", prefix = head.toString())
    }

    /** "2 movies": one category's share of the count. */
    private fun typeCountPart(type: FranchiseType, count: Int): String {
        val res = when (type) {
            FranchiseType.MOVIE -> R.plurals.franchise_count_movies
            FranchiseType.SEQUENCE -> R.plurals.franchise_count_series
            FranchiseType.OVA -> R.plurals.franchise_count_ova
            FranchiseType.SIDE_STORY -> R.plurals.franchise_count_side_story
            FranchiseType.SPIN_OFF -> R.plurals.franchise_count_spin_off
        }
        return getString(
            R.string.franchise_count_part,
            resources.getQuantityString(res, count, count),
        )
    }

    /**
     * Fills one row and flips it when the index is odd, which is the alternating rhythm.
     *
     * The flip is a swap of the two columns inside the row. Nothing about their sizes changes
     * with it: the poster is a fixed width and the text column takes the rest, so whichever
     * side a row lands on, the poster is the same poster and the prose gets the same prose.
     */
    private fun bindRow(row: ItemFranchiseEntryBinding, entry: FranchiseEntry, index: Int) {
        val cardOnLeft = index % 2 == 0

        // The row itself is the parent of the two columns, and it is the root of the inflated
        // layout, so it exists here whether or not the row has been added to the screen yet.
        //
        // `row.franchiseRow.parent` looks like the same thing and is not: bindRow runs before
        // container.addView, so that parent is still null at this point, and reading it made
        // this function return before it bound anything at all. It would have been the
        // container even once attached, which is the rows' container, not the row's — moving
        // the columns into it would have torn every row apart.
        val parent = row.franchiseRow
        if (cardOnLeft) {
            if (parent.getChildAt(0) !== row.franchiseRowCardHolder) {
                parent.removeView(row.franchiseRowCardHolder)
                parent.addView(row.franchiseRowCardHolder, 0)
            }
            if (parent.getChildAt(1) !== row.franchiseRowSynopsisHolder) {
                parent.removeView(row.franchiseRowSynopsisHolder)
                parent.addView(row.franchiseRowSynopsisHolder, 1)
            }
        } else {
            if (parent.getChildAt(0) !== row.franchiseRowSynopsisHolder) {
                parent.removeView(row.franchiseRowSynopsisHolder)
                parent.addView(row.franchiseRowSynopsisHolder, 0)
            }
            if (parent.getChildAt(1) !== row.franchiseRowCardHolder) {
                parent.removeView(row.franchiseRowCardHolder)
                parent.addView(row.franchiseRowCardHolder, 1)
            }
        }

        // The poster, not the banner: the row is a portrait card now, and a wide frame cropped
        // into one loses the sides of every shot that matters. Fall back to the wide artwork
        // only for the sources that send a poster for some titles and not others.
        row.franchiseRowBackdrop.loadImage(entry.posterUrl ?: entry.backdropUrl)

        // Rating at the top of the poster. Omitted rather than blanked when the source did not
        // score the title, because a zero would be a claim and a gap is not.
        row.franchiseRowScore.text = entry.score?.let {
            getString(R.string.franchise_score, it)
        }
        row.franchiseRowScore.isVisible = entry.score != null

        // The year under the info button. The full air date is not shown: with the year already
        // here in the poster's own column, the date would repeat it a few lines away in a
        // different typeface for no gain.
        row.franchiseRowYear.text = entry.year
        row.franchiseRowYear.isVisible = entry.year.isNotBlank()

        // The title, and only the title. Not the franchise's wordmark: that is already at
        // the top of the screen, and a logo in every row says the same thing a dozen times
        // without telling you which entry this is. Nor the runtime, which used to ride along at the
        // end of the run: the entry's own screen opens on its own length, so here it was a number
        // with nothing to be compared against, sitting at the end of a line about something else.
        row.franchiseRowTitle.text = entry.title

        // A teaser, not the text: clamped to the poster's height below, with the full synopsis
        // behind the info affordance. One entry's length must not stretch its row past the
        // poster it is sitting beside.
        row.franchiseRowSynopsis.text = entry.synopsis
            ?: getString(R.string.franchise_empty_synopsis)

        // Clamped in code rather than by maxLines in the layout because the limit is "as much as
        // the poster is tall", which maxLines cannot express: a fixed count would leave a short
        // poster with a short teaser and a tall one with a clipped teaser, breaking the rhythm
        // the whole screen depends on.
        clampSynopsisToPoster(row)

        bindListStatus(row, entry)
        bindInfo(row, entry)

        // The whole poster is the tap target, as on the row it came from.
        row.franchiseRowCard.setSafeOnClickListener {
            openEntry(entry, MediaDetailsActivity.INFO_TAB)
        }
    }

    /**
     * Caps the teaser at the poster's height.
     *
     * The text is measured unclamped to learn how many lines it would take, then re-clamped to
     * the tallest run that fits beside the poster, with a floor of one line so a teaser is
     * always readable. Measured in a post because the poster's height is only known after
     * layout.
     *
     * The poster is the ceiling because it is the fixed thing in the row. The text column is
     * centred against it, so a teaser taller than the poster would not merely crowd it, it
     * would make the column taller than the row and break the alternation, since every row is
     * as tall as its tallest column and the sides are what carry the zigzag.
     *
     * The title comes off the top of that budget first, along with the gap above the teaser,
     * so the column finishes level with the poster's foot rather than overshooting it.
     *
     * Where a full run would be a near-exact fit, the last line is dropped rather than
     * ellipsised mid-word: `maxLines` would otherwise leave a line that is mostly whitespace.
     */
    private fun clampSynopsisToPoster(row: ItemFranchiseEntryBinding) {
        val poster = row.franchiseRowCard
        val title = row.franchiseRowTitle
        val text = row.franchiseRowSynopsis
        val gap = resources.getDimensionPixelSize(R.dimen.franchise_synopsis_gap)

        text.post firstPass@{
            if (!text.isAttachedToWindow || poster.height <= 0) return@firstPass

            val lineHeight = text.lineHeight
            if (lineHeight <= 0) return@firstPass

            val previous = text.maxLines
            val budget = poster.height - title.height - gap
            val fits = (budget / lineHeight).coerceAtLeast(1)

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

    /**
     * The styled circular "i": the entry's own Info tab.
     *
     * Carries the calendar's focus treatment, circular border and all, because that is what the
     * user reaches for on a dpad and a square ring round a round button reads as a mistake. It is
     * also the one control here that is *only* a dpad target in practice: the card and the list
     * button are both perfectly good touch targets without being focused, and the "i" is a
     * 30dp circle inside a poster, which is exactly the sort of thing a directional pad needs to
     * be told about.
     */
    private fun bindInfo(row: ItemFranchiseEntryBinding, entry: FranchiseEntry) {
        row.franchiseRowInfo.setSafeOnClickListener {
            openEntry(entry, MediaDetailsActivity.INFO_TAB)
        }
        LensButtonBackground.apply(row.franchiseRowInfo)
        FocusEffectUtil.applyFocusListener(
            row.franchiseRowInfo,
            row.franchiseRowInfo,
            isCircular = true,
        )
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
     * Opens this entry's own detail screen — the same screen its own card opens on the home grid.
     *
     * Every source that has an in-app detail screen goes to it: a film or a show to
     * [TmdbDetailsActivity] by TMDB id, anime to [MediaDetailsActivity] by AniList id. Bouncing
     * out to a browser to read what the app already fetched is a downgrade. Trakt is the
     * exception — the app has no detail screen of its own for a Trakt entry, so that one opens
     * the source's own page rather than an unrelated title.
     *
     * The one thing that cannot match the home grid is the anime path, and it is a property of
     * the data rather than of this call. Home already holds the [Media] and passes it whole, so
     * its screen draws at once; a [FranchiseEntry] carries only an AniList id, so the detail
     * screen has to fetch the media itself before it draws. That is the same by-id path a dozen
     * other entry points use — notifications, the calendar, the activity feed — so it is the
     * app's normal cost for arriving without the object, not something this screen adds.
     *
     * A season entry carries its show's id, not the season's, so it lands on the show's page:
     * [TmdbDetailsActivity] has no season argument, and the show is the honest thing to open.
     *
     * @param tab which tab an *anime* entry lands on. Ignored for TMDB, whose screen is opened
     *   exactly as the home grid opens it and takes no tab argument.
     */
    private fun openEntry(entry: FranchiseEntry, tab: Int = MediaDetailsActivity.INFO_TAB) {
        entry.anilistId?.let { id ->
            startActivity(
                Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("mediaId", id)
                    putExtra(MediaDetailsActivity.TAB_TO_OPEN, tab)
                }
            )
            return
        }

        entry.tmdbId?.let { id ->
            // Deliberately the same intent the home grid builds in MediaAdaptor.clicked — same
            // two extras, no tab argument — so tapping a poster here and tapping its card at home
            // are the same action rather than two that look alike. Naming a tab here would pin it
            // to Info, which is where it lands anyway, and would quietly diverge from every other
            // caller the next time the default changed.
            startActivity(
                Intent(this, TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, entry.tmdbType ?: "movie")
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
            )
            return
        }

        val url = entry.traktId?.let { "https://trakt.tv/movies/$it" }
        if (url == null) {
            snackString(getString(R.string.franchise_load_failed))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
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
            rebuildPillTabs()
            buildRows(updated)
        }
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