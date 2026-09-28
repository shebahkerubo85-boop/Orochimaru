package ani.sanin.cloudstream

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object TmdbCards {

    /** Card density for every grid in the app, in dp. */
    const val GRID_COLUMN_DP = 120f

    /**
     * Vertical gap between grid rows, matching the card's 12dp horizontal margin.
     *
     * The card carries a horizontal margin but no vertical one, and none of the grids use
     * an ItemDecoration, so the bottom of one row sat flush against the top of the next.
     * Applied on the grid path only: a rail is a single row, where a bottom margin would
     * add dead space instead of separating anything.
     */
    const val GRID_ROW_GAP_DP = 12f

    fun gridRowGapPx(recyclerView: RecyclerView): Int =
        (GRID_ROW_GAP_DP * recyclerView.resources.displayMetrics.density).toInt()

    private val logoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isLandscapeOrientation(): Boolean = PrefManager.getVal<Int>(PrefName.CardOrientation) == 0

    fun cardSize(): Float = PrefManager.getVal(PrefName.CardSize)

    /**
     * Columns for a grid, cut from the screen width at a fixed density.
     *
     * The card-size preference is a *rail* setting. A rail scrolls sideways, so an
     * over-wide card only pushes the next one further away, which is why a row can honour
     * the preference and still look right. A grid has nowhere to scroll to: leave the
     * cards at the preferred width and three of them simply will not fit on a phone, which
     * is how TMDB discovery ended up two-up while anime discovery, which fills its cells,
     * was three-up.
     *
     * So a grid ignores the preference and sizes cards by density instead, giving every
     * grid the same look and letting the count follow the screen — three on a phone, more
     * on a tablet or a TV. There is deliberately no ceiling: a hardcoded 3 is right on one
     * screen and wrong on every other one.
     */
    fun gridSpan(screenWidthDp: Float, columnDp: Float = GRID_COLUMN_DP): Int =
        (screenWidthDp / columnDp).toInt().coerceAtLeast(2)

    /**
     * Width of one grid cell, so a card fills its column exactly at any span.
     *
     * None of the grids use an [androidx.recyclerview.widget.RecyclerView.ItemDecoration],
     * so cell width is just the padded width split evenly.
     */
    fun cellWidthPx(recyclerView: RecyclerView, span: Int): Int {
        val usable = recyclerView.width -
            recyclerView.paddingStart - recyclerView.paddingEnd
        return (usable / span).coerceAtLeast(1)
    }

    fun roundness(): Float {
        return when (PrefManager.getVal<Int>(PrefName.CardStyle)) {
            4 -> 24f
            6 -> 4f
            else -> PrefManager.getVal<Int>(PrefName.StandardCardRoundness).toFloat()
        }
    }

    /**
     * Applies the user's card settings (size, orientation, roundness) and the
     * Nuvio TV landscape design: landscape cards use the 16:9 backdrop, shown
     * uncropped, with the title (or TMDB logo art when available) over a
     * bottom gradient at the bottom-left.
     */
    fun applyCardStyle(binding: ItemTmdbCardBinding, item: TmdbMedia, cellWidth: Int? = null) {
        val landscape = isLandscapeOrientation()
        val size = cardSize()
        val (baseW, baseH) = if (landscape) 260f to 148f else 102f to 154f
        val (w, h) = if (cellWidth != null) {
            // In a grid the column decides the width, not the card preference. The height
            // follows from the same aspect ratio the rail cards use, so a 3-up phone grid
            // gets smaller posters rather than cropped or overlapping ones.
            cellWidth to (cellWidth * baseH / baseW).toInt()
        } else {
            (baseW * size).toInt() to (baseH * size).toInt()
        }
        binding.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
            width = w
            height = h
        }
        val radius = roundness()
        binding.tmdbCard.radius = radius
        // Keep rating pill inset from the rounded corner so it never clips
        val pillInset = radius.toInt().coerceIn(6, 14)
        binding.tmdbCardRating.updateLayoutParams<FrameLayout.LayoutParams> {
            topMargin = pillInset
            marginEnd = pillInset
        }

        val image = if (landscape) {
            Tmdb.imageUrl(item.backdropPath ?: item.posterPath, 780)
        } else {
            Tmdb.imageUrl(item.posterPath, 300)
        }
        binding.tmdbCardPoster.loadImage(image)

        val gradient = binding.tmdbCardGradient
        val logo = binding.tmdbCardLogo
        val overlayTitle = binding.tmdbCardOverlayTitle

        val titlePosition = PrefManager.getVal<Int>(PrefName.CardTitlePosition)

        if (landscape) {
            // Landscape: respect CardTitlePosition setting
            when (titlePosition) {
                0 -> {
                    // Overlay: gradient + logo/title at bottom (default landscape)
                    gradient.isVisible = true
                    gradient.updateLayoutParams<ViewGroup.LayoutParams> {
                        width = w; height = h
                    }
                    overlayTitle.updateLayoutParams<ViewGroup.LayoutParams> { width = w }
                    setCardGradient(gradient)
                    overlayTitle.text = item.displayTitle
                    val token = "${item.type}:${item.id}"
                    if (logo.tag != token) {
                        logo.tag = token
                        Glide.with(logo.context).clear(logo)
                        logo.setImageDrawable(null)
                    }
                    logoScope.launch {
                        val url = runCatching { Tmdb.logoUrl(item.type, item.id) }.getOrNull()
                        val current = logo.tag
                        if (current != token) return@launch
                        binding.root.post {
                            if (logo.tag != token) return@post
                            if (url != null) {
                                Glide.with(logo.context)
                                    .load(url)
                                    .override((w * 0.7f).toInt().coerceIn(60, 180), ((w * 0.7f).toInt().coerceIn(60, 180) * 0.4f).toInt())
                                    .listener(object : RequestListener<Drawable> {
                                        override fun onLoadFailed(
                                            e: GlideException?, model: Any?,
                                            target: Target<Drawable>?, isFirstResource: Boolean
                                        ): Boolean { overlayTitle.isVisible = true; return false }
                                        override fun onResourceReady(
                                            resource: Drawable?, model: Any?,
                                            target: Target<Drawable>?,
                                            dataSource: DataSource?, isFirstResource: Boolean
                                        ): Boolean = false
                                    })
                                    .into(logo)
                            } else {
                                overlayTitle.isVisible = true
                            }
                        }
                    }
                }
                2 -> {
                    // Hidden: no title at all
                    gradient.isVisible = false
                    overlayTitle.isVisible = false
                    logo.isVisible = false
                }
                else -> {
                    // Below card (1 or any other): no gradient, title below
                    gradient.isVisible = false
                    overlayTitle.isVisible = false
                    logo.isVisible = false
                }
            }
        } else {
            // Portrait: bottom overlay is landscape-only — always title below unless hidden
            gradient.isVisible = false
            overlayTitle.isVisible = false
            logo.isVisible = false
        }

        val showTitleBelow = if (landscape) titlePosition == 1 else titlePosition != 2
        binding.tmdbCardTitle.isVisible = showTitleBelow
        binding.tmdbCardTitle.text = item.displayTitle
        binding.tmdbCardYear.isVisible = false
        if (landscape && titlePosition == 0) {
            logo.isVisible = true  // Will be updated by async logo fetch
        }

        // ── Rating pill (gated by CardMetadataTop) ──
        val rating = binding.tmdbCardRating
        val ratingText = binding.tmdbCardRatingText
        val broadcastIcon = binding.tmdbCardBroadcast
        val starIcon = binding.tmdbCardStar
        val vote = item.voteAverage
        val topPref = PrefManager.getVal<Int>(PrefName.CardMetadataTop)
        val showRating  = topPref and 1 != 0 && vote > 0.0
        val showAiring  = topPref and 2 != 0 && item.type == "tv" && item.firstAirDate?.isNotEmpty() == true
        rating.isVisible    = showRating || showAiring
        ratingText.isVisible = showRating
        ratingText.text     = String.format("%.1f", vote)
        broadcastIcon.isVisible = showAiring
        starIcon.isVisible       = showRating

        // ── Progress badge (gated by CardMetadataBottom; movies + TV) ──
        // Hidden when landscape has a bottom-overlay title (badges would collide).
        val progressBadge = binding.root.findViewById<View>(R.id.progressBadge)
        val wantProgress = PrefManager.getVal<Int>(PrefName.CardMetadataBottom) == 2 &&
            !(landscape && titlePosition == 0)
        if (!wantProgress) {
            progressBadge.isVisible = false
        } else {
            val isMovie = item.type == "movie"
            // Movies: watched|1 (e.g. ~|1 unwatched, 1|1 watched). TV: watched|released|TT.
            progressBadge.isVisible = true
            progressBadge.tag = "${item.type}:${item.id}"
            val watchedIcon = progressBadge.findViewById<android.view.View>(R.id.progressWatchedIcon)
            val watchedCount = progressBadge.findViewById<TextView>(R.id.progressWatchedCount)
            val releasedIcon = progressBadge.findViewById<android.view.View>(R.id.progressReleasedIcon)
            val releasedCount = progressBadge.findViewById<TextView>(R.id.progressReleasedCount)
            val midDivider = progressBadge.findViewById<android.view.View>(R.id.progressDividerMid)
            val ttDivider = progressBadge.findViewById<android.view.View>(R.id.progressDividerTT)
            val ttText = progressBadge.findViewById<TextView>(R.id.progressTT)

            if (isMovie) {
                // Movie: always 1 total, no broadcast icon, no TT.
                // Unwatched: ~ | 1   Watched: [eye] 1 | 1
                watchedIcon.isVisible = false
                watchedCount.text = "~"
                releasedIcon.isVisible = false
                releasedCount.isVisible = true
                releasedCount.text = "1"
                midDivider.isVisible = true
                ttDivider.isVisible = false
                ttText.isVisible = false
                detailScope.launch {
                    val watched = SimklWatchCache.watched("movie", item.id) ?: 0
                    val tag = progressBadge.tag
                    if (tag != "${item.type}:${item.id}") return@launch
                    binding.root.post {
                        if (progressBadge.tag != tag) return@post
                        if (watched > 0) {
                            watchedIcon.isVisible = true
                            watchedCount.text = "1"
                        }
                    }
                }
            } else {
                // TV: hidden until detail fills real values; never flash a ~ placeholder
                watchedIcon.isVisible = false
                watchedCount.text = "~"
                releasedIcon.isVisible = false
                releasedCount.visibility = android.view.View.INVISIBLE
                midDivider.isVisible = false
                ttDivider.isVisible = false
                ttText.isVisible = false
                progressBadge.tag = "${item.type}:${item.id}"
                detailScope.launch {
                    val detail = runCatching { Tmdb.detail(item.type, item.id) }.getOrNull()
                    val tag = progressBadge.tag
                    if (tag != "${item.type}:${item.id}") return@launch
                    val released = detail?.numberOfEpisodes
                    val nextAirDate = detail?.nextEpisodeToAir?.airDate
                    val simklWatched = SimklWatchCache.watched("tv", item.id)
                    binding.root.post {
                        if (progressBadge.tag != tag) return@post
                        val hasWatched = simklWatched != null && simklWatched > 0
                        watchedIcon.isVisible = hasWatched
                        if (hasWatched) watchedCount.text = simklWatched.toString()
                        val hasReleased = released != null && released > 0
                        releasedIcon.isVisible = hasReleased
                        releasedCount.isVisible = hasReleased
                        if (hasReleased) releasedCount.text = released.toString()
                        var hasTT = false
                        midDivider.isVisible = hasReleased || hasTT
                    }
                }
            }
        }
    }

    fun setCardGradient(view: View) {
        val intensity = PrefManager.getVal<Float>(PrefName.CardGradientIntensity)
        if (intensity <= 0f) {
            view.background = null
            return
        }
        val startColor = Color.argb(0, 0, 0, 0)
        val endColor = Color.argb((255 * intensity).toInt().coerceIn(0, 255), 0, 0, 0)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(endColor, startColor)
        )
        view.background = gradient
    }
}
