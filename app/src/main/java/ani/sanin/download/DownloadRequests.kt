package ani.sanin.download

import ani.sanin.media.Media
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor

/** Preferred quality when an episode is downloaded in bulk without an explicit quality pick. */
fun bestVideoOf(extractor: VideoExtractor): Video? =
    extractor.videos.maxByOrNull { it.quality ?: 0 } ?: extractor.videos.firstOrNull()

/**
 * Builds the queue entry for one resolved episode/server/quality and enqueues it. The video is
 * already resolved by [ani.sanin.media.anime.SelectorDialogFragment], so this only has to map the
 * Orochimaru types onto the engine's model.
 *
 * Returns the enqueued item, or null if this episode is already downloaded / already queued.
 */
fun DownloadManager.enqueue(
    media: Media,
    episode: Episode,
    extractor: VideoExtractor,
    video: Video,
    sourceName: String? = null,
): DownloadItem? {
    if (isDownloaded(media.id, episode.number)) return null
    val url = video.file.url
    if (url.isBlank()) return null

    val item = DownloadItem(
        id = "${media.id}:${episode.number}",
        mediaId = media.id,
        mediaName = media.userPreferredName.ifBlank { media.nameRomaji },
        cover = media.cover,
        episodeNumber = episode.number,
        episodeTitle = episode.title,
        sourceName = sourceName,
        serverName = extractor.server.name,
        url = url,
        headers = video.file.headers,
        videoType = video.format,
        quality = video.quality,
        fileName = "Episode ${episode.number}",
    )
    enqueue(item)
    return item
}