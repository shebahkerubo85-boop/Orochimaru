package ani.sanin.themes

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.ViewGroup
import androidx.core.view.doOnLayout

object OledBackgroundManager {

    fun apply(activity: Activity, oledMode: Int, primaryColor: Int, gradientDir: Int = 0, intensity: Float = 1f) {
        activity.window.decorView.doOnLayout { decor ->
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return@doOnLayout

            val blackBg = DarkBgDrawable().apply { setBounds(0, 0, w, h) }

            val effectDrawable: Drawable? = when (oledMode) {
                2 -> GlowSpotsDrawable(primaryColor, intensity).apply { setBounds(0, 0, w, h) }
                3 -> GradientBgDrawable(primaryColor, gradientDir, intensity).apply { setBounds(0, 0, w, h) }
                4 -> VignetteBgDrawable(primaryColor, intensity).apply { setBounds(0, 0, w, h) }
                else -> null
            }

            if (effectDrawable != null) {
                val layerBg = LayerDrawable(arrayOf(blackBg, effectDrawable))
                decor.background = layerBg
            } else {
                decor.background = blackBg
            }
        }
    }

    fun remove(activity: Activity) {
        activity.window.decorView.background = DarkBgDrawable()
    }

    private class DarkBgDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(android.graphics.Color.BLACK)
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
        private val cachedShaders = arrayOfNulls<Shader>(3)

        private val spots = listOf(
            floatArrayOf(0.50f, 0.10f, 0.45f),
            floatArrayOf(0.90f, 0.18f, 0.36f),
            floatArrayOf(0.10f, 0.82f, 0.38f),
        )

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = android.graphics.Color.red(primaryColor)
                val g = android.graphics.Color.green(primaryColor)
                val bl = android.graphics.Color.blue(primaryColor)
                for (i in spots.indices) {
                    val spot = spots[i]
                    val cx = spot[0] * w
                    val cy = spot[1] * h
                    val radius = spot[2] * minOf(w, h)
                    cachedShaders[i] = RadialGradient(
                        cx, cy, radius,
                        intArrayOf(
                            scaleAlpha(android.graphics.Color.argb(90, r, g, bl)),
                            scaleAlpha(android.graphics.Color.argb(40, r, g, bl)),
                            android.graphics.Color.TRANSPARENT
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
            val a = (android.graphics.Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return android.graphics.Color.argb(a, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
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
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = android.graphics.Color.red(primaryColor)
                val g = android.graphics.Color.green(primaryColor)
                val bl = android.graphics.Color.blue(primaryColor)

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
                        scaleAlpha(android.graphics.Color.argb(80, r, g, bl)),
                        scaleAlpha(android.graphics.Color.argb(35, r, g, bl)),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.40f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (android.graphics.Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return android.graphics.Color.argb(a, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
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
        private var cachedShader: Shader? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return
            if (cachedBounds != b) {
                cachedBounds = android.graphics.Rect(b)
                val r = android.graphics.Color.red(primaryColor)
                val g = android.graphics.Color.green(primaryColor)
                val bl = android.graphics.Color.blue(primaryColor)
                val cx = w / 2f
                val cy = h / 2f
                val radius = maxOf(w, h) * 0.80f
                cachedShader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        android.graphics.Color.TRANSPARENT,
                        scaleAlpha(android.graphics.Color.argb(45, r, g, bl)),
                        scaleAlpha(android.graphics.Color.argb(110, 0, 0, 0))
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun scaleAlpha(color: Int): Int {
            val a = (android.graphics.Color.alpha(color) * intensity).toInt().coerceIn(0, 255)
            return android.graphics.Color.argb(a, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
