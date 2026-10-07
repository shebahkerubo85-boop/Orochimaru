package ani.sanin.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * Tadami-style "lens" button background: a translucent glass fill inside a thin,
 * top-lit white gradient rim. Ported from AuroraHeaderIconStyle.auroraHeaderIconSurface.
 *
 * There is no real blur/glass in the source either - it is just a translucent fill
 * plus a 1dp vertical white gradient rim. This reproduces the same look as a plain
 * (non-Compose) drawable so XML ImageButtons can share it.
 */
object LensButtonBackground {

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun make(context: Context, fillAlpha: Float? = null): Drawable {
        val dark = isNight(context)
        val alpha = fillAlpha ?: if (dark) 0.70f else 0.68f

        val rim = intArrayOf(
            if (dark) 0x47FFFFFF else 0xE6FFFFFF.toInt(),
            if (dark) 0x1AFFFFFF else 0x66FFFFFF,
            if (dark) 0x0AFFFFFF else 0x2EFFFFFF,
        )
        val fillColor = (Math.round(alpha * 255f) shl 24) or if (dark) 0x161922 else 0xFFFFFF

        val rimDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = rim
        }
        val fillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }

        val inset = (context.resources.displayMetrics.density * 1f).toInt().coerceAtLeast(1)
        val layer = LayerDrawable(arrayOf(rimDrawable, fillDrawable))
        layer.setLayerInset(1, inset, inset, inset, inset)

        val rippleColor = ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x1F000000)
        return RippleDrawable(rippleColor, layer, null)
    }

    fun apply(view: View, fillAlpha: Float? = null) {
        view.background = make(view.context, fillAlpha)
    }

    /** Translucent fill color (no rim) used for the reduced-background calendar button. */
    fun fillColor(context: Context, alpha: Float): Int {
        val dark = isNight(context)
        return (Math.round(alpha * 255f) shl 24) or if (dark) 0x161922 else 0xFFFFFF
    }

    fun rimColor(context: Context, top: Boolean = true): Int {
        val dark = isNight(context)
        return if (dark) {
            if (top) 0x47FFFFFF else 0x0AFFFFFF
        } else {
            if (top) 0xE6FFFFFF.toInt() else 0x2EFFFFFF
        }
    }

    val RIPPLE_NEUTRAL = Color.TRANSPARENT
}