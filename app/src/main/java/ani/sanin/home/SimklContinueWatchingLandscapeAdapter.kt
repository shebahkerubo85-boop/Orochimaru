package ani.sanin.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.loadImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView

class SimklContinueWatchingLandscapeAdapter(
    private val items: List<Simkl.SimklWatchedItem>,
    private val onItemClick: (Simkl.SimklWatchedItem) -> Unit
) : RecyclerView.Adapter<SimklContinueWatchingLandscapeAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_landscape, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.card.radius =
            PrefManager.getVal<Int>(PrefName.ContinueWatchingCardRoundness).toFloat()

        // Load poster as landscape backdrop
        val posterUrl = item.poster?.replace("original", "w780")?.replace("w300", "w780")
        if (!posterUrl.isNullOrBlank()) {
            holder.image.loadImage(posterUrl, 780)
        } else {
            holder.image.setImageResource(R.drawable.ic_round_person_24)
        }

        // Logo art: Simkl doesn't have clearlogo, show overlay title instead
        holder.clearlogo.visibility = View.GONE
        holder.overlayTitle.visibility = View.VISIBLE
        holder.overlayTitle.text = item.title ?: ""

        // Episode number badge
        val epStr = item.lastWatched
        if (!epStr.isNullOrBlank()) {
            holder.episodeNo.visibility = View.VISIBLE
            holder.episodeNo.text = epStr
        } else {
            holder.episodeNo.visibility = View.GONE
        }

        // Title below card
        holder.title.text = item.title ?: ""
        holder.title.isVisible = true

        // Subtitle
        val subtitle = buildString {
            val type = item.mediaType ?: "tv"
            append(type.replaceFirstChar { it.uppercase() })
            if (item.year != null) append(" \u00B7 ${item.year}")
        }
        holder.subtitle.text = subtitle

        // Time watched: Simkl doesn't track playback position
        holder.timeWatched.visibility = View.GONE

        // Progress bar: hidden for Simkl (no episode count data)
        holder.progress.visibility = View.GONE

        // Progress row below card
        holder.cwProgressRow.visibility = View.GONE

        // Ongoing badge
        val isOngoing = item.status?.lowercase() == "watching" || item.status?.lowercase() == "current"
        holder.ongoing.isVisible = isOngoing

        // Gradient overlay (obeys CardGradientIntensity slider)
        setGradient(holder.gradientOverlay)

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false
        holder.itemView.alpha = 0.85f
        FocusEffectUtil.applyFocusListener(holder.itemView, holder.card, fade = true)
    }

    override fun getItemCount() = items.size

    private fun setGradient(view: View) {
        val intensity = PrefManager.getVal<Float>(PrefName.CardGradientIntensity)
        if (intensity <= 0f) {
            view.background = null
            return
        }
        val endAlpha = 255
        val startColor = Color.argb(0, 0, 0, 0)
        val endColor = Color.argb(
            (endAlpha * intensity).toInt().coerceIn(0, 255),
            0, 0, 0
        )
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(endColor, startColor)
        )
        view.background = gradient
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cwCard)
        val image: ShapeableImageView = view.findViewById(R.id.cwImage)
        val gradientOverlay: View = view.findViewById(R.id.cwGradientOverlay)
        val clearlogo: ImageView = view.findViewById(R.id.cwClearlogo)
        val overlayTitle: TextView = view.findViewById(R.id.cwOverlayTitle)
        val title: TextView = view.findViewById(R.id.cwTitle)
        val subtitle: TextView = view.findViewById(R.id.cwSubtitle)
        val episodeNo: TextView = view.findViewById(R.id.cwEpisodeNo)
        val timeWatched: TextView = view.findViewById(R.id.cwTimeWatched)
        val cwProgressRow: LinearLayout = view.findViewById(R.id.cwProgressRow)
        val cwUserProgress: TextView = view.findViewById(R.id.cwUserProgress)
        val cwTotal: TextView = view.findViewById(R.id.cwTotal)
        val progress: ProgressBar = view.findViewById(R.id.cwProgress)
        val ongoing: View = view.findViewById(R.id.cwOngoing)
    }
}
