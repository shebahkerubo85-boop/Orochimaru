package ani.sanin.home

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

/**
 * The anime Discovery result card: a stroked outline holding a poster and its title on a black
 * tile. Shared by both discovery modes so the two result grids match.
 *
 * NOTE: temporary — the TMDB grid is expected to return to its own card design in the next
 * push; see the anime/TMDB Discovery unification notes.
 */
class DiscoveryOutlineAdapter(
    private val onClick: (OutlineCard) -> Unit,
) : RecyclerView.Adapter<DiscoveryOutlineAdapter.VH>() {

    data class OutlineCard(
        val id: Int,
        val mediaType: String,
        val title: String,
        val posterUrl: String?,
    )

    private val items = ArrayList<OutlineCard>()

    fun submit(list: List<OutlineCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<OutlineCard>) {
        val existing = items.mapTo(HashSet()) { it.id to it.mediaType }
        val unique = list.filter { existing.add(it.id to it.mediaType) }
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
        val context = parent.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.BLACK)
                    setStroke(dp(context, 2), context.getThemeColor(com.google.android.material.R.attr.colorSecondary))
                    cornerRadius = dp(context, 14).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.BLACK)
                    setStroke(dp(context, 1), Color.rgb(42, 42, 42))
                    cornerRadius = dp(context, 14).toFloat()
                })
            }
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 5))
            }
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_editorial_artwork_unavailable)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, if (isTvDevice(context)) 210 else 170)
            )
            contentDescription = "Poster"
        }
        val title = TextView(context).apply {
            textSize = 13f
            maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(context, 3), dp(context, 7), dp(context, 3), 0)
        }
        card.addView(poster)
        card.addView(title)
        FocusEffectUtil.applyFocusListener(card)
        return VH(card, poster, title)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.poster.setImageResource(R.drawable.ic_editorial_artwork_unavailable)
        item.posterUrl?.takeIf { it.isNotBlank() }?.let { holder.poster.loadImage(it) }
        holder.itemView.contentDescription = item.title
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(root: View, val poster: ImageView, val title: TextView) : RecyclerView.ViewHolder(root)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
