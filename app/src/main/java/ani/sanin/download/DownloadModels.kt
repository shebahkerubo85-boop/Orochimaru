package ani.sanin.download

import ani.sanin.parsers.VideoType
import java.io.Serializable

/**
 * Queue / progress state for a single episode download.
 *
 * The item is fully self-contained: the video url, headers and container type are resolved by
 * the caller from a [ani.sanin.parsers.VideoExtractor] before enqueue, so the engine never has
 * to touch a source or an extension while it is running.
 */
data class DownloadItem(
    val id: String,
    val mediaId: Int,
    val mediaName: String,
    val cover: String? = null,
    val thumbnail: String? = null,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val sourceName: String? = null,
    val serverName: String? = null,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val videoType: VideoType = VideoType.CONTAINER,
    val quality: Int? = null,
    val fileName: String,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Float = 0f,
    var downloadedBytes: Long = 0L,
    var totalBytes: Long = 0L,
    var speed: Long = 0L,
    var error: String? = null,
    // Offline extras captured while preparing (paused-overlay synopsis/chips + small local art).
    var synopsis: String? = null,
    var genres: List<String>? = null,
    var thumbPath: String? = null,
    var logoPath: String? = null,
) : Serializable {

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.PREPARING ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.RETRYING
}

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    RETRYING,
    PAUSED,
    FINISHED,
    ERROR,
}

/**
 * A file that finished downloading and now lives on disk (or behind a SAF uri).
 */
data class DownloadedItem(
    val id: String,
    val mediaId: Int,
    val mediaName: String,
    val cover: String? = null,
    val thumbnail: String? = null,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val path: String,
    val sizeBytes: Long = 0L,
    val timestamp: Long = 0L,
    val synopsis: String? = null,
    val genres: List<String>? = null,
    val thumbPath: String? = null,
    val logoPath: String? = null,
) : Serializable

/** Where the bytes are actually written. Chosen in Settings → Downloads. */
enum class DownloadStorage {
    /** App-private external files dir. Always writable, removed on uninstall. */
    APP,

    /** A user picked folder, written through the Storage Access Framework. */
    SHARED,
}