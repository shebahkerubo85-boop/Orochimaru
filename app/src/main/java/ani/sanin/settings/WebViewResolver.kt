package ani.sanin.settings

import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import ani.sanin.FileUrl
import ani.sanin.others.webview.VideoCatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Slow path for Direct URL playback: opens the saved page inside the app's
 * existing WebView catcher, lets JavaScript run (Cloudflare / captcha can be
 * solved by the user if needed) and captures the first media stream request
 * (.m3u8/.mpd/.mp4/.webm). The sheet auto-dismisses when a stream is caught.
 */
object WebViewResolver {

    private const val TIMEOUT_MS = 90_000L

    suspend fun catchVideos(
        activity: FragmentActivity,
        url: String,
        timeoutMs: Long = TIMEOUT_MS
    ): List<UrlVideoExtractor.ExtractedVideo> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val catcher = object : VideoCatcher(FileUrl(url), "Resolving video…") {
                override fun onDismiss(dialog: DialogInterface) {
                    super.onDismiss(dialog)
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            catcher.callback = { result ->
                val raw = result["videos"].orEmpty()
                val list = raw.lineSequence()
                    .filter { it.isNotBlank() }
                    .map { UrlVideoExtractor.ExtractedVideo(it, labelFor(it)) }
                    .toList()
                if (list.isNotEmpty() && cont.isActive) cont.resume(list)
            }
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                if (cont.isActive) cont.resume(emptyList())
                runCatching { catcher.dismissAllowingStateLoss() }
            }
            handler.postDelayed(timeout, timeoutMs)
            cont.invokeOnCancellation { handler.removeCallbacks(timeout) }
            if (activity.supportFragmentManager.isStateSaved) {
                handler.removeCallbacks(timeout)
                cont.resume(emptyList())
            } else {
                catcher.show(activity.supportFragmentManager, "direct_url_catcher")
            }
        }
    }

    private fun labelFor(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "HLS"
        url.contains(".mpd", ignoreCase = true) -> "DASH"
        url.contains(".webm", ignoreCase = true) -> "WEBM"
        else -> "MP4"
    }
}
