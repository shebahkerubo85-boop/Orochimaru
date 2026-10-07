package ani.sanin.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.ui.LensButtonBackground
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
        val button = view.findViewById<View>(R.id.sectionMoreButton)
        FocusEffectUtil.applyFocusListener(button)
        LensButtonBackground.apply(button)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.itemView.findViewById<View>(R.id.sectionMoreButton)
            .setOnClickListener { onClick(it) }
    }

    override fun getItemCount(): Int = 1
}