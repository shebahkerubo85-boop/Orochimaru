package ani.sanin.download

import android.net.Uri
import ani.sanin.FileUrl
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import java.io.File

/** Name of the synthetic server used when an episode is served from a finished download. */
const val DOWNLOADED_SERVER_NAME = "Downloaded"

/**
 * Locator stored on a [DownloadedItem] can be an absolute file path (app storage) or a `content://`
 * uri (SAF). ExoPlayer needs a proper uri, so normalize bare paths to `file://`.
 */
fun DownloadedItem.playbackUri(): String {
    if (path.startsWith(DownloadHlsCache.LOCATOR_PREFIX)) {
        return playbackUrl ?: sourceUrl
            ?: throw IllegalStateException("Downloaded HLS source URL is missing")
    }
    val p = path
    return if (p.startsWith("content://") || p.startsWith("file://")) p
    else Uri.fromFile(File(p)).toString()
}

/**
 * Wraps a finished download as a [VideoExtractor]. Direct files use their local file/SAF URI;
 * HLS downloads keep their original playlist URL and are opened through the persistent cache-only
 * Media3 data source so they continue to work without a network connection.
 */
fun DownloadedItem.toOfflineExtractor(): VideoExtractor {
    val isHlsCache = path.startsWith(DownloadHlsCache.LOCATOR_PREFIX)
    val video = Video(
        quality = null,
        videoType = if (isHlsCache) VideoType.M3U8 else (videoType ?: VideoType.CONTAINER),
        file = ani.sanin.FileUrl(playbackUri(), headers.orEmpty()),
        size = sizeBytes.takeIf { it > 0 }?.toDouble(),
    )
    return object : VideoExtractor() {
        override val server = VideoServer(DOWNLOADED_SERVER_NAME, true, null)
        override suspend fun extract(): VideoContainer = VideoContainer(listOf(video))
    }.apply { videos = listOf(video) }
}

/**
 * Rebuilds a minimal [Media] whose single episode points at the finished download, so the existing
 * player screen can open it exactly like a network episode. Marked `LOCAL` so the player keeps the
 * selected server we provide instead of loading one from the source.
 */
fun DownloadedItem.toOfflineMedia(): Media {
    val extractor = toOfflineExtractor()
    val ep = Episode(
        number = episodeNumber,
        title = episodeTitle,
        thumb = cover?.let { FileUrl(it) },
    ).apply {
        extractors = mutableListOf(extractor)
        extractorsSource = 0
        selectedExtractor = extractor.server.name
        selectedVideo = 0
    }
    val anime = Anime(
        totalEpisodes = 1,
        selectedEpisode = episodeNumber,
        episodes = mutableMapOf(episodeNumber to ep),
    )
    return Media(
        anime = anime,
        id = mediaId,
        name = mediaName,
        nameRomaji = mediaName,
        userPreferredName = mediaName,
        cover = cover,
        isAdult = false,
        format = "LOCAL",
        selected = Selected(sourceIndex = 0, server = DOWNLOADED_SERVER_NAME, video = 0),
        // Offline extras so the pause overlay renders synopsis + genre chips with no network,
        // and the wordmark points at the tiny local copy instead of a remote url.
        description = synopsis,
        genres = genres?.let { ArrayList(it) } ?: arrayListOf(),
        logoUrl = logoPath?.let { Uri.fromFile(File(it)).toString() },
    )
}