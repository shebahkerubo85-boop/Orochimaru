package ani.sanin.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.util.FocusEffectUtil

/**
 * A single trailing "show more" chevron appended to the end of a horizontal
 * home/explore section. Focusable and D-pad friendly; tapping it opens the
 * same "more" screen as the section header arrow.
 */
class SectionMoreAdapter(
    private val onClick: (View) -> Unit
) : RecyclerView.Adapter<SectionMoreAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_section_more, parent, false)
        FocusEffectUtil.applyFocusListener(view)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onClick(it) }
    }

    override fun getItemCount(): Int = 1
}