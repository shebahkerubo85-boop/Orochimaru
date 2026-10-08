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
    val p = path
    return if (p.startsWith("content://") || p.startsWith("file://")) p
    else Uri.fromFile(File(p)).toString()
}

/**
 * Wraps a finished download as a [VideoExtractor] so the existing player pipeline can open the
 * local file exactly like any other source (the player already knows how to read `content://` and
 * `file://`). The single video is a [VideoType.CONTAINER]; HLS downloads were remuxed to mkv.
 */
fun DownloadedItem.toOfflineExtractor(): VideoExtractor {
    val video = Video(
        quality = null,
        videoType = VideoType.CONTAINER,
        url = playbackUri(),
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
    )
}