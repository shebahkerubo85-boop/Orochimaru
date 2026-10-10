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
 * The Aurora capsule from the library status pill, rebuilt as plain drawables so the calendar
 * week strip shares the same look as the Compose pill without being a Compose host.
 *
 * Two pieces:
 *  - [applyContainer]: the capsule itself - the light-mode drop shadow, the white-to-accent
 *    wash and border in light, the faint white wash and top-lit border in dark.
 *  - [applyCell]: one day. Selected paints the accent-brush oval (the indicator); a focused but
 *    unselected day paints only the oval rim; an idle day paints nothing at all.
 */
object GlassPill {

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)

    /** Capsule radius: large enough that Android clamps it to a rounded end. */
    fun radiusPx(context: Context): Float =
        context.resources.displayMetrics.density * 100f

    /**
     * The library-pill capsule container: fill + 1dp border, and the same light-mode shadow the
     * lens buttons use. Callers set the inner padding so the oval indicator has its margin.
     */
    fun applyContainer(view: View) {
        val context = view.context
        val dark = isNight(context)
        val accent = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)

        // Every theme gets the same top-lit capsule: a vertical gradient fill under a
        // vertical gradient rim (rim drawn first, fill inset by the stroke so only the
        // border shows). Matches the Compose status pill, which previously only had the
        // gradient in light mode.
        val fill: IntArray
        val rim: IntArray
        if (dark) {
            fill = intArrayOf(0x14FFFFFF, 0x0AFFFFFF, 0x05FFFFFF)
            rim = intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 0x5C),
                ColorUtils.setAlphaComponent(Color.WHITE, 0x24),
                ColorUtils.setAlphaComponent(Color.WHITE, 0x05),
            )
        } else {
            fill = intArrayOf(
                ColorUtils.blendARGB(Color.WHITE, accent, 0.10f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.05f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.02f),
            )
            rim = intArrayOf(
                ColorUtils.setAlphaComponent(accent, 0x52),
                ColorUtils.setAlphaComponent(accent, 0x2E),
                ColorUtils.setAlphaComponent(accent, 0x14),
            )
        }

        view.background = capsule(fill, rim, dp(context, 1f), radius)
        view.clipToOutline = true
        view.elevation = if (dark) 0f else 6f * view.resources.displayMetrics.density
    }

    /** Vertical gradient fill inside a vertical gradient rim, both clamped to a capsule. */
    private fun capsule(
        fill: IntArray,
        rim: IntArray,
        stroke: Int,
        radius: Float,
    ): Drawable {
        val rimLayer = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(rim)
            cornerRadius = radius
        }
        val fillLayer = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(fill)
            cornerRadius = radius
        }
        val layer = LayerDrawable(arrayOf<Drawable>(rimLayer, fillLayer))
        layer.setLayerInset(1, stroke, stroke, stroke, stroke)
        return layer
    }

    /**
     * One day cell.
     *
     * @param selected draws the accent-brush oval indicator, inset from the cell edge so the
     *   oval keeps a margin inside the capsule.
     * @param focused draws the same oval as a rim only, so the dpad ring matches the indicator.
     */
    fun applyCell(view: View, selected: Boolean, focused: Boolean) {
        val context = view.context
        val dark = isNight(context)
        val accent = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)
        val gap = dp(context, 2f)

        view.background = when {
            selected -> {
                val fill = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if (dark) {
                        intArrayOf(
                            ColorUtils.setAlphaComponent(
                                ColorUtils.blendARGB(accent, Color.WHITE, 0.18f), 0x52
                            ),
                            ColorUtils.setAlphaComponent(accent, 0x2E),
                        )
                    } else {
                        intArrayOf(
                            ColorUtils.setAlphaComponent(accent, 0x33),
                            ColorUtils.setAlphaComponent(Color.WHITE, 0x66),
                        )
                    },
                ).apply { cornerRadius = radius }
                val border = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                    setStroke(
                        dp(context, 1f),
                        if (dark) {
                            ColorUtils.setAlphaComponent(accent, 0x40)
                        } else {
                            ColorUtils.setAlphaComponent(accent, 0x47)
                        },
                    )
                }
                inset(arrayOf(fill, border), gap)
            }

            focused -> {
                val rim = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                    setStroke(dp(context, 2f), accent)
                }
                inset(arrayOf(rim), gap)
            }

            else -> null
        }
        view.elevation = 0f
    }

    /**
     * The selection indicator on its own, for a strip that draws one travelling capsule behind
     * its cells instead of a fill per cell. Same accent gradient + border as [applyCell]'s
     * selected state; the caller supplies the gap by insetting the drawn bounds.
     */
    fun indicatorDrawable(context: Context): Drawable {
        val dark = isNight(context)
        val accent = context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)

        val fillLayer = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) {
                intArrayOf(
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(accent, Color.WHITE, 0.18f), 0x52
                    ),
                    ColorUtils.setAlphaComponent(accent, 0x2E),
                )
            } else {
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, 0x33),
                    ColorUtils.setAlphaComponent(Color.WHITE, 0x66),
                )
            },
        ).apply { cornerRadius = radius }

        val rimLayer = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(
                dp(context, 1f),
                if (dark) {
                    ColorUtils.setAlphaComponent(accent, 0x40)
                } else {
                    ColorUtils.setAlphaComponent(accent, 0x47)
                },
            )
        }

        return LayerDrawable(arrayOf<Drawable>(fillLayer, rimLayer))
    }

    /** Every layer inset equally, so the oval floats inside its day slot instead of touching it. */
    private fun inset(layers: Array<GradientDrawable>, inset: Int): Drawable {
        val layer = LayerDrawable(layers as Array<Drawable>)
        for (i in layers.indices) layer.setLayerInset(i, inset, inset, inset, inset)
        return layer
    }
}