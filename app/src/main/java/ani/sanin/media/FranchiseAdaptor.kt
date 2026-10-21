package ani.sanin.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemFranchiseOverflowBinding
import ani.sanin.databinding.ItemFranchisePosterBinding
import ani.sanin.databinding.ItemMediaFranchiseBinding
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener
import ani.sanin.util.FocusEffectUtil

/**
 * Adapter for the franchise cards that replace the Popular list on both Explore pages.
 *
 * A card is one [Franchise]. The banner is a fixed 152dp band regardless of how many entries
 * the franchise has, and the poster row sits on it holding as many entries as the measured
 * width suits. Entries beyond that are counted in a "+N" cell rather than clipped off the
 * edge: the row does not scroll, so a poster running out of view would promise somewhere to
 * go that does not exist.
 */
class FranchiseAdaptor(
    private val onBannerClick: (Franchise, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<FranchiseAdaptor.FranchiseViewHolder>() {

    /** The cards on screen, which is the queried subset of [allFranchises]. */
    private val franchises = ArrayList<Franchise>()

    /**
     * Every card the row knows about, whether or not the query shows it.
     *
     * The search filters what is *drawn*, not what the row knows: a card that the query hides
     * is still in the row, and clearing the query brings it back. Filtering the backing list
     * instead would throw the hidden cards away, and the row would have to fetch them all over
     * again to show them once more.
     */
    private val allFranchises = ArrayList<Franchise>()

    /** The text the row is filtered by. Blank means no filter. */
    private var query: String = ""

    private val lock = Any()

    /**
     * Seed ids being resolved right now, and seed ids already resolved one way or the other.
     *
     * Cards are built off the main thread from a seed list that keeps growing underneath,
     * so this adapter is the only thing that knows which seeds are in flight, already
     * failed, or already on screen. Without it, a page overlap re-walks the whole Kitsu
     * chain per seed, which is the cost this change exists to avoid.
     */
    private val seedsInFlight = LinkedHashSet<Int>()
    private val seedsSettled = HashSet<Int>()

    /** True once a seed has been resolved and its outcome appended or discarded. */
    fun hasCardFor(seed: Int) = synchronized(lock) { seed in seedsSettled }

    /** True while a seed is still being resolved, so a page overlap does not duplicate it. */
    fun isPending(seed: Int) = synchronized(lock) { seed in seedsInFlight }

    /**
     * Records seeds as in flight.
     *
     * Returns the seeds that were actually claimed, dropping any that already settled or are
     * already claimed, so the caller resolves exactly the seeds this adapter has not seen.
     */
    fun markPending(seeds: List<Int>): List<Int> = synchronized(lock) {
        seeds.filter { it !in seedsSettled && seedsInFlight.add(it) }
    }

    /** Moves seeds out of the in-flight set once their cards have been appended. */
    fun markDone(seeds: List<Int>) = synchronized(lock) {
        seeds.forEach {
            seedsInFlight -= it
            seedsSettled += it
        }
    }

    /** Drops every card and seed, for a mode or chip change that invalidates the row. */
    fun clear() = synchronized(lock) {
        seedsInFlight.clear()
        seedsSettled.clear()
        franchises.clear()
        allFranchises.clear()
        query = ""
        notifyDataSetChanged()
    }

    fun submit(newFranchises: List<Franchise>) {
        synchronized(lock) {
            allFranchises.clear()
            allFranchises.addAll(newFranchises)
        }
        publish()
    }

    /**
     * Filters the row by name.
     *
     * A blank query shows everything again. The name is the only thing a reader can search a
     * franchise by — the card has no other text — and matching on it is case-insensitive
     * because a reader types "spiderman" for "Spider-Man".
     */
    fun setQuery(value: String) {
        val trimmed = value.trim()
        if (query == trimmed) return
        query = trimmed
        publish()
    }

    /**
     * Republishes the row: the queried subset of every card the row knows about.
     *
     * Diffed rather than notifyDataSetChanged, for the same reason [submit] is: a blanket
     * invalidation rebinds every visible card, and each rebind re-runs loadImage on the banner
     * and every poster, so the row visibly re-flashes.
     */
    private fun publish() {
        val previous = synchronized(lock) { franchises.toList() }
        val next = synchronized(lock) { allFranchises.filter { matchesQuery(it) } }
        if (previous == next) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = previous[oldPos].name
                .equals(next[newPos].name, ignoreCase = true)

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                previous[oldPos] == next[newPos]
        })
        synchronized(lock) {
            franchises.clear()
            franchises.addAll(next)
        }
        // Dispatched after the backing list is swapped, never inside the lock: the dispatch
        // synchronously calls back into getItemCount and getItemViewType.
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Whether a card's name matches the row's query.
     *
     * By word, not by exact string. A reader who has typed "super" is asking for "Superman"
     * and "Supernatural" — the names they can see — and a search that only found whole words
     * would tell them nothing matches while the name they are looking at is right there.
     *
     * So a word of the name starting with the query is a match, and so is the query appearing
     * anywhere in the name, which keeps a fragment typed into the middle of a word working.
     * Separators are folded to spaces first, so "superman the" finds "Superman: The Animated
     * Series" as well as "Superman: The Brave and the Bold".
     */
    private fun matchesQuery(card: Franchise): Boolean {
        if (query.isBlank()) return true
        val name = normalise(card.name)
        val wanted = normalise(query)
        if (wanted.isEmpty()) return true
        if (name.contains(wanted)) return true
        return name.split(' ').any { it.startsWith(wanted) }
    }

    /**
     * Folds a name or a query into the shape the search compares them in.
     *
     * Lower case, and every run of punctuation or space turned into a single space, so that
     * two names that differ only in how they are punctuated are the same name to a reader.
     */
    private fun normalise(value: String) = value.lowercase()
        .replace(NON_WORD, " ")
        .trim()
        .replace(WHITESPACE, " ")

    /**
     * Whether the query emptied a row that had cards to show.
     *
     * Only a *miss*, and only when the row actually holds something: a row with no cards at
     * all is empty for its own reasons, and telling the reader that their search matched
     * nothing would blame the search for a row that had nothing in it to begin with.
     *
     * Asked rather than latched, because the row is the only thing that knows what it is
     * showing, and a latch would have to be kept in step with every change to the row.
     */
    fun isSearchMissed(): Boolean = synchronized(lock) {
        query.isNotBlank() && allFranchises.isNotEmpty() && franchises.isEmpty()
    }

    /**
     * The cards the row knows about, in order.
     *
     * A snapshot so the sort controls can re-order the row without holding their own copy: the
     * loader appends as pages land, and a second list would go stale behind it. The full set
     * rather than the queried subset, so a sort re-orders every card and the query still has
     * something to filter afterwards.
     */
    fun currentCards(): List<Franchise> = synchronized(lock) { allFranchises.toList() }

    /**
     * Swaps provisional cards for their finished ones and appends anything genuinely new.
     *
     * The loader publishes a provisional card for a seed the moment the page lands and then
     * replaces it as the real franchise is resolved, so a card can legitimately be seen twice
     * under one name. [replacements] therefore keys on the name the placeholder went out under,
     * which is the seed's title and need not survive resolution: a Naruto seed is published as
     * "Naruto: Shippuden" and resolved as "Naruto", so keying on the finished name would leave
     * the placeholder on screen beside the card that was meant to replace it.
     *
     * A card whose finished name is already in the row is dropped rather than appended, because
     * two seeds of one franchise resolve to the same name and the row wants one card for it.
     */
    fun upsert(replacements: Map<String, Franchise>) {
        if (replacements.isEmpty()) return
        synchronized(lock) {
            val at = HashMap<String, Int>()
            allFranchises.forEachIndexed { index, card -> at.putIfAbsent(card.name.lowercase(), index) }
            val merged = ArrayList(allFranchises)
            replacements.forEach { (provisionalName, card) ->
                val position = at.remove(provisionalName.lowercase())
                val key = card.name.lowercase()
                if (position == null) {
                    if (at.putIfAbsent(key, merged.size) == null) {
                        merged.add(card)
                    }
                } else {
                    at.putIfAbsent(key, position)
                    // Only a card that actually changed is written back. A stub re-published
                    // under its own name is common while a batch resolves, and rewriting it
                    // would rebind — and re-flash — a card that is already showing correctly.
                    if (merged[position] != card) {
                        merged[position] = card
                    }
                }
            }
            allFranchises.clear()
            allFranchises.addAll(merged)
        }
        // The diff reports the inserts and the rebinds on its own, and only for the cards that
        // really moved: the row holds several cards that are already correct while the rest are
        // still being upgraded.
        publish()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = FranchiseViewHolder(
        ItemMediaFranchiseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun getItemCount() = franchises.size

    override fun onBindViewHolder(holder: FranchiseViewHolder, position: Int) =
        holder.bind(franchises[position])

    inner class FranchiseViewHolder(
        private val binding: ItemMediaFranchiseBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // The card is one D-pad target, like every other card in the app: the root is
            // focusable in XML and takes the visible outer ring, while the banner inside it
            // is just art. Focusing a deep child here is what made the ring invisible.
            binding.root.isFocusableInTouchMode = false
            binding.franchiseBanner.isFocusable = false
            binding.franchiseBanner.isFocusableInTouchMode = false
            binding.posterRow.isFocusable = false
            binding.posterRow.isFocusableInTouchMode = false
            FocusEffectUtil.applyFocusListener(binding.root)
            binding.root.setSafeOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBannerClick(franchises[position], binding.franchiseBanner)
                }
            }
        }

        fun bind(franchise: Franchise) {
            binding.franchiseTitle.text = franchise.name
            binding.franchiseBanner.loadImage(franchise.bannerUrl)

            // The row is plain children rather than a nested RecyclerView: there is nothing
            // to scroll, so a RecyclerView would only add a focus target we do not want.
            val row = binding.posterRow
            row.removeAllViews()
            row.post { fillRow(row, franchise) }
        }

        /**
         * Adds one cell per entry that fits the row's current width.
         *
         * Deferred to a layout pass because at bind time the row has no measured width, and
         * a guessed one would be wrong on the first frame of a rotation.
         */
        private fun fillRow(row: ViewGroup, franchise: Franchise) {
            val available = row.width
            if (available <= 0) return

            // The holder may have been recycled for a different franchise between the bind
            // and this posted runnable. Filling now would put one card's posters under
            // another's title, so bail and let the next bind do the work.
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val current = synchronized(lock) { franchises.getOrNull(position) }
            if (current !== franchise) return

            val gap = GAP_DP.dpToPx(row)
            val cells = cellCount(available, row)
            if (cells <= 0) return

            val cellWidth = ((available - gap * (cells - 1)) / cells).toInt()
            if (cellWidth <= 0) return

            val inflater = LayoutInflater.from(row.context)
            for (entry in franchise.entries.take(cells)) {
                val cell: ItemFranchisePosterBinding = ItemFranchisePosterBinding.inflate(
                    inflater,
                    row,
                    false
                )
                cell.posterYear.text = entry.year
                cell.posterImage.loadImage(entry.posterUrl)
                // Width only. The poster's height comes from the weighted fill in
                // item_franchise_poster.xml, which gives it whatever the card's band has left
                // once the declared gaps and the year have taken theirs — that is what holds
                // the 1dp between the top of the banner and the top of the poster. Setting a
                // height here would be a second claim on the same space, and the two would
                // disagree on any screen whose width does not happen to line up.
                cell.root.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = cellWidth
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    marginEnd = gap
                }
                row.addView(cell.root)
            }

            // Whatever did not fit is named rather than clipped. A poster running off the
            // row's edge reads as somewhere to keep scrolling, and this row does not scroll,
            // so a count is the truth where a sliver would be a broken promise.
            val hidden = franchise.entries.size - cells
            if (hidden > 0) addOverflow(inflater, row, hidden, cellWidth, gap, franchise)
        }

        /**
         * The "+N" cell, standing in the slot a poster would have taken.
         *
         * Opens the franchise screen like the banner does, and hands the callback the banner
         * rather than itself: the shared element is the artwork the screen grows out of, so
         * morphing out of a number would be a morph from nowhere.
         */
        private fun addOverflow(
            inflater: LayoutInflater,
            row: ViewGroup,
            hidden: Int,
            cellWidth: Int,
            gap: Int,
            franchise: Franchise,
        ) {
            val more = ItemFranchiseOverflowBinding.inflate(inflater, row, false)
            val context = row.context
            more.franchiseOverflow.text = context.getString(R.string.franchise_more_entries, hidden)
            more.franchiseOverflow.contentDescription =
                context.getString(R.string.franchise_more_entries_desc, hidden)
            more.root.updateLayoutParams<LinearLayout.LayoutParams> {
                width = cellWidth
                height = ViewGroup.LayoutParams.MATCH_PARENT
                marginEnd = gap
            }
            // The overflow cell is informational inside the banner, not a separate focus target.
            // Opening the franchise remains available through the whole-banner focus target.
            more.root.isFocusable = false
            more.root.isFocusableInTouchMode = false
            more.root.isClickable = false
            row.addView(more.root)
        }

        /**
         * How many cells the row can hold without distorting a poster.
         *
         * Derived from the measured width rather than fixed, because the poster's height is
         * already spoken for by the card's band and width is the only thing left to give. A
         * fixed count that suits a portrait phone produces squat, over-wide posters in
         * landscape — and the poster cannot simply grow to match, because it is using the
         * band's whole height already. Dividing by a target cell width instead keeps every
         * cell near the shape the band's height implies, at any screen size.
         */
        private fun cellCount(available: Int, row: View): Int {
            val target = TARGET_CELL_DP.dpToPx(row)
            if (target <= 0) return 0
            return (available / target).coerceIn(MIN_CELLS, MAX_CELLS)
        }
    }

    private fun Int.dpToPx(view: View) =
        (this * view.resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Spacing between poster cells, in dp. Applied as a trailing margin so the last
         * cell's trailing space falls outside the visible run.
         */
        const val GAP_DP = 1

        /**
         * Most cells that will ever be built, whatever the width.
         *
         * A ceiling on density rather than a count: past this the posters are too small for
         * their year to read, and a franchise with a dozen entries is better served by a "+9"
         * than by twelve thumbnails.
         */
        const val MAX_CELLS = 6

        /**
         * Fewest cells that will ever be built.
         *
         * Below two there is no row left to speak of, and one poster alone is a poster, not
         * a franchise — which is the same reason a single-entry card is hidden by default.
         */
        const val MIN_CELLS = 2

        /**
         * The cell width that suits the card's band, in dp, and so the divisor behind
         * [cellCount].
         *
         * A band's height is fixed and its poster fills what is left, so the poster's shape
         * is already decided by the band; this is simply the width that shape implies. It is
         * the divisor rather than a hard cell count because a hard count cannot be right at
         * more than one screen width, and it is derived rather than measured because the
         * band's own height lives in the layout, not here.
         */
        const val TARGET_CELL_DP = 73
    }
}

