package ani.sanin.cloudstream

import android.content.Context
import ani.sanin.FileUrl
import ani.sanin.download.video.Helper
import ani.sanin.media.MediaKind
import ani.sanin.media.MediaType
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoType
import ani.sanin.util.Logger

/**
 * Bridges a TMDB/movie-mode stream into the shared download pipeline.
 *
 * The download engine is media-source agnostic: it only needs a resolved url plus
 * request headers, which is exactly what a CloudStream plugin link already carries.
 * Nothing here re-resolves the source or builds its own networking path — the very
 * same link the player would open is the one that gets queued.
 */
object TmdbDownload {

    /**
     * Queues [link] for download as [MediaType.MOVIE].
     *
     * @param title display title, used as the library folder name
     * @param episode episode label ("S1E2"), or the movie title for single films
     * @param posterUrl optional artwork, shown while the download runs
     * @return false when the link cannot be downloaded (blank url or DRM protected)
     */
    fun start(
        context: Context,
        link: TmdbStreamResolver.PlayableLink,
        title: String,
        episode: String,
        posterUrl: String? = null,
        kind: MediaKind = MediaKind.MOVIE
    ): Boolean {
        if (link.url.isBlank()) {
            Logger.log("TMDB_DOWNLOAD: refusing blank url for '$title'")
            return false
        }

        // DRM needs a licence server plus a persistent key, which Sanin does not
        // implement yet, so fail loudly instead of writing an unplayable file.
        if (link.drm != null) {
            Logger.log("TMDB_DOWNLOAD: DRM-protected link rejected for '$title'")
            return false
        }

        val headers = HashMap(link.headers).apply {
            link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }

        val video = Video(
            quality = qualityFromLabel(link.label),
            format = videoTypeFor(link.url),
            file = FileUrl(link.url, headers),
            drm = link.drm
        )

        val audioTracks = link.audioTracks.mapNotNull { track ->
            val url = track.url
            if (url.isBlank()) null else url to ""
        }

        return try {
            Helper.startAnimeDownloadService(
                context = context,
                title = title,
                episode = episode,
                video = video,
                subtitle = emptyList(),
                audio = audioTracks,
                sourceMedia = null,
                episodeImage = posterUrl,
                mediaType = MediaType.MOVIE,
                kind = kind
            )
            Logger.log(
                "TMDB_DOWNLOAD: queued '${video.file.url}' as ${video.format} for '$title' ($episode)"
            )
            true
        } catch (e: Exception) {
            Logger.log("TMDB_DOWNLOAD: failed to queue '$title': ${e.message}")
            false
        }
    }

    private fun qualityFromLabel(label: String): Int? =
        Regex("\\d{3,4}").find(label)?.value?.toIntOrNull()

    private fun videoTypeFor(url: String): VideoType = when {
        url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
        url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
        else -> VideoType.CONTAINER
    }
}