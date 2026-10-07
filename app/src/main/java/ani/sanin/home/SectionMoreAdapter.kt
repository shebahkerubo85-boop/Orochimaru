package ani.sanin.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.isTvDevice
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
        LensButtonBackground.apply(button)
        // Only the TV needs the dpad ring; on touch the button must open on a single tap,
        // so it stays out of focus-in-touch and gets no focus border.
        if (isTvDevice(parent.context)) {
            FocusEffectUtil.applyFocusListener(button)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val button = holder.itemView.findViewById<View>(R.id.sectionMoreButton)
        button.setOnClickListener { onClick(it) }
        // Match the chevron's row height so `center_vertical` really centres it against
        // the cards, whatever height the active card style gives them.
        holder.itemView.post {
            val rv = holder.itemView.parent as? RecyclerView ?: return@post
            val target = rv.height
            if (target <= 0) return@post
            val lp = holder.itemView.layoutParams
            if (lp != null && lp.height != target) {
                lp.height = target
                holder.itemView.layoutParams = lp
            }
        }
    }

    override fun getItemCount(): Int = 1
}