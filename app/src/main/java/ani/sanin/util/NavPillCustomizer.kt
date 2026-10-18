package ani.sanin.util

import android.content.res.ColorStateList
import android.content.res.Resources
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

    /** Whether the selected pill shows its text. Off leaves the rail as icons only. */
    fun getShowLabel(): Boolean = PrefManager.getVal<Boolean>(PrefName.NavPillShowLabel)

    /** Whether scrolling collapses the rail. Off pins it expanded. */
    fun getShrinkOnScroll(): Boolean = PrefManager.getVal<Boolean>(PrefName.NavPillShrinkOnScroll)

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

    /** Padding applied inside each pill, in px for a pill of [pillSizePx]. */
    fun getIconPaddingPx(pillSizePx: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (computeIconPadding(getHeightDp(), getIconSizeDp()) * density).toInt()
            .coerceAtMost(pillSizePx / 2)
    }

    /**
     * How wide the icon glyph actually draws inside a pill of [pillSizePx]. The pill
     * is square with symmetric padding and a centred fitCenter drawable, so the glyph is
     * the content box, capped by the configured icon size.
     */
    fun getIconDrawnSizePx(pillSizePx: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        val content = pillSizePx - 2 * getIconPaddingPx(pillSizePx)
        return minOf((getIconSizeDp() * density).toInt(), content).coerceAtLeast(0)
    }

    /** Fixed inner padding between the pill edges and neighbouring pills. */
    private const val fixedSpacingDp = 6

    /**
     * Icon Size padding plus the Icon Tint, so every nav icon matches the settings.
     * Shared with the search icon, which the controller creates lazily and therefore
     * after [applyToPillList] has already run over the XML pills.
     */
    fun applyIconSettings(btn: ImageButton, iconPaddingPx: Int) {
        btn.setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
        btn.imageTintList = ColorStateList.valueOf(getIconColor())
    }

    fun applyToPillList(pillList: LinearLayout) {
        val density = pillList.resources.displayMetrics.density
        val height = getHeightDp()
        val iconSize = getIconSizeDp()

        val pillSizePx = (height * density).toInt()
        val spacingPx = (fixedSpacingDp * density).toInt()
        val isHorizontal = pillList.orientation == LinearLayout.HORIZONTAL

        if (isHorizontal) {
            pillList.setPadding(spacingPx, pillList.paddingTop, spacingPx, pillList.paddingBottom)
        } else {
            pillList.setPadding(pillList.paddingLeft, spacingPx, pillList.paddingRight, spacingPx)
            // On the TV rail, Pill Height controls horizontal thickness only. Preserve each
            // item's existing vertical height so changing the setting never stretches the
            // rail downwards or changes the D-pad distance between tabs.
            (pillList.parent as? View)?.let { rail ->
                val railLp = rail.layoutParams
                if (railLp != null && railLp.width != pillSizePx) {
                    railLp.width = pillSizePx
                    rail.layoutParams = railLp
                }
            }
        }

        for (i in 0 until pillList.childCount) {
            val child = pillList.getChildAt(i)
            if (child is ImageButton) {
                val lp = child.layoutParams
                if (isHorizontal) {
                    lp.width = pillSizePx
                    lp.height = pillSizePx
                } else {
                    lp.width = pillSizePx
                    // Keep XML's per-item height in vertical mode (normally 44dp).
                }
                child.layoutParams = lp
                val contentSizeDp = if (isHorizontal) height else minOf(height, 44)
                val iconPaddingPx = (computeIconPadding(contentSizeDp, iconSize) * density).toInt()
                applyIconSettings(child, iconPaddingPx)
            }
        }
    }
}
