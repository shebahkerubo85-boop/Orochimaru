package ani.sanin.cloudstream

import android.view.ViewGroup
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.core.view.updateLayoutParams
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

object TmdbCards {

    fun isLandscapeOrientation(): Boolean = PrefManager.getVal<Int>(PrefName.CardOrientation) == 0

    fun cardSize(): Float = PrefManager.getVal(PrefName.CardSize)

    fun roundness(): Float {
        return when (PrefManager.getVal<Int>(PrefName.CardStyle)) {
            4 -> 24f
            6 -> 4f
            else -> PrefManager.getVal<Int>(PrefName.StandardCardRoundness).toFloat()
        }
    }

    fun applyCardStyle(image: ImageView, card: CardView) {
        val size = cardSize()
        val (w, h) = if (isLandscapeOrientation()) {
            (260f * size).toInt() to (148f * size).toInt()
        } else {
            (102f * size).toInt() to (154f * size).toInt()
        }
        image.updateLayoutParams<ViewGroup.LayoutParams> {
            width = w
            height = h
        }
        card.radius = roundness()
    }
}
