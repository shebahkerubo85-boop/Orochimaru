package ani.sanin.profile.notification

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

/**
 * Theme-adaptive three-layer gradient for the episode notification card:
 *
 *   transparent -> semi dark/white -> solid black (dark mode) / white (light mode)
 *
 * The right edge is always fully opaque so adaptive text stays readable and the
 * card always reads as "dark slab in dark mode, white slab in light mode".
 */
object EpisodeCardGradient {

    fun build(context: Context): GradientDrawable {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val base = if (isDark) Color.BLACK else Color.WHITE
        val colors = intArrayOf(
            ColorUtils.setAlphaComponent(base, 0),
            ColorUtils.setAlphaComponent(base, 110),
            base
        )
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            drawable.setColors(colors, floatArrayOf(0f, 0.55f, 1f))
        }
        return drawable
    }
}
