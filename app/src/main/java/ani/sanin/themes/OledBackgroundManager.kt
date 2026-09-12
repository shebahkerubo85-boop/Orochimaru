package ani.sanin.themes

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

object OledBackgroundManager {

    private var appliedActivity: Activity? = null
    private var originalBackground: Drawable? = null
    private val clearedBackgrounds = linkedMapOf<View, Drawable?>()
    private var installed = false
    private var pageListenerAttached = false

    fun apply(
        activity: Activity,
        oledMode: Int,
        primaryColor: Int,
        gradientDir: Int = 0,
        intensity: Float = 1f
    ) {
        val drawable = when (oledMode) {
            1 -> SolidBlackDrawable()
            2 -> GlowSpotsDrawable(primaryColor, intensity)
            3 -> GradientBgDrawable(primaryColor, gradientDir, intensity)
            4 -> VignetteBgDrawable(primaryColor, intensity)
            else -> return
        }

        if (appliedActivity != activity) {
            clearedBackgrounds.clear()
            originalBackground = null
            pageListenerAttached = false
        }

        originalBackground = activity.window.decorView.background
        activity.window.setBackgroundDrawable(drawable)
        appliedActivity = activity
        installed = true

        if (!pageListenerAttached) {
            pageListenerAttached = true
            activity.window.decorView.post {
                if (!installed || appliedActivity != activity) return@post
                clearPageBackground(activity)

                // Keep clearing page roots that get attached later (fragments, tabs,
                // on-demand screens) — no-op when the walk finds nothing new.
                val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@post
                content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (!installed || appliedActivity != activity) {
                            content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            return
                        }
                        clearPageBackground(activity)
                    }
                })
            }
        }
    }

    fun remove(activity: Activity) {
        if (appliedActivity == activity) {
            installed = false
            pageListenerAttached = false
            activity.window.decorView.post {
                restorePageBackgrounds()
                activity.window.setBackgroundDrawable(originalBackground)
                originalBackground = null
                appliedActivity = null
            }
        }
    }

    /**
     * Removes the opaque "page" backgrounds (the theme colorBackground/colorSurface
     * fills on full-bleed containers) so the OLED window drawable shows through in
     * the gaps — cards, banners and drawable surfaces are left untouched.
     */
    private fun clearPageBackground(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? ViewGroup ?: return
        val pageColors = resolvePageColors(activity)
        if (pageColors.isEmpty()) return
        if (root.background is ColorDrawable && matchesPageColor(root.background as ColorDrawable, pageColors)) {
            rememberBackground(root)
            root.background = null
        }
        clearPageBelow(root, pageColors, 0)
    }

    private fun clearPageBelow(view: ViewGroup, pageColors: Set<Int>, depth: Int) {
        if (depth > 8) return
        val parentW = view.width
        val parentH = view.height
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child !is ViewGroup) continue
            val bg = child.background
            if (bg is ColorDrawable && matchesPageColor(bg, pageColors)) {
                // Only full-bleed containers are page backgrounds — card-sized
                // surfaces (rows, sections) keep their own background.
                if (parentW > 0 && child.width < parentW - 4) continue
                if (parentH > 0 && child.height < parentH - 4) continue
                rememberBackground(child)
                child.background = null
                clearPageBelow(child, pageColors, depth + 1)
                continue
            }
            if (bg == null) {
                clearPageBelow(child, pageColors, depth + 1)
            }
        }
    }

    private fun rememberBackground(view: View) {
        if (view !in clearedBackgrounds) {
            clearedBackgrounds[view] = view.background
        }
    }

    private fun restorePageBackgrounds() {
        for ((view, bg) in clearedBackgrounds) {
            view.background = bg
        }
        clearedBackgrounds.clear()
    }

    private fun matchesPageColor(bg: ColorDrawable, pageColors: Set<Int>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return bg.color in pageColors
    }

    private fun resolvePageColors(activity: Activity): Set<Int> {
        val colors = mutableSetOf<Int>()
        val tv = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                colors.add(tv.data)
            }
        }
        if (activity.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                colors.add(tv.data)
            }
        }
        return colors
    }

    /** Stub for mode 1 (Pure AMOLED) — just pure black, no overlay needed. */
    private class SolidBlackDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class GlowSpotsDrawable(
        private val primaryColor: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private var cachedIntensity: Float = -1f
        private val spots = listOf(
            floatArrayOf(0.50f, 0.10f, 0.45f),
            floatArrayOf(0.90f, 0.18f, 0.36f),
            floatArrayOf(0.10f, 0.82f, 0.38f),
        )
        private val cachedShaders = arrayOfNulls<Shader>(3)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b || cachedIntensity != intensity) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                for (i in spots.indices) {
                    val spot = spots[i]
                    cachedShaders[i] = RadialGradient(
                        spot[0] * w, spot[1] * h, spot[2] * minOf(w, h),
                        intArrayOf(
                            scaleAlpha(Color.argb(90, r, g, bl)),
                            scaleAlpha(Color.argb(40, r, g, bl)),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.40f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
            for (i in spots.indices) {
                paint.shader = cachedShaders[i]
                canvas.drawCircle(spots[i][0] * w, spots[i][1] * h, spots[i][2] * minOf(w, h), paint)
            }
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class GradientBgDrawable(
        private val primaryColor: Int,
        private val direction: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private var cachedIntensity: Float = -1f
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b || cachedIntensity != intensity) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                val x0: Float; val y0: Float; val x1: Float; val y1: Float
                when (direction) {
                    1 -> { x0 = w / 2f; y0 = h; x1 = w / 2f; y1 = h * 0.30f }
                    2 -> { x0 = 0f; y0 = h / 2f; x1 = w * 0.70f; y1 = h / 2f }
                    3 -> { x0 = w; y0 = h / 2f; x1 = w * 0.30f; y1 = h / 2f }
                    else -> { x0 = w / 2f; y0 = 0f; x1 = w / 2f; y1 = h * 0.70f }
                }
                cachedShader = LinearGradient(
                    x0, y0, x1, y1,
                    intArrayOf(
                        scaleAlpha(Color.argb(80, r, g, bl)),
                        scaleAlpha(Color.argb(35, r, g, bl)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.40f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class VignetteBgDrawable(
        private val primaryColor: Int,
        private val intensity: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cachedBounds: android.graphics.Rect? = null
        private var cachedIntensity: Float = -1f
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawColor(Color.BLACK)
            if (cachedBounds != b || cachedIntensity != intensity) {
                cachedBounds = android.graphics.Rect(b)
                val r = Color.red(primaryColor)
                val g = Color.green(primaryColor)
                val bl = Color.blue(primaryColor)
                val cx = w / 2f
                val cy = h / 2f
                val radius = maxOf(w, h) * 0.80f
                cachedShader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        scaleAlpha(Color.argb(45, r, g, bl)),
                        scaleAlpha(Color.argb(110, 0, 0, 0))
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
