package ani.sanin.cloudstream

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

/**
 * Utility for checking and blocking ad / redirect URLs.
 */
object AdBlocker {

    private const val TAG = "AdBlock"

    /**
     * Domains known to serve ads / redirects from CNCVerse Cricify plugin.
     * Add more as new ones are discovered.
     */
    private val AD_DOMAINS = setOf(
        "cfyhljddgbkkufh82.top",
        "cfylsjhetvdak135.top",
    )

    fun isAdHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return AD_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /** Returns true if the intent is a browser open to an ad URL. */
    fun isAdIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val host = uri.host?.lowercase() ?: return false
        return isAdHost(host)
    }
}

/**
 * Wraps a [Context] so that any [startActivity] call opening an ad URL is
 * silently swallowed instead of launching a browser.  Used when loading
 * CloudStream plugins that may inject ad redirects.
 *
 * Only active when the "HTTP/Intent Interceptor" toggle is enabled in
 * Settings > Common. When disabled, all calls pass through unchanged.
 */
class AdBlockContextWrapper(base: Context) : ContextWrapper(base) {

    private val enabled: Boolean
        get() = PrefManager.getVal<Boolean>(PrefName.AdBlockInterceptor)

    override fun startActivity(intent: Intent) {
        if (enabled && AdBlocker.isAdIntent(intent)) {
            Log.w("AdBlock", "Intercepted ad intent: ${intent.data}")
            return
        }
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: android.os.Bundle?) {
        if (enabled && AdBlocker.isAdIntent(intent)) {
            Log.w("AdBlock", "Intercepted ad intent: ${intent.data}")
            return
        }
        super.startActivity(intent, options)
    }
}
