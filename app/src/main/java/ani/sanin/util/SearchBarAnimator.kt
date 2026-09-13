package ani.sanin.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.AutoCompleteTextView
import android.widget.ImageView

/** Expands/collapses an inline search bar (0 ↔ 56 dp) with a decelerating slide. */
class SearchBarAnimator(
    private val bar: View,
    private val barText: AutoCompleteTextView,
    private val searchIcon: ImageView,
    private val iconOpen: Int,
    private val iconClose: Int
) {
    var expanded: Boolean = false
        private set

    fun toggle() = if (expanded) collapse() else expand()

    fun collapse() {
        if (!expanded) return
        expanded = false
        searchIcon.setImageResource(iconOpen)
        barText.clearFocus()
        val startHeight = bar.layoutParams.height
        ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.layoutParams.height = it.animatedValue as Int
                bar.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.visibility = View.GONE
                    bar.layoutParams.height = 0
                }
            })
        }.start()
    }

    fun expand() {
        if (expanded) return
        expanded = true
        searchIcon.setImageResource(iconClose)
        val heightPx = (56 * bar.resources.displayMetrics.density).toInt()
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        ValueAnimator.ofInt(0, heightPx).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.layoutParams.height = it.animatedValue as Int
                bar.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.alpha = 1f
                    barText.requestFocus()
                }
            })
        }.start()
    }
}
