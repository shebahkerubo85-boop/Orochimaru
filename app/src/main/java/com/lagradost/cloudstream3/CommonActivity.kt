package com.lagradost.cloudstream3

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.UiText
import java.lang.ref.WeakReference

object CommonActivity {

    private var _activity: WeakReference<Activity>? = null
    var activity: Activity?
        get() = _activity?.get()
        private set(value) { _activity = WeakReference(value) }

    var isInPIPMode: Boolean = false
    var keyEventListener: ((KeyEvent) -> Boolean)? = null

    val screenWidth: Int get() {
        val act = activity ?: return 0
        return act.resources.displayMetrics.widthPixels
    }
    val screenHeightWithOrientation: Int get() {
        val act = activity ?: return 0
        val res = act.resources
        val isLandscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) res.displayMetrics.widthPixels else res.displayMetrics.heightPixels
    }
    val screenWidthWithOrientation: Int get() {
        val act = activity ?: return 0
        val res = act.resources
        val isLandscape = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) res.displayMetrics.heightPixels else res.displayMetrics.widthPixels
    }

    fun setActivityInstance(newActivity: Activity?) { activity = newActivity }

    private var currentToast: Toast? = null

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, act.getString(message), duration) }
    }

    fun showToast(message: String?, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread { showToast(act, message, duration) }
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        val act = activity ?: return
        if (message == null) return
        act.runOnUiThread { showToast(act, message.asString(act), duration) }
    }

    fun showToast(act: Activity?, text: UiText, duration: Int) {
        if (act == null) return
        text.asStringNull(act)?.let { showToast(act, it, duration) }
    }

    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        if (act == null) return
        showToast(act, act.getString(message), duration)
    }

    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (act == null || message == null) return
        try { currentToast?.cancel() } catch (_: Exception) {}
        val toast = Toast.makeText(act, message, duration ?: Toast.LENGTH_SHORT)
        currentToast = toast
        toast.show()
    }

    fun hideSystemUI(activity: Activity?) {}
    fun showSystemUI(activity: Activity?) {}
}
