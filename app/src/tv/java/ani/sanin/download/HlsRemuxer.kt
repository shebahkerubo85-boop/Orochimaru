package ani.sanin.download

import java.io.File

/**
 * TV build intentionally ships no ffmpeg. HLS playlists cannot be stitched here, so the remuxer
 * fails fast with a clear message; direct file downloads still work.
 */
internal object HlsRemuxer {

    suspend fun remux(
        item: DownloadItem,
        temp: File,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        throw DownloadException("HLS downloads are not supported in this build")
    }
}