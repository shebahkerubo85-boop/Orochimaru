package ani.sanin.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.util.FocusEffectUtil

/**
 * Renders discovery results in the app's standard grid card (`item_tmdb_card`) for titles that
 * are not TMDB records, e.g. the anime Discovery categories. It uses the same poster + title
 * layout and card styling as the TMDB grids, minus the TMDB-only extras (rating pill, logo
 * art, watch-progress badge) that have no data behind them here.
 */
class DiscoveryStandardCardAdapter(
    private val onClick: (Card) -> Unit,
) : RecyclerView.Adapter<DiscoveryStandardCardAdapter.VH>() {

    data class Card(
        val id: Int,
        val title: String,
        val posterUrl: String?,
    )

    private val items = ArrayList<Card>()

    fun submit(list: List<Card>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Card>) {
        val existing = items.mapTo(HashSet()) { it.id }
        val unique = list.filter { existing.add(it.id) }
        if (unique.isEmpty()) return
        val start = items.size
        items.addAll(unique)
        notifyItemRangeInserted(start, unique.size)
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val rv = holder.itemView.parent as? RecyclerView
        val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
        val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
        TmdbCards.applyPlainCardStyle(holder.binding, item.title, item.posterUrl, cell)
        if (rv != null) {
            val gap = TmdbCards.gridRowGapPx(rv)
            holder.itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = gap }
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
        FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster, holder.itemView)
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
}
