package ani.sanin.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import ani.sanin.getThemeColor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath

class TomoeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LottieAnimationView(context, attrs) {

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val railColor = if (PrefManager.getVal<String>(PrefName.Theme).contains("MONOCHROME", ignoreCase = true)) Color.BLACK
    else context.getThemeColor(com.google.android.material.R.attr.colorPrimary)

    init {
        if (composition != null) applyFilters()
        addLottieOnCompositionLoadedListener { applyFilters() }
    }

    private fun applyFilters() {
        val dots = PorterDuffColorFilter(if (dark) Color.WHITE else Color.BLACK, PorterDuff.Mode.SRC_IN)
        val rail = PorterDuffColorFilter(railColor, PorterDuff.Mode.SRC_IN)
        val outline = PorterDuffColorFilter(if (monochrome) Color.BLACK else railColor, PorterDuff.Mode.SRC_IN)
        val railColorOnMonochrome = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        for (nm in arrayOf("Ellipse 281", "Ellipse 280", "Ellipse 279")) {
            addValueCallback(KeyPath(nm, "**", "**"), LottieProperty.COLOR_FILTER) { dots }
            addValueCallback(KeyPath(nm, nm, "Contour 1"), LottieProperty.COLOR_FILTER) { dots }
        }
        addValueCallback(KeyPath("Cercle complet", "**", "**"), LottieProperty.COLOR_FILTER) { if (monochrome) railColorOnMonochrome else rail }
        addValueCallback(KeyPath("Cercle complet", "Ellipse 279", "Contour en dégradé 1"), LottieProperty.COLOR_FILTER) { if (monochrome) railColorOnMonochrome else rail }
        addValueCallback(KeyPath("Rail outline", "**", "**"), LottieProperty.COLOR_FILTER) { outline }
    }
}
