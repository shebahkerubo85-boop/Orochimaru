package ani.sanin.settings

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object AnimUtils {

    private fun enabled(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) &&
        PrefManager.getVal<Boolean>(PrefName.XpandableAnimations)

    /** Slide-down roll from the header. Falls back to instant show when disabled. */
    fun rollExpand(items: View) {
        if (!enabled()) { items.visibility = View.VISIBLE; return }
        val d = items.resources.displayMetrics.density
        items.visibility = View.VISIBLE
        items.alpha = 0f
        items.translationY = -items.height.toFloat().coerceAtLeast(12f * d)
        items.animate()
            .alpha(1f).translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Slide-up collapse. Falls back to instant hide when disabled. */
    fun rollCollapse(items: View) {
        if (!enabled()) { items.visibility = View.GONE; return }
        val d = items.resources.displayMetrics.density
        val slideH = items.height.toFloat().coerceAtLeast(12f * d)
        items.animate()
            .alpha(0f).translationY(-slideH * 0.4f)
            .setDuration(160)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                items.visibility = View.GONE
                items.translationY = 0f
            }.start()
    }

    /** Returns animation duration scaled by speed pref, or 0 when disabled. */
    fun duration(ms: Long): Long =
        if (enabled()) (ms * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong() else 0L
}
