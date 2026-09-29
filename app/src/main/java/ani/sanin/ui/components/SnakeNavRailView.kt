package ani.sanin.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.util.GlassComponent
import ani.sanin.util.GlassEffectDrawable
import ani.sanin.util.GlassEffectManager
import ani.sanin.util.NavPillCustomizer

class SnakeNavRailView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedWidth = -1f
    private var cachedHeight = -1f
    private var cachedColor = 0

    private var glassDrawable: GlassEffectDrawable? = null

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> suggestedMinimumHeight
            else -> suggestedMinimumHeight
        }
        setMeasuredDimension(width, height)
    }

    var live = true
        set(value) {
            field = value
            invalidate()
        }

    private var _glassEnabled: Boolean = false
    fun setGlassEnabled(enabled: Boolean) {
        _glassEnabled = enabled && GlassEffectManager.isComponentEnabled(GlassComponent.NavPills)
        if (_glassEnabled) {
            if (glassDrawable == null) {
                glassDrawable = GlassEffectDrawable.applyToView(
                    view = this,
                    cornerRadiusDp = NavPillCustomizer.getCornerRadiusDp().toFloat(),
                    blurRadius = GlassEffectManager.getBlurRadius(),
                    tintColor = GlassEffectManager.getTintColor()
                ).also { d ->
                    GlassEffectManager.applyParams(d)
                    post { wireScrollInvalidator(d) }
                }
            }
            setWillNotDraw(false)
        } else {
            glassDrawable?.destroy()
            glassDrawable = null
            background = null
        }
        invalidate()
    }

    private fun wireScrollInvalidator(drawable: GlassEffectDrawable) {
        val rv = rootView
        if (rv is ViewGroup) {
            fun find(vg: ViewGroup): View? {
                for (i in 0 until vg.childCount) {
                    val c = vg.getChildAt(i)
                    if (c is RecyclerView || c is NestedScrollView ||
                        c is ScrollView || c is android.widget.ListView
                    ) return c
                    if (c is ViewGroup) find(c)?.let { return it }
                }
                return null
            }
            find(rv)?.let { drawable.invalidateOnScroll(it) }
        }
    }

    fun updateGlassParams() {
        if (!_glassEnabled) return
        glassDrawable?.let { d ->
            d.setTintColor(GlassEffectManager.getTintColor())
            d.setCornerRadius(NavPillCustomizer.getCornerRadiusDp() * resources.displayMetrics.density)
            GlassEffectManager.applyParams(d)
            d.invalidateCache()
            d.invalidateSelf()
        }
    }

    fun getColorAtFraction(fraction: Float): Int =
        if (NavPillCustomizer.isDarkTheme()) Color.BLACK else Color.WHITE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (_glassEnabled) return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        drawSolid(canvas, w, h)
    }

    private fun drawSolid(canvas: Canvas, w: Float, h: Float) {
        // Kept in sync with NavPillCustomizer so the pill body and the icon tint can
        // never disagree about light vs dark.
        val color = if (NavPillCustomizer.isDarkTheme()) Color.BLACK else Color.WHITE
        if (w != cachedWidth || h != cachedHeight || color != cachedColor) {
            cachedWidth = w
            cachedHeight = h
            cachedColor = color
            bgPaint.shader = null
            bgPaint.color = color
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
    }

    fun invalidateGlass() {
        glassDrawable?.invalidateCache()
        invalidate()
    }

}
