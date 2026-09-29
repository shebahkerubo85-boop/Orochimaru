package ani.sanin.util

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import ani.sanin.isDarkTheme
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object NavPillCustomizer {

    fun getHeightDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(32, 72)
    fun getIconSizeDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28)

    /** Icon colour is always whatever the Icon Tint setting says. */
    fun getIconColor(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconColor)

    fun isDarkTheme(): Boolean = ani.sanin.isDarkTheme()
    fun getCornerRadiusDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48)

    /**
     * The pill's own fill, used whenever glass is off. Previously this lived in
     * SnakeNavRailView as a black-to-white gradient, which only ever drew on the
     * vertical rail; the horizontal pill was left transparent, so its icons appeared
     * to float with nothing behind them. One solid colour for both orientations.
     */
    fun getPillFillColor(): Int = if (isDarkTheme()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    fun applyPillBackground(view: View) {
        val density = view.resources.displayMetrics.density
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = getCornerRadiusDp() * density
            setColor(getPillFillColor())
        }
    }

    private fun computeIconPadding(pillSizeDp: Int, iconSizeDp: Int): Int {
        return ((pillSizeDp - iconSizeDp) / 2).coerceIn(2, pillSizeDp / 2 - 2)
    }

    /** Fixed inner padding between the pill edges and neighbouring pills. */
    private const val fixedSpacingDp = 6

    fun applyToPillList(pillList: LinearLayout) {
        val density = pillList.resources.displayMetrics.density
        val height = getHeightDp()
        val iconSize = getIconSizeDp()
        val iconColor = getIconColor()

        val pillHeightPx = (height * density).toInt()
        val iconPaddingPx = (computeIconPadding(height, iconSize) * density).toInt()
        val spacingPx = (fixedSpacingDp * density).toInt()

        val isHorizontal = pillList.orientation == LinearLayout.HORIZONTAL
        if (isHorizontal) {
            pillList.setPadding(spacingPx, pillList.paddingTop, spacingPx, pillList.paddingBottom)
        } else {
            pillList.setPadding(pillList.paddingLeft, spacingPx, pillList.paddingRight, spacingPx)
        }

        val iconTint = ColorStateList.valueOf(iconColor)
        for (i in 0 until pillList.childCount) {
            val child = pillList.getChildAt(i)
            if (child is ImageButton) {
                val lp = child.layoutParams
                // Pills are always square: width tracks height in both orientations.
                lp.width = pillHeightPx
                lp.height = pillHeightPx
                child.layoutParams = lp
                child.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
                child.imageTintList = iconTint
            }
        }
    }
}
