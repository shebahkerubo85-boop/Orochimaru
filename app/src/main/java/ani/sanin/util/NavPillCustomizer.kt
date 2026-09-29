package ani.sanin.util

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import ani.sanin.isDarkTheme
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object NavPillCustomizer {

    fun getHeightDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(32, 72)
    fun getIconSizeDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28)

    /**
     * Icon colour is user-overridable, but the default follows the pill: white icons
     * on the black dark-mode pill, black icons on the white light-mode pill.
     */
    fun getIconColor(): Int {
        val stored = PrefManager.getVal<Int>(PrefName.NavPillIconColor)
        if (stored != DEFAULT_ICON_COLOR) return stored
        return if (isDarkTheme()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    /** Sentinel meaning "follow the theme" rather than a concrete colour. */
    const val DEFAULT_ICON_COLOR = 0xFFFFFFFF.toInt()

    fun isDarkTheme(): Boolean = ani.sanin.isDarkTheme()
    fun getCornerRadiusDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48)

    private fun computeIconPadding(pillSizeDp: Int, iconSizeDp: Int): Int {
        return ((pillSizeDp - iconSizeDp) / 2).coerceIn(2, pillSizeDp / 2 - 2)
    }

    /** Fixed inner padding between the pill edges and neighbouring pills. */
    private val fixedSpacingDp = 6

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

    fun applyToPillPreview(preview: View) {
        val group = preview as? ViewGroup ?: return
        val density = group.resources.displayMetrics.density
        val height = getHeightDp()
        val iconSize = getIconSizeDp()
        val iconColor = getIconColor()

        val iconPaddingPx = (computeIconPadding(height, iconSize) * density).toInt()
        val spacingPx = (fixedSpacingDp * density).toInt()

        val previewH = ((Math.max(height, 32) + fixedSpacingDp * 2) * density).toInt()
        val lp = group.layoutParams
        if (lp.height != previewH) {
            lp.height = previewH
            group.layoutParams = lp
        }

        val iconTint = ColorStateList.valueOf(iconColor)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ImageButton) {
                val clp = child.layoutParams
                clp.width = (height * density).toInt()
                clp.height = (height * density).toInt()
                child.layoutParams = clp
                child.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
                child.imageTintList = iconTint
            }
        }
    }
}
