package ani.sanin.home

import android.graphics.drawable.Drawable
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
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.loadImage
import ani.sanin.media.Media
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
class BannerCarouselAdapter(
    private val items: List<Media>,
    private val scope: CoroutineScope,
    private val onItemClick: (Media) -> Unit,
    private var backdropUrls: Map<Int, String?> = emptyMap(),
    private var logoUrls: Map<Int, String?> = emptyMap(),
    var nextFocusDownId: Int = View.NO_ID,
    private val layoutRes: Int = R.layout.item_banner_carousel,
    private val cardMode: Boolean = false,
    private val hideDescription: Boolean = false,
) : RecyclerView.Adapter<BannerCarouselAdapter.ViewHolder>() {

    private var landscapeOverlay = false
    private var cardWidthPx = 0

    fun setLandscapeMode(enabled: Boolean, cardWidthPx: Int) {
        if (this.landscapeOverlay == enabled && this.cardWidthPx == cardWidthPx) return
        this.landscapeOverlay = enabled
        this.cardWidthPx = cardWidthPx
        notifyDataSetChanged()
    }

    val actualCount: Int get() = items.size

    fun realPosition(virtualPos: Int): Int = virtualPos % items.size

    fun updateUrls(backdrops: Map<Int, String?>, logos: Map<Int, String?>) {
        backdropUrls = backdrops
        logoUrls = logos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val media = items[realPosition(position)]
        val ctx = holder.itemView.context

        // --- Banner image (AniZip backdrop, fallback AniList banner/cover) ---
        val anizipUrl = backdropUrls[media.id]
        val imageUrl = if (!anizipUrl.isNullOrBlank()) anizipUrl
                       else media.banner ?: media.cover
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.visibility = View.VISIBLE
            holder.bannerImage.visibility = View.VISIBLE
            holder.bannerImage.scaleType = ImageView.ScaleType.FIT_CENTER
            Glide.with(holder.itemView.context)
                .load(imageUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .into(holder.bannerBg)
            Glide.with(holder.itemView.context)
                .load(imageUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .listener(object : RequestListener<Drawable> {
                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>,
                        dataSource: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        holder.bannerImage.scaleType = if (resource.intrinsicHeight > resource.intrinsicWidth)
                            ImageView.ScaleType.CENTER_CROP
                        else
                            ImageView.ScaleType.FIT_CENTER
                        return false
                    }
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        holder.bannerImage.scaleType = ImageView.ScaleType.FIT_CENTER
                        return false
                    }
                })
                .into(holder.bannerImage)
        }

        // --- Clearlogo (pre-fetched) / Title fallback ---
        holder.title.text = media.userPreferredName ?: media.name
        holder.title.isVisible = true
        holder.clearlogo.isVisible = false
        holder.clearlogo.setImageDrawable(null)
        val logoUrl = logoUrls[media.id]
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.title.isVisible = false
            com.bumptech.glide.Glide.with(holder.clearlogo.context)
                .load(logoUrl)
                .override(240, 64)
                .into(holder.clearlogo)
        }

        // --- Format tag ---
        val formatText = media.format?.replace("_", " ")?.let { fmt ->
            when {
                fmt.equals("TV", true) -> "TV Series"
                fmt.equals("TV_SHORT", true) -> "TV Short"
                else -> fmt
            }
        }
        if (!formatText.isNullOrBlank()) {
            holder.formatTag.text = formatText
            holder.formatTag.isVisible = true
        } else {
            holder.formatTag.isVisible = false
        }

        // --- Status tag ---
        val statusText = media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
        if (!statusText.isNullOrBlank()) {
            holder.statusTag.text = statusText
            holder.statusTag.isVisible = true
        } else {
            holder.statusTag.isVisible = false
        }

        // --- Season tag ---
        val season = media.anime?.season?.lowercase()
        val year = media.anime?.seasonYear
        val seasonText = if (season != null && year != null) "$season $year" else null
        if (seasonText != null) {
            holder.seasonTag.text = seasonText
            holder.seasonTag.isVisible = true
        } else {
            holder.seasonTag.isVisible = false
        }

        // --- Score tag ---
        val score = media.meanScore
        if (score != null) {
            holder.scoreTag.text = "$score%"
            holder.scoreTag.isVisible = true
        } else {
            holder.scoreTag.isVisible = false
        }

        // --- Description ---
        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (hideDescription) {
            holder.description.isVisible = false
        } else if (!desc.isNullOrBlank()) {
            holder.description.text = desc
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        // --- Genre chips ---
        holder.genresRow.removeAllViews()
        if (media.genres.isNotEmpty()) {
            val density = ctx.resources.displayMetrics.density
            for (genre in media.genres.take(4)) {
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
            holder.genresRow.isVisible = true
        } else {
            holder.genresRow.isVisible = false
        }

        // --- Play button ---
        holder.playBtn.setOnClickListener { onItemClick(media) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.visibility = View.VISIBLE

        // --- Favorite button ---
        val isFav = media.isFav
        holder.favBtn.setImageDrawable(
            ContextCompat.getDrawable(
                ctx,
                if (isFav) R.drawable.ic_round_favorite_24
                else R.drawable.ic_round_favorite_border_24
            )
        )
        holder.favBtn.setOnClickListener {
            val newState = !media.isFav
            media.isFav = newState
            holder.favBtn.setImageDrawable(
                ContextCompat.getDrawable(
                    ctx,
                    if (newState) R.drawable.ic_round_favorite_24
                    else R.drawable.ic_round_favorite_border_24
                )
            )
            scope.launch(Dispatchers.IO) {
                Anilist.mutation.toggleFav(media.anime != null, media.id)
            }
        }
        holder.favBtn.visibility = View.VISIBLE

        // --- Item click ---
        holder.itemView.setOnClickListener { onItemClick(media) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false

        // --- D-pad focus chain ---
        if (nextFocusDownId != View.NO_ID) {
            holder.playBtn.nextFocusDownId = nextFocusDownId
            holder.favBtn.nextFocusDownId = nextFocusDownId
        }

        if (cardMode) {
            holder.description.isVisible = false
            holder.playBtn.isVisible = false
            holder.favBtn.isVisible = false
        }

        applyLandscapeOverlay(holder)

        // --- Preload adjacent items ---
        for (offset in listOf(-1, 1)) {
            val pos = realPosition(position + offset)
            if (pos in items.indices) {
                val item = items[pos]
                val url = backdropUrls[item.id] ?: item.banner ?: item.cover
                if (!url.isNullOrBlank()) {
                    Glide.with(ctx).load(url).preload()
                }
            }
        }
    }

    override fun getItemCount() = if (items.isEmpty()) 0 else Int.MAX_VALUE

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
        val bottomGradient: View? = view.findViewById(R.id.bannerBottomGradient)
    }

    private fun applyLandscapeOverlay(holder: ViewHolder) {
        val scrim = holder.scrim ?: return
        val content = holder.content ?: return
        val bottomGradient = holder.bottomGradient ?: return
        val density = holder.itemView.context.resources.displayMetrics.density
        if (!landscapeOverlay) {
            scrim.isVisible = false
            bottomGradient.isVisible = true
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.BOTTOM
            content.layoutParams = lp
            val pad = (12 * density).toInt()
            content.setPadding(pad, pad, pad, pad)
            holder.clearlogo.maxWidth = (160 * density).toInt()
            holder.clearlogo.maxHeight = (40 * density).toInt()
            return
        }
        val half = cardWidthPx / 2
        scrim.isVisible = true
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        bottomGradient.isVisible = false
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
