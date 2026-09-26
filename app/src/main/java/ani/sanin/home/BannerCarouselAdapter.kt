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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ani.sanin.R
import ani.sanin.isLargeBanner
import ani.sanin.connections.anilist.Anilist
import ani.sanin.getThemeColor
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
    private val modernMode: Boolean = false,
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

        if (modernMode) {
            bindModern(holder, media)
            return
        }

        // --- Banner image (AniZip backdrop, fallback AniList banner/cover) ---
        val anizipUrl = backdropUrls[media.id]
        val imageUrl = if (!anizipUrl.isNullOrBlank()) anizipUrl
                       else media.banner ?: media.cover
        val bannerImage = holder.bannerImage
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.visibility = View.VISIBLE
            bannerImage?.visibility = View.VISIBLE
            bannerImage?.scaleType = if (isLargeBanner()) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
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
                        bannerImage?.scaleType = if (isLargeBanner() ||
                            resource.intrinsicHeight > resource.intrinsicWidth)
                            ImageView.ScaleType.CENTER_CROP
                        else
                            ImageView.ScaleType.FIT_CENTER
                        return false
                    }
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        bannerImage?.scaleType = if (isLargeBanner()) ImageView.ScaleType.CENTER_CROP
                            else ImageView.ScaleType.FIT_CENTER
                        return false
                    }
                })
                .into(bannerImage ?: holder.bannerBg)
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
        val formatTag = holder.formatTag
        if (!formatText.isNullOrBlank() && formatTag != null) {
            formatTag.text = formatText
            formatTag.isVisible = true
        } else {
            formatTag?.isVisible = false
        }

        // --- Status tag ---
        val statusText = media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
        val statusTag = holder.statusTag
        if (!statusText.isNullOrBlank() && statusTag != null) {
            statusTag.text = statusText
            statusTag.isVisible = true
        } else {
            statusTag?.isVisible = false
        }

        // --- Season tag ---
        val season = media.anime?.season?.lowercase()
        val year = media.anime?.seasonYear
        val seasonText = if (season != null && year != null) "$season $year" else null
        val seasonTag = holder.seasonTag
        if (seasonText != null && seasonTag != null) {
            seasonTag.text = seasonText
            seasonTag.isVisible = true
        } else {
            seasonTag?.isVisible = false
        }

        // --- Score tag ---
        val score = media.meanScore
        val scoreTag = holder.scoreTag
        if (score != null && scoreTag != null) {
            scoreTag.text = "$score%"
            scoreTag.isVisible = true
        } else {
            scoreTag?.isVisible = false
        }

        // --- Description ---
        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (hideDescription || isLargeBanner()) {
            holder.description.isVisible = false
        } else if (!desc.isNullOrBlank()) {
            holder.description.text = desc
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        // --- Genre chips ---
        val genresRow = holder.genresRow
        genresRow?.removeAllViews()
        if (media.genres.isNotEmpty() && genresRow != null) {
            val density = ctx.resources.displayMetrics.density
            for (genre in media.genres.take(4)) {
                val chip = TextView(ctx).apply {
                    text = genre
                    setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground))
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
                genresRow.addView(chip, lp)
            }
            genresRow.isVisible = true
        } else {
            genresRow?.isVisible = false
        }

        // --- Play button ---
        holder.playBtn.setOnClickListener { onItemClick(media) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.visibility = View.VISIBLE

        // --- Favorite button ---
        val favBtn = holder.favBtn
        val isFav = media.isFav
        favBtn?.setImageDrawable(
            ContextCompat.getDrawable(
                ctx,
                if (isFav) R.drawable.ic_round_favorite_24
                else R.drawable.ic_round_favorite_border_24
            )
        )
        favBtn?.setOnClickListener {
            val newState = !media.isFav
            media.isFav = newState
            favBtn.setImageDrawable(
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
        favBtn?.visibility = View.VISIBLE

        // --- Item click ---
        holder.itemView.setOnClickListener { onItemClick(media) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false

        // --- D-pad focus chain ---
        if (nextFocusDownId != View.NO_ID) {
            holder.playBtn.nextFocusDownId = nextFocusDownId
            favBtn?.nextFocusDownId = nextFocusDownId
        }

        if (cardMode) {
            holder.description.isVisible = false
            holder.playBtn.isVisible = false
            favBtn?.isVisible = false
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

    /**
     * Modern mode: the landscape artwork is the background and every piece of metadata
     * lives inside the banner, so the side overlay and the compact tag chips are unused.
     */
    private fun bindModern(holder: ViewHolder, media: Media) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        applyModernContentWidth(holder.itemView, holder.modernContent)

        // --- Background art: AniZip backdrop, then AniList banner, then cover. ---
        val imageUrl = backdropUrls[media.id]?.takeIf { it.isNotBlank() }
            ?: media.banner?.takeIf { it.isNotBlank() }
            ?: media.cover
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.isVisible = true
            holder.bannerBg.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(ctx)
                .load(imageUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .into(holder.bannerBg)
        } else {
            holder.bannerBg.isVisible = false
        }

        // --- Portrait poster, floated on the right. ---
        val poster = holder.poster
        val posterUrl = media.cover
        if (!posterUrl.isNullOrBlank() && poster != null) {
            poster.isVisible = true
            Glide.with(ctx)
                .load(posterUrl)
                .placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24)
                .into(poster)
        } else {
            poster?.isVisible = false
        }

        // --- Logo art, falling back to the title text. ---
        val logoUrl = logoUrls[media.id]
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = false
            Glide.with(ctx)
                .load(logoUrl)
                .override(520, 160)
                .into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = true
            holder.title.text = media.userPreferredName ?: media.name
        }

        // --- Synopsis ---
        val desc = media.description
            ?.replace(Regex("<.*?>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (!desc.isNullOrBlank()) {
            holder.description.text = desc
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        // --- Genres · Year · Rating · Status · Type · Country ---
        val metaRow = holder.metaRow
        metaRow?.removeAllViews()
        metaRow?.let { row ->
            for (genre in media.genres.take(MODERN_MAX_GENRES)) {
                addModernMetaItem(row, genre, density)
            }
            addModernMetaItem(row, media.anime?.seasonYear?.toString(), density)
            media.meanScore?.let { score ->
                // AniList scores 0-100; the meta row shows them out of 10.
                addModernMetaItem(row, String.format("%.1f", score / 10f), density, withStar = true)
            }
            addModernMetaItem(
                row,
                media.status?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() },
                density,
                accent = true,
            )
            addModernMetaItem(row, media.format?.replace("_", " "), density)
            addModernMetaItem(row, media.countryOfOrigin, density)
            row.isVisible = row.childCount > 0
        }

        // --- Watch Now: the banner's only D-pad stop, so it drives slide navigation. ---
        holder.playBtn.setOnClickListener { onItemClick(media) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.isVisible = true

        holder.itemView.setOnClickListener { onItemClick(media) }
        // Watch Now is the banner's only D-pad stop, so the card itself stays
        // out of the focus chain.
        holder.itemView.isFocusable = false
        holder.itemView.isFocusableInTouchMode = false

        if (nextFocusDownId != View.NO_ID) {
            holder.playBtn.nextFocusDownId = nextFocusDownId
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bannerBg: ImageView = view.findViewById(R.id.bannerBg)
        val bannerImage: ImageView? = view.findViewById(R.id.bannerImage)
        val clearlogo: ImageView = view.findViewById(R.id.bannerClearlogo)
        val title: TextView = view.findViewById(R.id.bannerTitle)
        val formatTag: TextView? = view.findViewById(R.id.bannerFormatTag)
        val statusTag: TextView? = view.findViewById(R.id.bannerStatusTag)
        val seasonTag: TextView? = view.findViewById(R.id.bannerSeasonTag)
        val scoreTag: TextView? = view.findViewById(R.id.bannerScoreTag)

        val description: TextView = view.findViewById(R.id.bannerDescription)
        val genresRow: LinearLayout? = view.findViewById(R.id.bannerGenresRow)
        val metaRow: LinearLayout? = view.findViewById(R.id.bannerMetaRow)

        val playBtn: android.widget.Button = view.findViewById(R.id.bannerPlayBtn)
        val favBtn: ImageView? = view.findViewById(R.id.bannerFavBtn)
        val poster: ImageView? = view.findViewById(R.id.bannerModernPoster)
        val modernContent: LinearLayout? = view.findViewById(R.id.bannerModernContent)

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
        // Large type: no left 3-layer scrim, only the gradient below.
        val largeBanner = isLargeBanner()
        scrim.isVisible = !largeBanner
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        bottomGradient.isVisible = largeBanner
        holder.clearlogo.isVisible = false
        holder.title.isVisible = false
        holder.formatTag?.isVisible = false
        holder.statusTag?.isVisible = false
        holder.seasonTag?.isVisible = false
        holder.scoreTag?.isVisible = false
        holder.description.isVisible = false
        holder.genresRow?.isVisible = false
    }
}

/** Genres get their own budget in the meta row; the rest of the line is short. */
const val MODERN_MAX_GENRES = 3

/** How long a banner slide should take, in ms. */
private const val BANNER_SCROLL_MS = 400f

/** The content column is capped to this fraction of the banner so it clears the poster. */
private const val MODERN_CONTENT_WIDTH_FRACTION = 0.58f

/**
 * Caps the Modern banner's content column to a fraction of the banner width.
 * AAPT2 rejects a percentage on a FrameLayout child's layout_width, so the
 * fraction is applied here once the item has been measured.
 */
internal fun applyModernContentWidth(root: View, content: View?) {
    content ?: return
    val apply = {
        val total = root.width
        if (total > 0) {
            val target = (total * MODERN_CONTENT_WIDTH_FRACTION).toInt()
            val lp = content.layoutParams
            if (lp != null && lp.width != target) {
                lp.width = target
                content.layoutParams = lp
            }
        }
    }
    if (root.width > 0) apply() else root.post { apply() }
}

/**
 * A snap scroller in the 400ms window the banner spec asks for, with no app-side
 * per-frame work: RecyclerView drives it from its own layout pass, so it costs
 * nothing on a low-RAM device. The default item animator is switched off for the
 * banner instead, since its full-screen cross-fade is the expensive part.
 */
internal fun scrollBanner(rv: RecyclerView, position: Int) {
    val lm = rv.layoutManager as? LinearLayoutManager ?: run {
        rv.smoothScrollToPosition(position)
        return
    }
    if (rv.width <= 0) {
        rv.smoothScrollToPosition(position)
        return
    }
    val steps = kotlin.math.abs(position - lm.findFirstVisibleItemPosition())
        .coerceAtLeast(1)
    val pxPerMs = (rv.width * steps).toFloat() / BANNER_SCROLL_MS
    val scroller = object : LinearSmoothScroller(rv.context) {
        override fun getHorizontalSnapPreference() = SNAP_TO_START
        override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics) = pxPerMs

        // No deceleration phase, so the move is a plain constant-speed 400ms
        // rather than RecyclerView's default speed-then-decel.
        override fun calculateTimeForDeceleration(msScroller: Int) = 0
    }
    scroller.targetPosition = position
    lm.startSmoothScroll(scroller)
}

/** Slightly muted white for the metadata line. */
private val MODERN_META_MUTED = 0xCCFFFFFF.toInt()

/**
 * Appends one item to a Modern banner's metadata line, prefixed by a centred
 * dot when it is not the first. Blank text is skipped, so callers can pass
 * whatever fields happen to be populated.
 */
internal fun addModernMetaItem(
    row: LinearLayout,
    value: String?,
    density: Float,
    withStar: Boolean = false,
    accent: Boolean = false,
) {
    if (value.isNullOrBlank()) return
    val ctx = row.context
    val res = ctx.resources
    val textSp = res.getDimension(R.dimen.banner_modern_meta_text) / res.displayMetrics.density
    val gap = res.getDimension(R.dimen.banner_modern_meta_gap).toInt()

    if (row.childCount > 0) {
        val dot = TextView(ctx).apply {
            setText("·")
            setTextColor(MODERN_META_MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp)
            includeFontPadding = false
            isFocusable = false
        }
        row.addView(
            dot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = gap
                marginEnd = gap
            },
        )
    }

    val item = TextView(ctx).apply {
        setText(value)
        setTextColor(
            if (accent) ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
            else MODERN_META_MUTED
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        isFocusable = false
        if (withStar) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_star_rating, 0, 0, 0)
            compoundDrawablePadding = (3 * density).toInt()
        }
    }
    row.addView(
        item,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
}
