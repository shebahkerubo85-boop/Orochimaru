package ani.sanin.home

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.isLargeBanner
import ani.sanin.getThemeColor

class TmdbBannerCarouselAdapter(
    private val items: List<TmdbHomeFragment.BannerItem>,
    private val onItemClick: (TmdbHomeFragment.BannerItem) -> Unit,
    private val genreNames: Map<Int, String>,
    private var logoUrls: Map<Int, String?> = emptyMap(),
    private var statusByIndex: Map<Int, String?> = emptyMap(),
    private var scoreByIndex: Map<Int, String?> = emptyMap(),
    private val modernMode: Boolean = false,
) : RecyclerView.Adapter<TmdbBannerCarouselAdapter.ViewHolder>() {

    private var detailsByIndex: Map<Int, TmdbDetail?> = emptyMap()
    private var postersByIndex: Map<Int, String?> = emptyMap()

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
            .inflate(
                if (modernMode) R.layout.item_banner_modern else R.layout.item_banner_card,
                parent,
                false,
            )
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pos = realPosition(position)
        val item = items[pos]
        val ctx = holder.itemView.context

        if (modernMode) {
            bindModern(holder, item, pos)
            return
        }

        // --- Banner image ---
        val imageUrl = item.bannerUrl
        val bannerImage = holder.bannerImage
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerImage?.isVisible = true
            holder.bannerBg.isVisible = true
            bannerImage?.scaleType = if (isLargeBanner()) android.widget.ImageView.ScaleType.CENTER_CROP
                else android.widget.ImageView.ScaleType.FIT_CENTER
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black).error(R.drawable.ic_round_person_24)
                .into(holder.bannerBg)
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black).error(R.drawable.ic_round_person_24)
                .into(bannerImage ?: holder.bannerBg)
        } else {
            bannerImage?.isVisible = false
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
        val formatTag = holder.formatTag
        if (typeText.isNotBlank() && formatTag != null) {
            formatTag.text = typeText
            formatTag.isVisible = true
        } else {
            formatTag?.isVisible = false
        }

        // Status tag (pre-fetched from TMDB detail, optional)
        val statusText = statusByIndex[pos]
        val statusTag = holder.statusTag
        if (!statusText.isNullOrBlank() && statusTag != null) {
            statusTag.text = statusText
            statusTag.isVisible = true
        } else {
            statusTag?.isVisible = false
        }

        // Season/Year tag. A plugin result carries no year of its own, so the
        // matched TMDB entry supplies it.
        val year = item.year.ifBlank { detailsByIndex[pos]?.year.orEmpty() }
        val seasonTag = holder.seasonTag
        if (year.isNotBlank() && seasonTag != null) {
            seasonTag.text = year
            seasonTag.isVisible = true
        } else {
            seasonTag?.isVisible = false
        }

        // Score tag, falling back to the detail's rating for plugin items.
        val score = scoreByIndex[pos]
            ?: detailsByIndex[pos]?.voteAverage?.takeIf { it > 0.0 }
                ?.let { String.format("%.1f", it) + "%" }
        val scoreTag = holder.scoreTag
        if (!score.isNullOrBlank() && scoreTag != null) {
            scoreTag.text = score
            scoreTag.isVisible = true
        } else {
            scoreTag?.isVisible = false
        }

        // --- Description (hidden in cardMode) ---
        holder.description.isVisible = false

        // --- Genre chips (transparent pills) ---
        val genresRow = holder.genresRow
        genresRow?.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        when (item) {
            is TmdbHomeFragment.BannerItem.Tmdb -> {
                val genres = item.media.genreIds.mapNotNull { genreNames[it] }
                for (genre in genres.take(4)) {
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
                    genresRow?.addView(chip, lp)
                }
                if (genresRow != null) genresRow.isVisible = genres.isNotEmpty()
            }
            is TmdbHomeFragment.BannerItem.Plugin -> {
                val genres = detailsByIndex[pos]?.genres?.map { it.name }.orEmpty()
                for (genre in genres.take(4)) {
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
                    genresRow?.addView(chip, lp)
                }
                if (genresRow != null) genresRow.isVisible = genres.isNotEmpty()
            }
        }

        // --- Hide play / fav buttons (cardMode) ---
        holder.playBtn.isVisible = false
        holder.favBtn?.isVisible = false

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
            holder.formatTag?.isVisible = true
            holder.statusTag?.isVisible = true
            holder.seasonTag?.isVisible = true
            holder.scoreTag?.isVisible = true
            holder.genresRow?.isVisible = true
            return
        }
        // Landscape (anime-exact): the card shows only the image + left-half
        // scrim; the metadata lives in the side panel (tmdbBannerSide).
        // Large type: no left 3-layer scrim, only the gradient below.
        val half = cardWidthPx / 2
        scrim.isVisible = !isLargeBanner()
        scrim.layoutParams = scrim.layoutParams.apply { width = half }
        holder.clearlogo.isVisible = false
        holder.title.isVisible = false
        holder.formatTag?.isVisible = false
        holder.statusTag?.isVisible = false
        holder.seasonTag?.isVisible = false
        holder.scoreTag?.isVisible = false
        holder.description.isVisible = false
        holder.genresRow?.isVisible = false
    }

    /**
     * Modern mode. Metadata comes from TMDB wherever it exists — including for
     * plugin items, which are matched to a TMDB entry upstream. Anything TMDB
     * does not know about is simply left out, so an unmatched plugin still
     * renders as a plain title over its artwork.
     */
    private fun bindModern(holder: ViewHolder, item: TmdbHomeFragment.BannerItem, pos: Int) {
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val detail = detailsByIndex[pos]

        // --- Background art ---
        val imageUrl = item.bannerUrl
        if (!imageUrl.isNullOrBlank()) {
            holder.bannerBg.isVisible = true
            holder.bannerBg.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            Glide.with(ctx).load(imageUrl).placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24).into(holder.bannerBg)
        } else {
            holder.bannerBg.isVisible = false
        }

        // --- Portrait poster: TMDB's own, else the plugin's ---
        val poster = holder.poster
        val posterUrl = postersByIndex[pos] ?: (item as? TmdbHomeFragment.BannerItem.Plugin)?.response?.posterUrl
        if (!posterUrl.isNullOrBlank() && poster != null) {
            poster.isVisible = true
            Glide.with(ctx).load(posterUrl).placeholder(R.color.bg_black)
                .error(R.drawable.ic_round_person_24).into(poster)
        } else {
            poster?.isVisible = false
        }

        // --- Logo art, falling back to the title text ---
        val logoUrl = logoUrls[pos]
        if (!logoUrl.isNullOrBlank()) {
            holder.clearlogo.isVisible = true
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = false
            Glide.with(ctx).load(logoUrl).override(520, 160).into(holder.clearlogo)
        } else {
            holder.clearlogo.isVisible = false
            holder.clearlogo.setImageDrawable(null)
            holder.title.isVisible = true
            holder.title.text = item.title
        }

        // --- Synopsis ---
        val overview = detail?.overview?.replace(Regex("\\s+"), " ")?.trim()
        if (!overview.isNullOrBlank()) {
            holder.description.text = overview
            holder.description.isVisible = true
        } else {
            holder.description.isVisible = false
        }

        // --- Genres · Year · Rating · Status · Type ---
        val metaRow = holder.metaRow
        metaRow?.removeAllViews()
        metaRow?.let { row ->
            val genres = when (item) {
                is TmdbHomeFragment.BannerItem.Tmdb ->
                    item.media.genreIds.mapNotNull { genreNames[it] }
                is TmdbHomeFragment.BannerItem.Plugin ->
                    detail?.genres?.map { it.name }.orEmpty()
            }
            for (genre in genres.take(MODERN_MAX_GENRES)) {
                addModernMetaItem(row, genre, density)
            }
            addModernMetaItem(row, detail?.year?.takeIf { it.isNotBlank() }, density)
            val rating = detail?.voteAverage?.takeIf { it > 0.0 }
                ?: (item as? TmdbHomeFragment.BannerItem.Tmdb)?.media?.voteAverage?.takeIf { it > 0.0 }
            if (rating != null) {
                addModernMetaItem(row, String.format("%.1f", rating), density, withStar = true)
            }
            addModernMetaItem(row, statusByIndex[pos], density, accent = true)
            addModernMetaItem(
                row,
                (item as? TmdbHomeFragment.BannerItem.Tmdb)?.media?.type,
                density,
            )
            row.isVisible = row.childCount > 0
        }

        // --- Watch Now: the banner's only D-pad stop ---
        holder.playBtn.setOnClickListener { onItemClick(item) }
        holder.playBtn.isFocusable = true
        holder.playBtn.isFocusableInTouchMode = false
        holder.playBtn.isVisible = true

        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    /** Late-arriving TMDB enrichment, keyed by real (non-virtual) banner index. */
    fun updateDetails(
        logos: Map<Int, String?>,
        statuses: Map<Int, String?>,
        scores: Map<Int, String?>,
        details: Map<Int, TmdbDetail?>,
        posters: Map<Int, String?>,
    ) {
        logoUrls = logos
        statusByIndex = statuses
        scoreByIndex = scores
        detailsByIndex = details
        postersByIndex = posters
        notifyDataSetChanged()
    }
}
