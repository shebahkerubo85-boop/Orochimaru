package ani.sanin.util

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object NavPillCustomizer {

    fun getHeightDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(32, 72)
    fun getWidthDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillWidth).coerceIn(2, 100)
    fun getSpacingDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillSpacing).coerceIn(0, 35)
    fun getIconSizeDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28)
    fun getIconColor(): Int = PrefManager.getVal<Int>(PrefName.NavPillIconColor)
    fun getCornerRadiusDp(): Int = PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48)

    private fun computeIconPadding(pillSizeDp: Int, iconSizeDp: Int): Int {
        return ((pillSizeDp - iconSizeDp) / 2).coerceIn(2, pillSizeDp / 2 - 2)
    }

    fun applyToPillList(pillList: LinearLayout) {
        val density = pillList.resources.displayMetrics.density
        val width = getWidthDp()
        val height = getHeightDp()
        val spacing = getSpacingDp()
        val iconSize = getIconSizeDp()
        val iconColor = getIconColor()

        val pillWidthPx = (width * density).toInt()
        val pillHeightPx = (height * density).toInt()
        val iconPaddingPx = (computeIconPadding(width, iconSize) * density).toInt()
        val spacingPx = (spacing * density).toInt()

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
                if (isHorizontal) {
                    lp.width = pillHeightPx
                    lp.height = pillHeightPx
                } else {
                    lp.width = pillWidthPx
                    lp.height = pillHeightPx
                }
                child.layoutParams = lp
                child.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
                child.imageTintList = iconTint
            }
        }
    }

    fun applyToPillPreview(preview: View) {
        val group = preview as? ViewGroup ?: return
        val density = group.resources.displayMetrics.density
        val width = getWidthDp()
        val height = getHeightDp()
        val spacing = getSpacingDp()
        val iconSize = getIconSizeDp()
        val iconColor = getIconColor()

        val iconPaddingPx = (computeIconPadding(width, iconSize) * density).toInt()
        val spacingPx = (spacing * density).toInt()

        val previewH = (Math.max(height, 32) + spacing * 2) * density.toInt()
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
                clp.width = (width * density).toInt()
                clp.height = (height * density).toInt()
                child.layoutParams = clp
                child.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
                child.imageTintList = iconTint
            }
        }
    }
}
