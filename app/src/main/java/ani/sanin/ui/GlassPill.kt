package ani.sanin.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
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

    /** True when the current theme is the dark palette (the app's Light/Dark setting drives
     *  AppCompatDelegate night mode, and the theme colours come from values-night). */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Int =
        (context.resources.displayMetrics.density * value).toInt().coerceAtLeast(1)

    /**
     * The owning Activity's context. The status pill can sit under an AppBar theme overlay
     * (fragment_library.xml applies Theme.Sanin.AppBarOverlay to the toolbar), and that overlay
     * does not carry the accent applied to the Activity at runtime, so colour attrs resolved
     * from the view context fall back to the Material default (purple). Resolve from the
     * Activity's own theme instead. No-op when the context already is the Activity.
     */
    fun themedContext(context: Context): Context {
        var ctx: Context = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context
    }

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
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val radius = radiusPx(context)

        // Every theme gets the same top-lit capsule: a gradient fill with a rim light stroked
        // along the outline, brightest along the top edge and fading down the sides.
        val fill: IntArray
        val rimPeak: Int
        if (dark) {
            fill = intArrayOf(0x14FFFFFF, 0x0AFFFFFF, 0x05FFFFFF)
            // Rim light: white at its brightest along the top, fading down the sides.
            rimPeak = ColorUtils.setAlphaComponent(Color.WHITE, 0x61)
        } else {
            fill = intArrayOf(
                ColorUtils.blendARGB(Color.WHITE, accent, 0.10f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.05f),
                ColorUtils.blendARGB(Color.WHITE, accent, 0.02f),
            )
            // Light-mode rim light: black at its brightest along the top, fading down the sides.
            rimPeak = ColorUtils.setAlphaComponent(Color.BLACK, 0x61)
        }

        view.background = capsule(fill, rimPeak, dp(context, 3f), radius)
        view.clipToOutline = true
        view.elevation = if (dark) 0f else 6f * view.resources.displayMetrics.density
    }

    /**
     * A capsule fill with a rim light stroked along its outline: [rimPeak] at the top edge
     * fading to transparent toward the bottom. Because it is stroked (not a top band) the light
     * follows the pill's curve onto the sides, and it is [thickness] px thick everywhere.
     */
    private fun capsule(
        fill: IntArray,
        rimPeak: Int,
        thickness: Int,
        radius: Float,
    ): Drawable {
        val fillLayer = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(fill)
            cornerRadius = radius
        }
        val rimLayer = TopLitRimDrawable(rimPeak, thickness.toFloat(), radius)
        return LayerDrawable(arrayOf<Drawable>(fillLayer, rimLayer))
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
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
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
        val accent = themedContext(context)
            .getThemeColor(com.google.android.material.R.attr.colorPrimary)
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

/**
 * Strokes a rounded-rect (capsule) outline with a vertical gradient: [peak] along the top edge
 * fading to transparent toward the bottom. Because it strokes the path it follows the curve onto
 * the sides, [thicknessPx] px thick all the way round. Used for the pill's top-lit rim.
 */
private class TopLitRimDrawable(
    private val peak: Int,
    private val thicknessPx: Float,
    private val radius: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thicknessPx
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = thicknessPx / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        paint.shader = LinearGradient(
            0f,
            b.top.toFloat(),
            0f,
            b.bottom.toFloat(),
            peak,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) {
            outline.setEmpty()
        } else {
            outline.setRoundRect(bounds, radius)
        }
    }
}