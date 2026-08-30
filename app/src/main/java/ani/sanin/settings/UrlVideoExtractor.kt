package ani.sanin.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Fast-path video extraction for direct URL pages: fetch the HTML, scan
 * <video>/<source> tags, inline m3u8/mp4/mpd/webm URLs and iframes.
 */
object UrlVideoExtractor {

    data class ExtractedVideo(val url: String, val label: String)

    data class ExtractResult(
        val videos: List<ExtractedVideo>,
        val pageTitle: String? = null
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val videoUrlRegex = Regex(
        """https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:[?#][^\s"'<>\\]*)?|//[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:[?#][^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    suspend fun extract(pageUrl: String, depth: Int = 0): ExtractResult = withContext(Dispatchers.IO) {
        if (depth > 2) return@withContext ExtractResult(emptyList())
        val url = pageUrl.trim()
        if (url.isBlank() || !DirectUrlManager.isValidUrl(url)) return@withContext ExtractResult(emptyList())
        runCatching {
            val html = fetch(url) ?: return@withContext ExtractResult(emptyList())
            val videos = extractFromHtml(html, url).distinctBy { it.url }
            ExtractResult(videos, extractTitle(html))
        }.getOrDefault(ExtractResult(emptyList()))
    }

    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                return resp.body?.string()?.take(2_500_000)
            }
        }
        return null
    }

    private fun extractFromHtml(html: String, baseUrl: String): List<ExtractedVideo> {
        val found = mutableListOf<ExtractedVideo>()
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()

        // <video> and <source> tags
        doc?.select("video[src], source[src]")?.forEach { el ->
            val src = el.attr("abs:src").ifBlank { el.attr("src") }
            if (isVideoUrl(src)) found += ExtractedVideo(src, labelFor(src))
        }

        // Raw src attributes inside JS objects / data attributes
        videoUrlRegex.findAll(html).forEach { m ->
            val raw = m.groupValues[0]
            val abs = resolveUrl(raw, baseUrl)
            if (isVideoUrl(abs)) found += ExtractedVideo(abs, labelFor(abs))
        }

        // iframes (common embed pattern)
        doc?.select("iframe[src]")?.forEach { el ->
            val src = el.attr("abs:src").ifBlank { el.attr("src") }
            if (!src.startsWith("http")) return@forEach
            val inner = fetch(src)
            if (inner != null) {
                found += extractFromHtml(inner, src)
            }
        }

        return found.distinctBy { it.url }
    }

    private fun resolveUrl(raw: String, baseUrl: String): String {
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http")) return raw
        return runCatching { java.net.URI(baseUrl).resolve(raw).toString() }.getOrDefault(raw)
    }

    private fun isVideoUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".mpd", ignoreCase = true) ||
            url.contains(".webm", ignoreCase = true)

    private fun labelFor(url: String): String = when {
        url.contains(".m3u8", ignoreCase = true) -> "HLS"
        url.contains(".mpd", ignoreCase = true) -> "DASH"
        url.contains(".webm", ignoreCase = true) -> "WEBM"
        else -> "MP4"
    }

    private fun extractTitle(html: String): String? {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
        val t = doc?.title()?.trim()
        return t?.takeIf { it.isNotBlank() }?.take(120)
    }
}
