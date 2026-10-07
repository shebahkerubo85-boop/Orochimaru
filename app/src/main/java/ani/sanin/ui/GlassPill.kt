package ani.sanin.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import ani.sanin.getThemeColor

/**
 * The Aurora capsule from the library status tabs, rebuilt as a plain drawable so the
 * calendar week strip and the franchise filter row can share the same look as the
 * Compose pill.
 *
 * States:
 *  - selected: accent-tinted fill with an accent rim.
 *  - focused (unselected): no fill, just the accent rim - the dpad ring.
 *  - idle: a faint container (white wash in dark, a hairline rim in light).
 *
 * Light mode adds the same small elevation shadow the lens buttons and pills use.
 */
object GlassPill {

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)

    /**
     * A capsule in one of the three states.
     *
     * @param cornerRadiusPx half the pill's height for a true capsule; callers that know
     *   their height can pass it, otherwise a large value is used.
     */
    fun make(
        context: Context,
        selected: Boolean,
        focused: Boolean,
        cornerRadiusPx: Float? = null,
    ): Drawable {
        val dark = isNight(context)
        val accent = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = cornerRadiusPx ?: radiusPx(context)

        val rimColor = when {
            focused -> accent
            selected -> ColorUtils.setAlphaComponent(accent, if (isNight(context)) 140 else 230)
            isNight(context) -> 0x38FFFFFF
            else -> ColorUtils.setAlphaComponent(accent, 0x47)
        }

        val fill: GradientDrawable = when {
            selected -> GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    if (isNight(context)) {
                        ColorUtils.setAlphaComponent(
                            ColorUtils.blendARGB(accent, Color.WHITE, 0.18f), 0x52
                        )
                    } else {
                        ColorUtils.setAlphaComponent(accent, 0x33)
                    },
                    if (isNight(context)) {
                        ColorUtils.setAlphaComponent(accent, 0x2E)
                    } else {
                        ColorUtils.setAlphaComponent(Color.WHITE, 0x66)
                    },
                ),
            ).also { it.cornerRadius = radius }

            focused -> GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
            }

            isNight(context) -> GradientDrawable().apply {
                setColor(0x0DFFFFFF)
                cornerRadius = radius
            }

            else -> GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
            }
        }

        val border = GradientDrawable().apply {
            setColor(
                when {
                    focused -> accent
                    selected -> ColorUtils.setAlphaComponent(accent, if (isNight(context)) 0x8C else 0xE6)
                    isNight(context) -> 0x38FFFFFF
                    else -> ColorUtils.setAlphaComponent(accent, 0x47)
                }
            )
            cornerRadius = radius
        }

        val inset = (context.resources.displayMetrics.density * 1f).toInt().coerceAtLeast(1)
        val layer = LayerDrawable(arrayOf(border, fill))
        layer.setLayerInset(1, inset, inset, inset, inset)
        return layer
    }

    /** Applies the pill background for the given state, with the light-mode shadow. */
    fun apply(view: View, selected: Boolean, focused: Boolean, cornerRadiusPx: Float = radiusPx(view.context)) {
        view.background = make(view.context, selected, focused, cornerRadiusPx)
        if (!isNight(view.context)) {
            view.elevation = 3f * view.resources.displayMetrics.density
        } else {
            view.elevation = 0f
        }
    }

    /** Default capsule radius: large enough that Android clamps it to a rounded end. */
    fun radiusPx(context: Context): Float =
        (context.resources.displayMetrics.density * 100f)
}