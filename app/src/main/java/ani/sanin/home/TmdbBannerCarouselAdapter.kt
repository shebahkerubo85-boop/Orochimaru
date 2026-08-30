package ani.sanin.home

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ani.sanin.R

class TmdbBannerCarouselAdapter(
    private val items: List<TmdbHomeFragment.BannerItem>,
    private val onItemClick: (TmdbHomeFragment.BannerItem) -> Unit,
    private val genreNames: Map<Int, String>,
    private val logoUrls: Map<Int, String?> = emptyMap(),
    private val statusByIndex: Map<Int, String?> = emptyMap(),
    private val scoreByIndex: Map<Int, String?> = emptyMap(),
) : RecyclerView.Adapter<TmdbBannerCarouselAdapter.ViewHolder>() {

    var landscapeMode = false
    var cardWidthPx = 0

    fun setLandscapeMode(enabled: Boolean, widthPx: Int) {
        if (landscapeMode == enabled && cardWidthPx == widthPx) return
        landscapeMode = enabled
        cardWidthPx = widthPx
        notifyDataSetChanged()
    }

    val actualCount: Int get() = items.size
    fun realPosition(virtualPos: Int): Int = virtualPos % items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_banner_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pos = realPosition(position)
        val item = items[pos]
        val ctx = holder.itemView.context

        // --- Banner image ---
        val imageUrl = item.bannerUrl
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerImage.isVisible = true
            holder.bannerBg.isVisible = true
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black).error(R.drawable.ic_round_person_24)
                .into(holder.bannerBg)
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black).error(R.drawable.ic_round_person_24)
                .into(holder.bannerImage)
        } else {
            holder.bannerImage.isVisible = false
            holder.bannerBg.isVisible = false
        }

        // --- Clearlogo / Title ---
        val logoUrl = logoUrls[pos]
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.title.isVisible = false
            Glide.with(ctx).load(logoUrl).override(240, 64).into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.title.isVisible = true
            holder.title.text = item.title
        }

        // --- Tags: transparent pills ---
        // Format tag (Movie / TV Series)
        val typeText = item.type.replaceFirstChar { it.uppercase() }
        if (typeText.isNotBlank()) {
            holder.formatTag.text = typeText
            holder.formatTag.isVisible = true
        } else {
            holder.formatTag.isVisible = false
        }

        // Status tag (pre-fetched from TMDB detail, optional)
        val statusText = statusByIndex[pos]
        if (!statusText.isNullOrBlank()) {
            holder.statusTag.text = statusText
            holder.statusTag.isVisible = true
        } else {
            holder.statusTag.isVisible = false
        }

        // Season/Year tag
        val year = item.year
        if (year.isNotBlank()) {
            holder.seasonTag.text = year
            holder.seasonTag.isVisible = true
        } else {
            holder.seasonTag.isVisible = false
        }

        // Score tag
        val score = scoreByIndex[pos]
        if (!score.isNullOrBlank()) {
            holder.scoreTag.text = score
            holder.scoreTag.isVisible = true
        } else {
            holder.scoreTag.isVisible = false
        }

        // --- Description (hidden in cardMode) ---
        holder.description.isVisible = false

        // --- Genre chips (transparent pills) ---
        holder.genresRow.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        when (item) {
            is TmdbHomeFragment.BannerItem.Tmdb -> {
                val genres = item.media.genreIds.mapNotNull { genreNames[it] }
                for (genre in genres.take(4)) {
                    val chip = TextView(ctx).apply {
                        text = genre
                        setTextColor(ContextCompat.getColor(ctx, R.color.bg_white))
                        textSize = 11f
                        setBackgroundResource(R.drawable.tag_chip_bg)
                        setPadding(
                            (10 * density).toInt(),
                            (3 * density).toInt(),
                            (10 * density).toInt(),
                            (3 * density).toInt()
                        )
                        maxLines = 1
                        isFocusable = false
                    }
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginEnd = (6 * density).toInt()
                    holder.genresRow.addView(chip, lp)
                }
                holder.genresRow.isVisible = genres.isNotEmpty()
            }
            is TmdbHomeFragment.BannerItem.Plugin -> {
                holder.genresRow.isVisible = false
            }
        }

        // --- Hide play / fav buttons (cardMode) ---
        holder.playBtn.isVisible = false
        holder.favBtn.isVisible = false

        // --- Click (touch only). The carousel is intentionally NOT D-pad
        // focusable — the watch-now button is the banner's focus point and
        // moves this carousel left/right, exactly like anime mode. ---
        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false
        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener { onItemClick(item) }

        // --- Landscape overlay ---
        applyLandscapeOverlay(holder)

        // --- Preload adjacent ---
        for (offset in listOf(-1, 1)) {
            val adjPos = realPosition(position + offset)
            if (adjPos in items.indices) {
                items[adjPos].bannerUrl?.let { Glide.with(ctx).load(it).preload() }
            }
        }
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else Int.MAX_VALUE

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bannerBg: ImageView = view.findViewById(R.id.bannerBg)
        val bannerImage: ImageView = view.findViewById(R.id.bannerImage)
        val clearlogo: ImageView = view.findViewById(R.id.bannerClearlogo)
        val title: TextView = view.findViewById(R.id.bannerTitle)
        val formatTag: TextView = view.findViewById(R.id.bannerFormatTag)
        val statusTag: TextView = view.findViewById(R.id.bannerStatusTag)
        val seasonTag: TextView = view.findViewById(R.id.bannerSeasonTag)
        val scoreTag: TextView = view.findViewById(R.id.bannerScoreTag)
        val description: TextView = view.findViewById(R.id.bannerDescription)
        val genresRow: LinearLayout = view.findViewById(R.id.bannerGenresRow)
        val playBtn: android.widget.Button = view.findViewById(R.id.bannerPlayBtn)
        val favBtn: ImageView = view.findViewById(R.id.bannerFavBtn)
        val scrim: View? = view.findViewById(R.id.bannerScrimLeft)
        val content: LinearLayout? = view.findViewById(R.id.bannerContent)
    }

    private fun applyLandscapeOverlay(holder: ViewHolder) {
        val scrim = holder.scrim ?: return
        val content = holder.content ?: return
        val density = holder.itemView.context.resources.displayMetrics.density
        if (!landscapeMode) {
            scrim.isVisible = false
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            val pad = (12 * density).toInt()
            content.setPadding(pad, pad, pad, pad)
            holder.clearlogo.maxWidth = (160 * density).toInt()
            holder.clearlogo.maxHeight = (40 * density).toInt()
            holder.title.isVisible = true
            holder.formatTag.isVisible = true
            holder.statusTag.isVisible = true
            holder.seasonTag.isVisible = true
            holder.scoreTag.isVisible = true
            holder.genresRow.isVisible = true
            return
        }
        // Landscape (anime-exact): the card shows only the image + left-half
        // scrim; the metadata lives in the side panel (tmdbBannerSide).
        val half = cardWidthPx / 2
        scrim.isVisible = true
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        holder.clearlogo.isVisible = false
        holder.title.isVisible = false
        holder.formatTag.isVisible = false
        holder.statusTag.isVisible = false
        holder.seasonTag.isVisible = false
        holder.scoreTag.isVisible = false
        holder.description.isVisible = false
        holder.genresRow.isVisible = false
    }
}
