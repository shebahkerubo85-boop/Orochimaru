package ani.sanin.ui.components

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.graphics.drawable.GradientDrawable
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

class NavPillAnimator(
    private val container: ViewGroup,
    private val pills: List<ImageButton>
) {
    private var indicator: View? = null
    private var selectedIndex = -1
    private var offsetXAnim: SpringAnimation? = null
    private var widthAnim: SpringAnimation? = null

    fun attach() {
        if (indicator != null) return
        val insertIndex = if (container.getChildAt(0)?.id == View.NO_ID ||
            container.getChildAt(0)?.alpha == 1f && container.childCount > 1) 1 else 0

        indicator = View(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(0, 0)
            alpha = 0f
            val radius = 22 * container.context.resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(0x28FFFFFF)
            }
        }
        container.addView(indicator, insertIndex.coerceAtMost(container.childCount))
    }

    fun select(index: Int) {
        attach()
        val target = pills.getOrNull(index) ?: return
        val ind = indicator ?: return

        target.post {
            val containerLoc = IntArray(2).also { container.getLocationOnScreen(it) }
            val pillLoc = IntArray(2).also { target.getLocationOnScreen(it) }
            val x = pillLoc[0] - containerLoc[0]
            val y = pillLoc[1] - containerLoc[1]
            val w = target.width
            val h = target.height

            ind.layoutParams = (ind.layoutParams as FrameLayout.LayoutParams).also {
                it.width = w; it.height = h; it.setMargins(x, y, 0, 0)
            }

            if (selectedIndex < 0) {
                ind.requestLayout()
                ind.alpha = 1f
            } else {
                animateTo(x, y, w, h)
            }
            selectedIndex = index
        }

        animateIcon(index)
    }

    private fun animateTo(x: Int, y: Int, w: Int, h: Int) {
        val ind = indicator ?: return
        val lp = ind.layoutParams as FrameLayout.LayoutParams

        offsetXAnim?.cancel()
        widthAnim?.cancel()

        offsetXAnim = SpringAnimation(FloatValueHolder(lp.leftMargin.toFloat()))
            .setSpring(
                SpringForce().setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM_LOW)
            )
            .addUpdateListener { _, value, _ ->
                lp.leftMargin = Math.round(value); ind.requestLayout()
            }
        offsetXAnim?.animateToFinalPosition(x.toFloat())

        SpringAnimation(FloatValueHolder(lp.topMargin.toFloat()))
            .setSpring(
                SpringForce().setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM_LOW)
            )
            .addUpdateListener { _, value, _ ->
                lp.topMargin = Math.round(value); ind.requestLayout()
            }
            .start()

        widthAnim = SpringAnimation(FloatValueHolder(lp.width.toFloat()))
            .setSpring(
                SpringForce().setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM_LOW)
            )
            .addUpdateListener { _, value, _ ->
                lp.width = Math.round(value); ind.requestLayout()
            }
        widthAnim?.animateToFinalPosition(w.toFloat())

        SpringAnimation(FloatValueHolder(lp.height.toFloat()))
            .setSpring(
                SpringForce().setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM_LOW)
            )
            .addUpdateListener { _, value, _ ->
                lp.height = Math.round(value); ind.requestLayout()
            }
            .start()
    }

    private fun animateIcon(selectedIdx: Int) {
        pills.forEachIndexed { i, pill ->
            val spring = SpringForce()
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM_LOW)
            val scale = if (i == selectedIdx) 1.12f else 1f

            SpringAnimation(pill, DynamicAnimation.SCALE_X)
                .setSpring(spring).animateToFinalPosition(scale)
            SpringAnimation(pill, DynamicAnimation.SCALE_Y)
                .setSpring(spring).animateToFinalPosition(scale)
        }
    }
}
