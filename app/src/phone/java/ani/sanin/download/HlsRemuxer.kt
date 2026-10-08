package ani.sanin.download

import android.util.Log
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

    private const val TAG = "HlsRemuxer"

    private val durationRegex = Regex("^(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)")

    suspend fun remux(
        item: DownloadItem,
        temp: File,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        Log.i(TAG, "remux start url=${item.url} headers=${item.headers.size} out=${temp.absolutePath}")
        try {
            FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
        } catch (t: Throwable) {
            // Almost always a native load failure (e.g. a missing symbol in libffmpegkit.so).
            Log.e(TAG, "ffmpeg-kit failed to initialize", t)
            throw DownloadException("ffmpeg-kit failed to load: ${t.message}")
        }

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
        Log.d(TAG, "ffmpeg args: ${args.joinToString(" ")}")

        // ffmpeg prints the source duration during probing; capture it so HLS progress can be a
        // real percentage (Statistics.time is the processed timestamp, not a byte total).
        var totalDurationMs = 0L
        val logCallback = LogCallback { ffLog ->
            Log.v(TAG, ffLog.message)
            if (totalDurationMs <= 0L) {
                val msg = ffLog.message
                if (msg != null) {
                    val idx = msg.indexOf("Duration:")
                    if (idx >= 0) {
                        parseDurationMs(msg.substring(idx + "Duration:".length))?.let {
                            totalDurationMs = it
                            Log.i(TAG, "hls duration detected: ${it}ms")
                        }
                    }
                }
            }
        }
        val statCallback = StatisticsCallback { s ->
            val fraction = if (totalDurationMs > 0L) {
                (s.time.toFloat() / totalDurationMs).coerceIn(0f, 1f)
            } else {
                -1f
            }
            if (s.size > 0L || fraction > 0f) onProgress(s.size.toLong(), 0L, fraction)
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            val startedAt = System.currentTimeMillis()
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { ffmpegSession ->
                    if (!continuation.isActive) return@executeWithArgumentsAsync
                    if (ffmpegSession.returnCode?.isValueSuccess == true) {
                        Log.i(
                            TAG,
                            "ffmpeg done in ${System.currentTimeMillis() - startedAt}ms, " +
                                "output=${temp.length()} bytes",
                        )
                        continuation.resume(Unit)
                    } else {
                        val reason = ffmpegSession.failStackTrace?.takeIf { it.isNotBlank() }
                            ?: ffmpegSession.allLogsAsString?.trim()?.takeLast(500)
                            ?: "unknown error"
                        Log.e(TAG, "ffmpeg failed (${ffmpegSession.returnCode}): $reason")
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

    private fun parseDurationMs(raw: String): Long? {
        val text = raw.trim()
        if (text.startsWith("N/A")) return null
        val match = durationRegex.find(text) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toDoubleOrNull() ?: return null
        if (!seconds.isFinite()) return null
        val total = ((hours * 60 + minutes) * 60 + seconds) * 1000
        return if (total > 0) total.toLong() else null
    }
}