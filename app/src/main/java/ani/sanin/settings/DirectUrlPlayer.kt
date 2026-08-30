package ani.sanin.settings

import android.content.Context
import androidx.fragment.app.FragmentActivity
import ani.sanin.R
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.toast

/**
 * Plays a saved direct URL: fast HTML extraction first; when the page is
 * JS-rendered (like most anime player pages) it falls back to the WebView
 * catcher so JavaScript can run and the media stream can be captured.
 */
object DirectUrlPlayer {

    private val priority = mapOf("HLS" to 0, "MP4" to 1, "DASH" to 2, "WEBM" to 3)

    /**
     * @return true when a video was found and the player was launched.
     */
    suspend fun play(context: Context, name: String, url: String): Boolean {
        // Fast path: static HTML + regex.
        val fast = UrlVideoExtractor.extract(url)
        if (fast.videos.isNotEmpty()) {
            launchWith(context, name, url, fast.videos)
            return true
        }
        // Slow path: headless-ish WebView catcher (JS site, Cloudflare, embeds).
        val activity = context as? FragmentActivity
            ?: return false
        toast(context.getString(R.string.direct_url_resolving, name))
        val caught = WebViewResolver.catchVideos(activity, url).filter { it.url.isNotBlank() }
        if (caught.isNotEmpty()) {
            launchWith(context, name, url, caught)
            return true
        }
        return false
    }

    private suspend fun launchWith(
        context: Context,
        name: String,
        pageUrl: String,
        videos: List<UrlVideoExtractor.ExtractedVideo>
    ) {
        val chosen = pickBest(videos)
        val safeMediaId = (pageUrl.hashCode() and 0x7fffffff).coerceAtLeast(1)
        val d = TmdbDetail(
            id = safeMediaId,
            title = name,
            overview = pageUrl
        )
        val source = CsInstalledSource(
            id = "direct-url",
            name = name,
            version = 1,
            type = "movie",
            lang = "en",
            url = pageUrl,
            repoUrl = ""
        )
        val link = TmdbStreamResolver.StreamResult.PlayableLink(
            label = chosen.label,
            url = chosen.url,
            referer = pageUrl
        )
        TmdbStreamResolver.launchPlayer(
            context = context,
            mediaId = safeMediaId,
            mediaType = "movie",
            d = d,
            source = source,
            season = null,
            epNumber = null,
            links = listOf(link),
            pickedLabel = chosen.label
        )
    }

    private fun pickBest(videos: List<UrlVideoExtractor.ExtractedVideo>): UrlVideoExtractor.ExtractedVideo =
        videos.minByOrNull { priority[it.label] ?: 99 } ?: videos.first()
}
