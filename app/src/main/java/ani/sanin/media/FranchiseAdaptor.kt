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

    private val franchises = ArrayList<Franchise>()

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
        notifyDataSetChanged()
    }

    fun submit(newFranchises: List<Franchise>) {
        // Diffed rather than notifyDataSetChanged. A blanket invalidation rebinds every visible
        // card, and each rebind re-runs loadImage on the banner and every poster, so the row
        // visibly re-flashes on each batch and on each sort. The diff only touches the cards that
        // actually moved or changed, which is what lets a batch append progressively without the
        // row strobing as it fills.
        //
        // Name is the identity because the row has no id to use: `upsert` already keys on it, and
        // it is unique per row by construction there.
        val previous = synchronized(lock) { franchises.toList() }
        if (previous == newFranchises) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = newFranchises.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = previous[oldPos].name
                .equals(newFranchises[newPos].name, ignoreCase = true)

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                previous[oldPos] == newFranchises[newPos]
        })
        synchronized(lock) {
            franchises.clear()
            franchises.addAll(newFranchises)
        }
        // Dispatched after the backing list is swapped, never inside the lock: the dispatch
        // synchronously calls back into getItemCount and getItemViewType.
        diff.dispatchUpdatesTo(this)
    }

    /**
     * The cards currently on screen, in order.
     *
     * A snapshot so the sort controls can re-order the row without holding their own copy: the
     * loader appends as pages land, and a second list would go stale behind it.
     */
    fun currentCards(): List<Franchise> = synchronized(lock) { franchises.toList() }

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
        val changed = mutableListOf<Int>()
        val inserted = mutableListOf<Int>()
        synchronized(lock) {
            val at = HashMap<String, Int>()
            franchises.forEachIndexed { index, card -> at.putIfAbsent(card.name.lowercase(), index) }
            val merged = ArrayList(franchises)
            replacements.forEach { (provisionalName, card) ->
                val position = at.remove(provisionalName.lowercase())
                val key = card.name.lowercase()
                if (position == null) {
                    if (at.putIfAbsent(key, merged.size) == null) {
                        merged.add(card)
                        inserted += merged.size - 1
                    }
                } else {
                    at.putIfAbsent(key, position)
                    // Only a card that actually changed is reported. A stub re-published under
                    // its own name is common while a batch resolves, and reporting it as changed
                    // would rebind — and re-flash — a card that is already showing correctly.
                    if (merged[position] != card) {
                        merged[position] = card
                        changed += position
                    }
                }
            }
            franchises.clear()
            franchises.addAll(merged)
        }
        // Reported per item rather than as one blanket invalidation, which is what turned a
        // resolving batch into a strobe: the row holds several cards that are already correct
        // while the rest are still being upgraded.
        //
        // Inserts go out first and in ascending order, because RecyclerView replays the
        // positions it is told about and out-of-order inserts land in the wrong place.
        inserted.sorted().forEach { notifyItemInserted(it) }
        changed.forEach { notifyItemChanged(it) }
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
            val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val last = manager.findLastVisibleItemPosition()
            val end = manager.itemCount - 1
            if (last != RecyclerView.NO_POSITION && end > 0 && last >= end - threshold) {
                onNearEnd()
            }
        }
    })
}
