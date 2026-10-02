package ani.sanin.media

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemFranchisePosterBinding
import ani.sanin.databinding.ItemMediaFranchiseBinding
import ani.sanin.loadImage
import ani.sanin.setSafeOnClickListener

/**
 * Adapter for the franchise cards that replace the Popular list on both Explore pages.
 *
 * A card is one [Franchise]. The banner is a fixed 152dp regardless of how many entries
 * the franchise has, and the poster row below it holds only as many entries as fit the
 * measured width, so a nine-entry franchise shows a clipped row and nothing more.
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
        franchises.clear()
        franchises.addAll(newFranchises)
        notifyDataSetChanged()
    }

    /**
     * The cards currently on screen, in order.
     *
     * A snapshot so the sort controls can re-order the row without holding their own copy: the
     * loader appends as pages land, and a second list would go stale behind it.
     */
    fun currentCards(): List<Franchise> = synchronized(lock) { franchises.toList() }

    /** Appends cards and notifies only the new range, leaving existing holders alone. */
    fun append(more: List<Franchise>) {
        if (more.isEmpty()) return
        // Seed bookkeeping is mutated from the background loader, so the list mutation and
        // the RecyclerView notification stay under the same lock.
        synchronized(lock) {
            val start = franchises.size
            franchises.addAll(more)
            notifyItemRangeInserted(start, more.size)
        }
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
            // Only the banner is a focus target. The card root is focusable in XML so the whole
            // 152dp banner is hit by dpad, so that is turned off here and the banner takes it
            // instead; the poster row stays unreachable until the franchise screen exists.
            binding.root.isFocusable = false
            binding.root.isFocusableInTouchMode = false
            binding.franchiseBanner.isFocusable = true
            binding.franchiseBanner.isFocusableInTouchMode = false
            binding.franchiseBanner.setSafeOnClickListener {
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
            val cellWidth = ((available - gap) / MAX_CELLS).toInt()
            if (cellWidth <= 0) return

            val fits = (available / (cellWidth + gap)).toInt()
            if (fits <= 0) return

            val inflater = LayoutInflater.from(row.context)
            for (entry in franchise.entries.take(fits)) {
                val cell: ItemFranchisePosterBinding = ItemFranchisePosterBinding.inflate(
                    inflater,
                    row,
                    false
                )
                cell.posterYear.text = entry.year
                cell.posterImage.loadImage(entry.posterUrl)
                // Height follows width so the poster keeps a 2:3 shape at any count. Setting
                // width alone would stretch every cell that is not exactly 76dp wide.
                cell.posterImage.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = cellWidth
                    height = cellWidth * 3 / 2
                }
                cell.root.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = cellWidth
                    marginEnd = gap
                }
                row.addView(cell.root)
            }
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
         * Most cells that will ever be built. Acts as the divisor that hands the row its
         * remaining width evenly, so on a wide screen cells widen instead of leaving a gap
         * at the end. The count actually shown is still limited by what fits, not by this.
         */
        const val MAX_CELLS = 6
    }
}
