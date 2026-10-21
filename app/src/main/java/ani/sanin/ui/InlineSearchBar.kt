package ani.sanin.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import androidx.core.widget.addTextChangedListener
import ani.sanin.R
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil

/**
 * The lens-icon search button and the bar it reveals, as one reusable piece.
 *
 * This is the extensions screen's search, lifted out of it: a small lens button that grows
 * into a full-width bar under the header, the same way the extensions screen's own search
 * opens. The bar is a sibling of the row it belongs to rather than an overlay, so opening it
 * pushes what is below it down instead of covering it.
 *
 * The button is a lens because a bare magnifier on a flat page reads as decoration, and the
 * lens rim is the app's own button surface — the same one the extensions screen uses, so the
 * two do not look like two different buttons.
 *
 * D-pad: the button is a focus target like any other. Pressing it opens the bar and moves
 * focus into the field, so typing starts at once; the field's own next-focus up is the
 * button, so the way back is one press rather than a hunt.
 */
class InlineSearchBar(
    val button: ImageButton,
    private val bar: View,
    private val field: EditText,
    private val onQueryChanged: (String) -> Unit,
    private val onQuerySettled: (String) -> Unit = {},
) {

    /** True while the bar is open. */
    var isExpanded: Boolean = false
        private set

    /** The query as last typed, for the settled callback. */
    private var latestQuery: String = ""

    private val handler = Handler(Looper.getMainLooper())

    /** Runs once the typing has stopped, so a report is said once rather than per keystroke. */
    private val settle = Runnable { onQuerySettled(latestQuery) }

    /** The watcher on the field, kept so it can be taken off again when this is released. */
    private var watcher: TextWatcher? = null

    init {
        LensButtonBackground.apply(button)
        FocusEffectUtil.applyFocusListener(button)
        button.setOnClickListener { toggle() }
        watcher = field.addTextChangedListener { text ->
            latestQuery = text?.toString()?.trim().orEmpty()
            onQueryChanged(latestQuery)
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, SETTLE_MS)
        }
        TvKeyboardUtil.setupTvInput(field)
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    /**
     * Clears the query and leaves the bar as it is.
     *
     * For a row that has been rebuilt from scratch: the cards behind the bar are new ones, so
     * a query left in the field would be filtering a row that no longer exists, and the field
     * would be showing a search for cards that are gone.
     */
    fun clearQuery() {
        field.setText("")
    }

    /**
     * Drops the pending settled callback and the field's watcher.
     *
     * The callback reaches back into the screen that owns the row, so it must not fire after
     * that screen has gone, and the watcher must not outlive this either: the field outlives
     * the bar's owner when the page is rebuilt, and a watcher left on it would keep filtering
     * a row that is no longer on screen.
     */
    fun release() {
        handler.removeCallbacks(settle)
        watcher?.let { field.removeTextChangedListener(it) }
        watcher = null
    }

    /**
     * Opens the bar and puts the caret in it.
     *
     * The height is animated rather than set, so the content below slides down instead of
     * jumping. The field only takes focus once the bar has finished growing, because a field
     * that is focused while its container is still at zero height gets no keyboard.
     */
    fun expand() {
        if (isExpanded) return
        isExpanded = true
        button.setImageResource(R.drawable.ic_round_close_24)
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        ValueAnimator.ofInt(0, barHeightPx()).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.layoutParams.height = it.animatedValue as Int
                bar.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bar.alpha = 1f
                    field.requestFocus()
                    TvKeyboardUtil.showKeyboardDelayed(field)
                }
            })
        }.start()
    }

    /** Closes the bar, clears the query and hands focus back to the button. */
    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        button.setImageResource(R.drawable.ic_round_search_24)
        field.clearFocus()
        field.setText("")
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
                    onQueryChanged("")
                }
            })
        }.start()
    }

    private fun barHeightPx(): Int =
        (56 * bar.resources.displayMetrics.density).toInt()

    companion object {
        /** How long the typing has to pause before the query counts as settled. */
        private const val SETTLE_MS = 400L
    }
}
