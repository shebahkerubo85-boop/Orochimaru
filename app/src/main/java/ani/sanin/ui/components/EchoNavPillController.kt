package ani.sanin.ui.components

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ani.sanin.R
import ani.sanin.util.NavPillCustomizer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

private const val INDICATOR_WIDTH = "indicator_width"
private const val INDICATOR_HEIGHT = "indicator_height"

class EchoNavPillController(
    private val container: FrameLayout,
    private val backgroundPill: View?,
    private val row: LinearLayout?,
    private val pillList: LinearLayout?,
    private val pills: List<ImageButton>,
    private val labels: List<String>,
    private val onSearch: () -> Unit
) {
    private val density = container.resources.displayMetrics.density
    private val thresholdPx = 52f * density
    private val searchGapPx = (8f * density).roundToInt()
    private val tightPaddingPx = (3f * density).roundToInt()
    private val labelGapPx = (5f * density).roundToInt()
    private val pillRadiusPx = NavPillCustomizer.getCornerRadiusDp() * density
    private val indicatorColor = 0x40FFFFFF.toInt()
    private val surfaceColor = 0x30FFFFFF.toInt()

    private var selectedIndex = 0
    private var collapsed = false
    private var accumulated = 0f
    private var lastDirection = 0f
    private var savedPillPadding: IntArray? = null

    val isCollapsed: Boolean get() = collapsed
    val isScrollCapable: Boolean get() = row != null && pillList != null

    private val springInterpolator = object : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            return 1f - exp(-t * 5f) * cos(t * 9f)
        }
    }

    private val indicator: View by lazy {
        View(container.context).apply {
            background = GradientDrawable().apply {
                cornerRadius = pillRadiusPx
                setColor(indicatorColor)
            }
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private val labelView: TextView? by lazy {
        if (pillList == null || pillList.orientation != LinearLayout.HORIZONTAL) return@lazy null
        TextView(container.context).apply {
            setTextColor(NavPillCustomizer.getIconColor())
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12f, container.resources.displayMetrics
            )
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                container.context, R.font.poppins_semi_bold
            )
            includeFontPadding = false
            isSingleLine = true
            visibility = View.VISIBLE
        }
    }

    private val searchButton: ImageButton? by lazy {
        if (row == null) return@lazy null
        ImageButton(container.context).apply {
            setImageResource(R.drawable.ic_round_search_24)
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = container.context.getString(R.string.search)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { onSearch() }
        }
    }

    fun attach(initialIndex: Int) {
        selectedIndex = initialIndex
        if (indicator.parent == null) {
            container.addView(indicator, 1)
        }
        labelView?.let { lbl ->
            if (lbl.parent == null) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = labelGapPx
                pillList?.addView(lbl, min(selectedIndex + 1, pillList.childCount), lp)
            }
        }
        searchButton?.let { sb ->
            if (sb.parent == null) {
                val pillSize = (NavPillCustomizer.getHeightDp() * density).roundToInt()
                val lp = LinearLayout.LayoutParams(pillSize, pillSize)
                lp.leftMargin = searchGapPx
                row?.addView(sb, lp)
                sb.updateSurface(surfaceColor)
            }
        }
        container.post {
            positionIndicator(selectedIndex, animate = false)
        }
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in pills.indices) return
        val oldIndex = selectedIndex
        selectedIndex = index
        if (collapsed) {
            if (index != oldIndex) refreshCollapsedSelection(oldIndex, index)
            return
        }
        positionLabel()
        if (index == oldIndex) {
            positionIndicator(index, animate)
        } else {
            container.post { positionIndicator(index, animate) }
        }
    }

    fun setCollapsed(collapsed: Boolean) {
        if (pillList == null || row == null) return
        if (this.collapsed == collapsed) return
        if (collapsed) shrink() else expand()
    }

    fun onScroll(dyPixels: Float) {
        if (!isScrollCapable) return
        if (dyPixels == 0f) return
        val dir = if (dyPixels > 0f) 1f else -1f
        if (dir != lastDirection) {
            accumulated = 0f
            lastDirection = dir
        }
        accumulated += dyPixels
        if (!collapsed && accumulated >= thresholdPx) {
            accumulated = 0f
            setCollapsed(true)
        } else if (collapsed && accumulated <= -thresholdPx) {
            accumulated = 0f
            setCollapsed(false)
        }
    }

    private fun shrink() {
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        collapsed = true
        labelView?.let { fadeOut(it) }
        fadeAlpha(indicator, 0f)
        backgroundPill?.let { fadeAlpha(it, 0f) }
        others.forEach { fadeOut(it) }

        searchButton?.let { sb ->
            sb.visibility = View.VISIBLE
            sb.alpha = 0f
            sb.scaleX = 0.3f
            sb.scaleY = 0.3f
        }

        selected.updateSurface(surfaceColor)
        savedPillPadding = intArrayOf(pl.paddingLeft, pl.paddingTop, pl.paddingRight, pl.paddingBottom)
        if (pl.orientation == LinearLayout.HORIZONTAL) {
            pl.setPadding(tightPaddingPx, pl.paddingTop, tightPaddingPx, pl.paddingBottom)
        }

        container.postDelayed({
            labelView?.visibility = View.GONE
            others.forEach { it.visibility = View.GONE }
            pl.requestLayout()
            pl.post {
                val targetWidth = container.measuredWidth
                animateContainerWidth(startWidth, targetWidth) {
                    searchButton?.animate()
                        ?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
                }
            }
        }, 170)
    }

    private fun expand() {
        if (savedPillPadding == null) return
        val pl = pillList ?: return
        val selected = pills.getOrNull(selectedIndex) ?: pills.firstOrNull() ?: return
        val others = pills.filter { it !== selected }
        val startWidth = container.width

        collapsed = false
        others.forEach { it.visibility = View.VISIBLE }
        others.forEach { it.alpha = 0f }
        others.forEach { it.scaleX = 0.3f }
        others.forEach { it.scaleY = 0.3f }
        labelView?.let { it.visibility = View.VISIBLE }
        labelView?.let { it.alpha = 0f }
        indicator.alpha = 0f
        backgroundPill?.let { it.alpha = 0f }
        savedPillPadding?.let { p ->
            pl.setPadding(p[0], p[1], p[2], p[3])
        }
        savedPillPadding = null
        selected.setBackgroundResource(android.R.color.transparent)

        container.post {
            positionLabel()
            pl.requestLayout()
            pl.post {
                val targetWidth = container.measuredWidth
                animateContainerWidth(startWidth, targetWidth) {
                    others.forEach { it.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start() }
                    backgroundPill?.animate()?.alpha(1f)?.setDuration(200)?.start()
                    positionIndicator(selectedIndex, animate = false)
                    indicator.alpha = 1f
                    labelView?.animate()?.alpha(1f)?.setDuration(200)?.start()
                    searchButton?.let { sb ->
                        sb.animate()
                            ?.alpha(0f)?.scaleX(0.3f)?.scaleY(0.3f)
                            ?.setDuration(160)
                            ?.withEndAction { sb.visibility = View.GONE }
                            ?.start()
                    }
                }
            }
        }
    }

    private fun positionLabel() {
        val lbl = labelView ?: return
        val pl = pillList ?: return
        lbl.text = labels.getOrNull(selectedIndex) ?: ""
        val desired = min(selectedIndex + 1, pl.childCount)
        if (pl.indexOfChild(lbl) == desired) return
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = labelGapPx
        if (lbl.parent != null) pl.removeView(lbl)
        pl.addView(lbl, desired, lp)
    }

    private fun refreshCollapsedSelection(from: Int, to: Int) {
        val old = pills.getOrNull(from)
        val new = pills.getOrNull(to) ?: return
        old?.let {
            it.visibility = View.GONE
            it.setBackgroundResource(android.R.color.transparent)
        }
        new.visibility = View.VISIBLE
        new.alpha = 1f
        new.scaleX = 1f
        new.scaleY = 1f
        new.updateSurface(surfaceColor)
    }

    private fun positionIndicator(index: Int, animate: Boolean) {
        val pill = pills.getOrNull(index) ?: pills.firstOrNull() ?: return
        val cLoc = IntArray(2)
        val pLoc = IntArray(2)
        container.getLocationInWindow(cLoc)
        pill.getLocationInWindow(pLoc)
        val vx = (pLoc[0] - cLoc[0]).toFloat()
        val vy = (pLoc[1] - cLoc[1]).toFloat()
        var width = pill.width
        val height = pill.height
        labelView?.let { lbl ->
            if (lbl.visibility == View.VISIBLE && !collapsed && lbl.width > 0) {
                width = pill.width + lbl.width + labelGapPx
            }
        }
        if (animate) {
            animateIndicator(vx, vy, width, height)
        } else {
            indicator.translationX = vx
            indicator.translationY = vy
            val lp = indicator.layoutParams
            lp.width = width
            lp.height = height
            indicator.layoutParams = lp
            indicator.visibility = View.VISIBLE
        }
    }

    private fun animateIndicator(vx: Float, vy: Float, width: Int, height: Int) {
        indicator.visibility = View.VISIBLE
        indicator.animate()
            .translationX(vx)
            .translationY(vy)
            .setDuration(340)
            .setInterpolator(springInterpolator)
            .start()
        ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofInt(INDICATOR_WIDTH, indicator.width, width),
            PropertyValuesHolder.ofInt(INDICATOR_HEIGHT, indicator.height, height)
        ).apply {
            duration = 340
            interpolator = springInterpolator
            addUpdateListener { anim ->
                val lp = indicator.layoutParams
                lp.width = anim.getAnimatedValue(INDICATOR_WIDTH) as Int
                lp.height = anim.getAnimatedValue(INDICATOR_HEIGHT) as Int
                indicator.layoutParams = lp
            }
            start()
        }
    }

    private fun animateContainerWidth(from: Int, to: Int, done: () -> Unit) {
        val lp = container.layoutParams
        lp.width = from.coerceAtLeast(1)
        container.layoutParams = lp
        ValueAnimator.ofInt(from.coerceAtLeast(1), to.coerceAtLeast(1)).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.width = anim.animatedValue as Int
                container.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.layoutParams = lp
                    done()
                }
            })
            start()
        }
    }

    private fun fadeOut(view: View) {
        view.animate().cancel()
        view.animate()
            .alpha(0f).scaleX(0.3f).scaleY(0.3f)
            .setDuration(170)
            .start()
    }

    private fun fadeAlpha(view: View, to: Float) {
        view.animate().cancel()
        view.animate().alpha(to).setDuration(170).start()
    }

    private fun ImageButton.updateSurface(color: Int) {
        background = GradientDrawable().apply {
            cornerRadius = pillRadiusPx
            setColor(color)
        }
    }
}