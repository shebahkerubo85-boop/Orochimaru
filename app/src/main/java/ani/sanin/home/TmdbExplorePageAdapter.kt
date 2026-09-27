package ani.sanin.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemTmdbExplorePageBinding

/**
 * The Explore page as a single-item adapter.
 *
 * The anime page does the same thing (AnimePageAdapter.getItemCount() == 1): one
 * ViewHolder holds the banner, the chips and every row, and the fragment's ConcatAdapter
 * hangs the Popular list and the pager's progress bar off the end of it. Keeping the
 * page to one item is what lets a horizontal rail and a full-width list live in the same
 * scroller without a nested-scroll fight.
 *
 * [onPageBound] hands the inflated page to the fragment, which owns all the loading and
 * needs the banner and row bindings the moment the item exists.
 */
class TmdbExplorePageAdapter : RecyclerView.Adapter<TmdbExplorePageAdapter.PageViewHolder>() {

    lateinit var binding: ItemTmdbExplorePageBinding
        private set

    /** Called once per page bind, after [binding] is assigned. */
    var onPageBound: ((ItemTmdbExplorePageBinding) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val inflated = ItemTmdbExplorePageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PageViewHolder(inflated)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        binding = holder.binding
        onPageBound?.invoke(holder.binding)
    }

    override fun getItemCount(): Int = 1

    class PageViewHolder(val binding: ItemTmdbExplorePageBinding) :
        RecyclerView.ViewHolder(binding.root)
}
