package ani.sanin.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

/**
 * Settings gates consulted by the Sanin downloader before it
 * does anything potentially destructive or network-sensitive.
 *
 * The downloader remains a pure stateless component; it asks
 * this object for permission to perform each guarded action.
 *
 *  - [shouldRetryExpiredUrls] — gate for the re-resolver path
 *    triggered by 401/403/410 responses.
 *  - [isWifiOnly]            — gate for new downloads; when on,
 *    the downloader refuses to start on cellular.
 *  - [isOnWifi]              — connectivity check helper.
 */
object SaninDownloadAutoManager {

    /**
     * Returns whether the Sanin downloader should re-resolve
     * expired URLs. When this returns false the supervisor
     * surfaces a hard failure to the user instead.
     */
    fun shouldRetryExpiredUrls(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.DownloadRetryExpiredUrls, true)

    /**
     * Returns whether new downloads should be paused on cellular.
     * The downloader checks [isOnWifi] before issuing the first
     * segment request; if it returns false and [isWifiOnly] is
     * true, the download is refused with a friendly error.
     */
    fun isWifiOnly(): Boolean =
        PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly, false)

    /**
     * True if the device currently has a Wi-Fi transport. Returns
     * true when the system reports no network at all (caller
     * decides whether to allow offline / queue).
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(net) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
