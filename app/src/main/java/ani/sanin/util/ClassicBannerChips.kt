package ani.sanin.util

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.flexbox.FlexboxLayout
import ani.sanin.R
import ani.sanin.getThemeColor

/** Chips on the top line of the Classic banner. */
const val CLASSIC_CHIPS_TOP = 5

/** Chips on the bottom line of the Classic banner. */
const val CLASSIC_CHIPS_BOTTOM = 3

/** Total chip slots, so a long genre list cannot push the banner taller. */
const val CLASSIC_CHIPS_MAX = CLASSIC_CHIPS_TOP + CLASSIC_CHIPS_BOTTOM

/**
 * Renders the Classic banner's chips into two fixed rows.
 *
 * The first [CLASSIC_CHIPS_TOP] chips go on the top line and the rest go
 * underneath, which keeps the layout to two lines no matter how long the
 * labels are. Anything past [CLASSIC_CHIPS_MAX] is dropped, since the banner
 * has a fixed height and more chips would just push the logo off the card.
 */
fun bindClassicChips(
    container: ViewGroup?,
    top: ViewGroup?,
    bottom: ViewGroup?,
    labels: List<String>,
) {
    if (top == null || bottom == null) return
    top.removeAllViews()
    bottom.removeAllViews()

    val capped = labels.filter { it.isNotBlank() }.take(CLASSIC_CHIPS_MAX)
    if (capped.isEmpty()) {
        // Hide the wrapper too, otherwise it keeps its top margin and leaves a
        // gap above the logo.
        container?.isVisible = false
        top.isVisible = false
        bottom.isVisible = false
        return
    }
    container?.isVisible = true

    val split = minOf(capped.size, CLASSIC_CHIPS_TOP)
    addChips(top, capped.take(split))
    addChips(bottom, capped.drop(split))
    top.isVisible = true
    bottom.isVisible = capped.size > split
}

private fun addChips(row: ViewGroup, labels: List<String>) {
    if (labels.isEmpty()) {
        row.isVisible = false
        return
    }
    val ctx = row.context
    val res = ctx.resources
    val density = res.displayMetrics.density
    val gap = res.getDimension(R.dimen.banner_classic_chip_gap).toInt()
    val padH = res.getDimension(R.dimen.banner_classic_chip_pad).toInt()
    val height = res.getDimensionPixelSize(R.dimen.banner_classic_chip_height)
    val textSize = res.getDimension(R.dimen.banner_classic_chip_text) / density
    val onBackground = ctx.getThemeColor(com.google.android.material.R.attr.colorOnBackground)

    row.isVisible = true
    for (label in labels) {
        val chip = TextView(ctx).apply {
            text = label
            setTextColor(onBackground)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, textSize)
            setBackgroundResource(R.drawable.tag_chip_bg)
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            setPadding(padH, 0, padH, 0)
            layoutParams = FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                height,
            ).apply {
                marginEnd = gap
                flexBasis = 0f
            }
        }
        row.addView(chip)
    }
}
