package ani.sanin.download

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phone-only HLS remuxer backed by ffmpeg-kit (FFmpeg 6.0).
 *
 * The playlist is stitched/remuxed with `-c copy` (no transcoding) so downloads are bound only
 * by network speed and the resulting file is a single playable container. ffmpeg follows the
 * playlist itself, so no proxy is involved.
 */
internal object HlsRemuxer {

    suspend fun remux(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long) -> Unit,
        isActive: () -> Boolean,
    ) {
        FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)

        val headerString = item.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val args = mutableListOf<String>()
        args += "-y"
        if (headerString.isNotBlank()) {
            args += "-headers"
            args += headerString
        }
        args += "-i"
        args += item.url
        args += "-c"
        args += "copy"
        args += temp.absolutePath

        val logCallback = LogCallback { /* intentionally quiet */ }
        val statCallback = StatisticsCallback { s ->
            if (s.size > 0L) onProgress(s.size.toLong(), 0L)
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { ffmpegSession ->
                    if (!continuation.isActive) return@executeWithArgumentsAsync
                    if (ffmpegSession.returnCode?.isValueSuccess == true) {
                        continuation.resume(Unit)
                    } else {
                        val reason = ffmpegSession.failStackTrace?.takeIf { it.isNotBlank() }
                            ?: ffmpegSession.allLogsAsString?.trim()?.takeLast(500)
                            ?: "unknown error"
                        continuation.resumeWithException(
                            DownloadException("ffmpeg failed (${ffmpegSession.returnCode}): $reason"),
                        )
                    }
                },
                logCallback,
                statCallback,
            )
            continuation.invokeOnCancellation { session.cancel() }
            if (!isActive()) {
                session.cancel()
                continuation.cancel()
            }
        }
    }
}