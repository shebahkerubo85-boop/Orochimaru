package ani.sanin.download

import ani.sanin.App
import ani.sanin.media.Media
import ani.sanin.media.anime.Episode
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoExtractor
import ani.sanin.util.BatteryOptimizations

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

    // video.size is reported in MB; keep it as an estimate so the queue can show a total
    // even when the server does not send a Content-Length.
    val estimatedSize = video.size?.takeIf { it > 0.0 }?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L

    val item = DownloadItem(
        id = "${media.id}:${episode.number}",
        mediaId = media.id,
        mediaName = media.userPreferredName.ifBlank { media.nameRomaji },
        cover = media.cover,
        thumbnail = episode.thumb?.url?.takeIf { it.isNotBlank() }
            ?: media.banner?.takeIf { it.isNotBlank() }
            ?: media.cover,
        episodeNumber = episode.number,
        episodeTitle = episode.title,
        sourceName = sourceName,
        serverName = extractor.server.name,
        url = url,
        headers = video.file.headers,
        videoType = video.format,
        quality = video.quality,
        fileName = "Episode ${episode.number}",
        totalBytes = estimatedSize,
    )
    enqueue(item)
    // One-time battery exemption prompt, so the OS does not kill the queue (or its foreground
    // service) under OEM power management. Only ever fires on the first item ever queued.
    App.currentActivity()?.let { BatteryOptimizations.promptIfNeeded(it) }
    return item
}