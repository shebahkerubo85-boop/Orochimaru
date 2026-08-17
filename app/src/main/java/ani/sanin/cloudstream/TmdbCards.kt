package ani.sanin.cloudstream

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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

    private val logoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isLandscapeOrientation(): Boolean = PrefManager.getVal<Int>(PrefName.CardOrientation) == 0

    fun cardSize(): Float = PrefManager.getVal(PrefName.CardSize)

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
    fun applyCardStyle(binding: ItemTmdbCardBinding, item: TmdbMedia) {
        val landscape = isLandscapeOrientation()
        val size = cardSize()
        val (w, h) = if (landscape) {
            (260f * size).toInt() to (148f * size).toInt()
        } else {
            (102f * size).toInt() to (154f * size).toInt()
        }
        binding.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
            width = w
            height = h
        }
        binding.tmdbCard.radius = roundness()

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
                                    .override((w * 0.7f).toInt())
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
            // Portrait: always title below, no gradient
            gradient.isVisible = false
            overlayTitle.isVisible = false
            logo.isVisible = false
        }

        val showTitleBelow = !landscape || titlePosition != 0 && titlePosition != 2
        binding.tmdbCardTitle.isVisible = showTitleBelow
        binding.tmdbCardTitle.text = item.displayTitle
        binding.tmdbCardYear.isVisible = false
        if (landscape && titlePosition == 0) {
            logo.isVisible = true  // Will be updated by async logo fetch
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
