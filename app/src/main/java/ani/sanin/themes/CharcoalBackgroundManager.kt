package ani.sanin.themes

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout

/**
 * Applies the Charcoal neumorphic background: a dark charcoal surface
 * with a very soft, diffuse top-left highlight that gives the entire
 * background the feel of a molded dark surface.
 */
object CharcoalBackgroundManager {

    private var overlayView: View? = null

    private const val BG_COLOR = "#212121"
    private const val HIGHLIGHT_COLOR = "#2A2A2A"
    private const val SHADOW_COLOR = "#151515"

    fun apply(activity: Activity) {
        activity.window.decorView.doOnLayout { decor ->
            // Base charcoal background
            decor.setBackgroundDrawable(CharcoalBgDrawable().apply {
                setBounds(0, 0, decor.width, decor.height)
            })

            val decorGroup = decor as? ViewGroup ?: return@doOnLayout

            // Remove previous overlay if any
            if (overlayView != null && overlayView?.parent == decorGroup) {
                decorGroup.removeView(overlayView)
                overlayView = null
            }

            // Subtle neumorphic light overlay: very soft top-left glow
            val overlay = CharcoalHighlightDrawable()
            val overlayViewNew = object : View(activity) {
                override fun onDraw(canvas: Canvas) {
                    overlay.setBounds(0, 0, width, height)
                    overlay.draw(canvas)
                }
            }
            overlayViewNew.setWillNotDraw(false)
            overlayViewNew.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START
            )
            overlayViewNew.isClickable = false
            overlayViewNew.isFocusable = false
            overlayViewNew.isFocusableInTouchMode = false
            overlayViewNew.alpha = 0.5f
            overlayViewNew.id = View.generateViewId()
            decorGroup.addView(overlayViewNew)
            overlayView = overlayViewNew
        }
    }

    fun remove(activity: Activity) {
        if (overlayView != null && overlayView?.parent == activity.window.decorView) {
            (activity.window.decorView as? ViewGroup)?.removeView(overlayView)
            overlayView = null
        }
    }

    /** Solid charcoal background */
    private class CharcoalBgDrawable : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.parseColor(BG_COLOR))
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /**
     * Neumorphic highlight: a very soft, wide, diffuse gradient
     * from the top-left corner that simulates ambient light
     * hitting a raised dark surface.
     */
    private class CharcoalHighlightDrawable : Drawable() {
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
                cachedShader = LinearGradient(
                    0f, 0f,
                    w * 0.6f, h * 0.4f,
                    intArrayOf(
                        Color.parseColor(HIGHLIGHT_COLOR),
                        Color.parseColor("#232323"),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.3f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            paint.shader = cachedShader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
