package ani.sanin.cloudstream

import android.graphics.Color
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

object TmdbCards {

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
     * uncropped, with the title over a bottom gradient at the bottom-left.
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
        val overlayTitle = binding.tmdbCardOverlayTitle
        gradient.isVisible = landscape
        overlayTitle.isVisible = landscape
        overlayTitle.text = item.displayTitle
        if (landscape) {
            gradient.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            overlayTitle.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
            }
            setGradient(gradient)
        }

        binding.tmdbCardTitle.isVisible = !landscape
        binding.tmdbCardYear.isVisible = !landscape
    }

    private fun setGradient(view: View) {
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
