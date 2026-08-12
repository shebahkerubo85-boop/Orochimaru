package ani.sanin.others.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.sanin.FileUrl

class VideoCatcher(
    override val location: FileUrl,
    override val title: String = "Loading video…"
) : WebViewBottomDialog() {

    private val found = LinkedHashSet<String>()

    override val webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            if (isMedia(url) && found.add(url)) {
                view?.post {
                    privateCallback.invoke(mapOf("videos" to found.joinToString("\n")))
                }
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    companion object {
        fun newInstance(url: FileUrl) = VideoCatcher(url)
        fun newInstance(url: String) = VideoCatcher(FileUrl(url))
    }
}

private fun isMedia(url: String): Boolean {
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
        lower.contains(".webp") || lower.contains(".svg") || lower.contains(".gif") ||
        lower.contains(".css") || lower.contains(".js") || lower.endsWith("/")
    ) return false
    return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4")
}
