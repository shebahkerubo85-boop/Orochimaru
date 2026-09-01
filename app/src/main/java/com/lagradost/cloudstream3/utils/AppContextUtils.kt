package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.ui.settings.Globals

object AppContextUtils {

    /**
     * Sets the focus to the negative button when in TV and Emulator layout.
     **/
    fun AlertDialog.setDefaultFocus(buttonFocus: Int = android.content.DialogInterface.BUTTON_NEGATIVE) {
        if (!Globals.isLayout(Globals.TV or Globals.EMULATOR)) return
        this.getButton(buttonFocus).run {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    /** Opens the url in an external browser. */
    fun Context.openBrowser(url: String, fallbackWebView: Boolean = false, fragment: androidx.fragment.app.Fragment? = null) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            // No browser available; silently ignore.
        }
    }
}
