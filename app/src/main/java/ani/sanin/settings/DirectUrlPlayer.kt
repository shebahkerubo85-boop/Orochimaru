package ani.sanin.settings

import android.content.Context
import ani.sanin.R
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.connections.tmdb.TmdbDetail

/**
 * Plays a saved direct URL: fast HTML extraction -> best video -> existing
 * ExoplayerView pipeline (through TmdbStreamResolver's synthetic media).
 */
object DirectUrlPlayer {

    private val priority = mapOf("HLS" to 0, "MP4" to 1, "DASH" to 2, "WEBM" to 3)

    /**
     * @return true when a video was found and the player was launched.
     */
    suspend fun play(context: Context, name: String, url: String): Boolean {
        val result = UrlVideoExtractor.extract(url)
        val videos = result.videos
        if (videos.isEmpty()) return false
        val chosen = pickBest(videos)
        val safeMediaId = (url.hashCode() and 0x7fffffff).coerceAtLeast(1)
        val d = TmdbDetail(
            id = safeMediaId,
            title = name,
            overview = result.pageTitle ?: context.getString(R.string.direct_url)
        )
        val source = CsInstalledSource(
            id = "direct-url",
            name = name,
            version = 1,
            type = "movie",
            lang = "en",
            url = url,
            repoUrl = ""
        )
        val link = TmdbStreamResolver.StreamResult.PlayableLink(
            label = chosen.label,
            url = chosen.url,
            referer = url
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
        return true
    }

    private fun pickBest(videos: List<UrlVideoExtractor.ExtractedVideo>): UrlVideoExtractor.ExtractedVideo =
        videos.minByOrNull { priority[it.label] ?: 99 } ?: videos.first()
}
