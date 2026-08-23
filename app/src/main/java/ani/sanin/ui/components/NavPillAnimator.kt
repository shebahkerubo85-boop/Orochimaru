package ani.sanin.ui.components

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.graphics.drawable.GradientDrawable

class NavPillAnimator(
    private val container: ViewGroup,
    private val pills: List<ImageButton>
) {
    private var indicator: View? = null
    private var selectedIndex = -1
    private var currentAnim: android.animation.AnimatorSet? = null
    private var indicatorSize = 0

    fun attach() {
        if (indicator != null) return
        val density = container.context.resources.displayMetrics.density
        indicatorSize = (44 * density).toInt()
        val radius = indicatorSize / 2

        indicator = View(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(indicatorSize, indicatorSize)
            alpha = 0f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(0x28FFFFFF)
            }
        }
        val insertIndex = (container.childCount - 1).coerceAtLeast(0)
        container.addView(indicator, insertIndex)
    }

    fun select(index: Int) {
        attach()
        val target = pills.getOrNull(index) ?: return
        val ind = indicator ?: return

        target.post {
            val containerLoc = IntArray(2).also { container.getLocationOnScreen(it) }
            val pillLoc = IntArray(2).also { target.getLocationOnScreen(it) }
            val x = pillLoc[0] - containerLoc[0]
            // ← CENTER VERTICALLY: pill center minus indicator radius
            val pillCenterY = pillLoc[1].toFloat() + (target.height / 2f)
            val indY = pillCenterY - (indicatorSize / 2f)
            val y = indY

            if (selectedIndex < 0) {
                ind.translationX = x.toFloat()
                ind.translationY = y.toFloat()
                ind.alpha = 1f
            } else {
                animateIndicator(ind, x.toFloat(), y)
            }
            selectedIndex = index
        }

        animateIcon(index)
    }

    private fun animateIndicator(ind: View, targetX: Float, targetY: Float) {
        currentAnim?.cancel()

        val xAnim = ValueAnimator.ofFloat(ind.translationX, targetX).apply {
            duration = 350
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { ind.translationX = it.animatedValue as Float }
        }
        val yAnim = ValueAnimator.ofFloat(ind.translationY, targetY).apply {
            duration = 350
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { ind.translationY = it.animatedValue as Float }
        }

        currentAnim = android.animation.AnimatorSet().apply {
            playTogether(xAnim, yAnim)
            start()
        }
    }

    private fun animateIcon(selectedIdx: Int) {
        pills.forEachIndexed { i, pill ->
            val targetScale = if (i == selectedIdx) 1.18f else 1f
            pill.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                .start()
        }
    }
}