/**
 * Calls [onNearEnd] when this list is scrolled to within [threshold] items of its last one.
 *
 * The Franchise cards are the tail of a ConcatAdapter rather than a list of their own, so this
 * is the only place the paging gesture exists: the user scrolling the last card into view is
 * the request for the next batch. Watching the main list's scroll is what the rest of the
 * screen already scrolls, so nothing new has to be attached to the cards themselves.
 *
 * Gated on a downward scroll so a fling that happens to pass the end on the way back up
 * cannot ask for another batch, and left to the caller's own guards otherwise: whether more
 * exists and whether a request is already in flight are row state this cannot see.
 */
fun RecyclerView.onNearEnd(threshold: Int = 2, onNearEnd: () -> Unit) {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            // Read inside the callback rather than captured: the layout manager is set by the
            // caller, which for one of the two pages happens after this is wired up.
        /**
         * Anything that is not a letter or a digit ends a word.
         *
         * Used to fold a name into its words, so a search can be matched against the start of
         * one of them rather than against the whole string.
         */
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** A run of spaces, left behind by the folding above. */
        private val WHITESPACE = Regex(" +")

            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val last = manager.findLastVisibleItemPosition()
            val end = manager.itemCount - 1
            if (last != RecyclerView.NO_POSITION && end > 0 && last >= end - threshold) {
                onNearEnd()
            }
        }
    })
}
