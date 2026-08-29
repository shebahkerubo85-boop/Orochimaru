package ani.sanin.others.webview

import android.app.Activity
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import ani.sanin.currContext
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

object UrlVideoExtractor {

    private const val TAG = "UrlVideoExtractor"
    private const val WEBVIEW_TIMEOUT_MS = 15_000L

    data class ExtractedVideo(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val title: String? = null
    )

    /**
     * Extract video URL from any page.
     * Fast path: OkHttp + HTML parsing.
     * Slow path: Headless WebView (handles Cloudflare, JS obfuscation).
     */
    suspend fun extract(url: String): Result<List<ExtractedVideo>> = withContext(Dispatchers.IO) {
        try {
            // Fast path: fetch HTML and scan for video URLs
            val fastResult = extractFromHtml(url)
            if (fastResult.isNotEmpty()) {
                Logger.d(TAG, "Fast path found ${fastResult.size} videos for $url")
                return@withContext Result.success(fastResult)
            }

            // Slow path: headless WebView
            Logger.d(TAG, "Fast path empty, falling back to WebView for $url")
            val webResult = extractViaWebView(url)
            if (webResult.isNotEmpty()) {
                return@withContext Result.success(webResult)
            }

            Result.failure(Exception("No video found on this page"))
        } catch (e: Exception) {
            Logger.e(TAG, "Extraction failed for $url: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fast path: fetch the page HTML with OkHttp and scan for video URLs.
     */
    private suspend fun extractFromHtml(pageUrl: String): List<ExtractedVideo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<ExtractedVideo>()
        try {
            val client = okHttpClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            val doc = Jsoup.parse(html, pageUrl)

            // 1. Look for <video> and <source> tags
            doc.select("video[src], video source[src], source[type*=video]").forEach { el ->
                val src = el.attr("src").ifEmpty { return@forEach }
                val absoluteUrl = resolveUrl(pageUrl, src)
                val type = el.attr("type")
                val headers = mutableMapOf<String, String>()
                if (type.isNotEmpty()) headers["Content-Type"] = type
                videos.add(ExtractedVideo(absoluteUrl, headers))
            }

            // 2. Look for iframe embeds (common pattern: /embed/xxx)
            doc.select("iframe[src]").forEach { el ->
                val src = el.attr("src").ifEmpty { return@forEach }
                val absoluteUrl = resolveUrl(pageUrl, src)
                if (isEmbedUrl(absoluteUrl)) {
                    videos.add(ExtractedVideo(absoluteUrl))
                }
            }

            // 3. Scan for m3u8/mpd/mp4/webm URLs in the page source and scripts
            val urlPatterns = listOf(
                Regex("""["'](https?://[^"']*\.m3u8[^"']*?)["']"""),
                Regex("""["'](https?://[^"']*\.mpd[^"']*?)["']"""),
                Regex("""["'](https?://[^"']*\.mp4[^"']*?)["']"""),
                Regex("""["'](https?://[^"']*\.webm[^"']*?)["']"""),
                Regex("""src\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*?)["']"""),
                Regex("""file\s*[:=]\s*["'](https?://[^"']+)["']"""),
                Regex("""source\s*[:=]\s*["'](https?://[^"']+)["']""")
            )

            for (pattern in urlPatterns) {
                pattern.findAll(html).forEach { match ->
                    val foundUrl = match.groupValues[1]
                    if (foundUrl.isNotEmpty() && isVideoUrl(foundUrl)) {
                        val absoluteUrl = resolveUrl(pageUrl, foundUrl)
                        if (videos.none { it.url == absoluteUrl }) {
                            videos.add(ExtractedVideo(absoluteUrl))
                        }
                    }
                }
            }

            // 4. Check for JavaScript-based video loading (packed/encoded URLs)
            val jsPatterns = listOf(
                Regex("""eval\(function\(p,a,c,k,e,d\).*?\)"""),
                Regex("""var\s+\w+\s*=\s*["'](https?://[^"']+)["']""")
            )
            for (pattern in jsPatterns) {
                pattern.findAll(html).forEach { match ->
                    val script = match.value
                    // Try to decode packed JS
                    val decoded = tryUnpack(script)
                    if (decoded != null) {
                        val videoUrlPattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*|https?://[^\s"']+\.mp4[^\s"']*)""")
                        videoUrlPattern.findAll(decoded).forEach { urlMatch ->
                            val foundUrl = urlMatch.groupValues[1]
                            val absoluteUrl = resolveUrl(pageUrl, foundUrl)
                            if (videos.none { it.url == absoluteUrl }) {
                                videos.add(ExtractedVideo(absoluteUrl))
                            }
                        }
                    }
                }
            }

            // 5. Extract page title for display
            val pageTitle = doc.title().ifEmpty { null }

            // Attach title to all videos if we found one
            if (pageTitle != null) {
                return@withContext videos.map { it.copy(title = pageTitle) }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "HTML extraction error: ${e.message}")
        }
        videos
    }

    /**
     * Slow path: load the page in a headless WebView and capture network requests.
     * Handles Cloudflare challenges, JS obfuscation, and dynamic content.
     */
    private suspend fun extractViaWebView(pageUrl: String): List<ExtractedVideo> =
        withContext(Dispatchers.Main) {
            val activity = currContext() as? Activity ?: return@withContext emptyList()
            val candidates = Collections.synchronizedSet(LinkedHashSet<String>())
            var pageTitle: String? = null
            val container = FrameLayout(activity)
            val webView = WebView(activity)

            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean = false

                    override fun onJsAlert(
                        view: WebView?, url: String?, message: String?, result: JsResult?
                    ): Boolean {
                        result?.confirm()
                        return true
                    }

                    override fun onJsConfirm(
                        view: WebView?, url: String?, message: String?, result: JsResult?
                    ): Boolean {
                        result?.confirm()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView?, url: String?, message: String?,
                        defaultValue: String?, result: JsPromptResult?
                    ): Boolean {
                        result?.confirm("")
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (isMediaUrl(url) && !isAdUrl(url) && candidates.add(url)) {
                            Log.d(TAG, "WebView captured: ${url.take(140)}")
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Try to extract video URLs from the loaded DOM
                        view?.evaluateJavascript(VIDEO_EXTRACT_JS) { result ->
                            if (result != null && result != "null" && result.isNotEmpty()) {
                                val cleaned = result.removeSurrounding("\"").replace("\\\"", "\"")
                                cleaned.split("\n").forEach { line ->
                                    val trimmed = line.trim()
                                    if (trimmed.isNotEmpty() && isVideoUrl(trimmed) && candidates.add(trimmed)) {
                                        Log.d(TAG, "JS extracted: ${trimmed.take(140)}")
                                    }
                                }
                            }
                        }
                        pageTitle = view?.title
                    }
                }

                container.addView(webView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                // Make it 1x1 and invisible
                container.layout(0, 0, 1, 1)
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                content?.addView(container)

                webView.loadUrl(pageUrl)

                // Wait for video URLs or timeout
                val deadline = System.currentTimeMillis() + WEBVIEW_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline && candidates.isEmpty()) {
                    delay(250)
                }

                // Give a bit more time for JS to finish
                if (candidates.isNotEmpty()) {
                    delay(500)
                }

            } catch (e: Exception) {
                Logger.e(TAG, "WebView extraction error: ${e.message}")
            } finally {
                runCatching { webView.stopLoading() }
                runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
                runCatching { (container.parent as? ViewGroup)?.removeView(container) }
                runCatching { webView.destroy() }
            }

            val title = pageTitle
            candidates.map { ExtractedVideo(it, title = title) }.toList()
        }

    companion object {
        private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mpd", ".mp4", ".webm")
        private val EMBED_HOSTS = listOf(
            "youtube.com", "youtu.be", "dailymotion.com", "vimeo.com",
            "streamtape.com", "vidplay.online", "filemoon.sx",
            "streamsb.com", "doodstream.com", "tape.gg"
        )
        private val AD_MARKERS = listOf(
            "doubleclick", "googlesyndication", "googletagmanager", "googlead",
            "adservice", "amazon-adsystem", "outbrain", "taboola", "revcontent",
            "adform", "adsystem", "adsdk", "vast", "vpaid", "adserver",
            "/ad/", "/ads/", "exoclick", "/banner/", "adpush"
        )

        // JavaScript to extract video URLs from the DOM
        private const val VIDEO_EXTRACT_JS = """
        (function() {
            var urls = [];
            // <video> and <source> tags
            document.querySelectorAll('video[src], video source[src], source[type*=video]').forEach(function(el) {
                if (el.src) urls.push(el.src);
            });
            // Look for common player variables
            var scripts = document.querySelectorAll('script');
            scripts.forEach(function(s) {
                var text = s.textContent || '';
                var patterns = [
                    /file\s*[:=]\s*["']([^"']+)["']/g,
                    /source\s*[:=]\s*["']([^"']+)["']/g,
                    /src\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']/g,
                    /src\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']/g
                ];
                patterns.forEach(function(p) {
                    var m;
                    while ((m = p.exec(text)) !== null) {
                        if (m[1] && (m[1].indexOf('.m3u8') !== -1 || m[1].indexOf('.mp4') !== -1 || m[1].indexOf('.mpd') !== -1 || m[1].indexOf('.webm') !== -1)) {
                            urls.push(m[1]);
                        }
                    }
                });
            });
            return urls.join('\n');
        })()
        """

        fun isMediaUrl(url: String): Boolean {
            if (url.isEmpty()) return false
            val lower = url.lowercase()
            if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                lower.contains(".webp") || lower.contains(".svg") || lower.contains(".gif") ||
                lower.contains(".css") || lower.contains(".js") || lower.contains(".ico") ||
                lower.contains("favicon") || lower.endsWith("/")
            ) return false
            return lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.contains(".mp4") || lower.contains(".webm")
        }

        fun isVideoUrl(url: String): Boolean {
            val lower = url.lowercase()
            return VIDEO_EXTENSIONS.any { lower.contains(it) }
        }

        private fun isAdUrl(url: String): Boolean {
            val lower = url.lowercase()
            return AD_MARKERS.any { lower.contains(it) }
        }

        private fun isEmbedUrl(url: String): Boolean {
            val lower = url.lowercase()
            return EMBED_HOSTS.any { lower.contains(it) }
        }

        private fun resolveUrl(base: String, relative: String): String {
            return try {
                java.net.URL(java.net.URL(base), relative).toString()
            } catch (e: Exception) {
                relative
            }
        }

        /**
         * Attempt to decode packed/obfuscated JavaScript (Dean Edwards packer).
         */
        private fun tryUnpack(script: String): String? {
            return try {
                val packed = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\)""", RegexOption.DOT_MATCHES_ALL)
                    .find(script)?.value ?: return null
                // Simple unpacker: extract the packed data
                val args = Regex("""\{.*?\}""").find(packed)?.value ?: return null
                val p = Regex("""p\}(.*?)\);""").find(packed)?.groupValues?.get(1) ?: return null
                // For now, just return the raw script for URL extraction
                script
            } catch (e: Exception) {
                null
            }
        }
    }
}
