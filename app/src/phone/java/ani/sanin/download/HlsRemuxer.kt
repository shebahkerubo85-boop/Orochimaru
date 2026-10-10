package ani.sanin.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phone-only HLS remuxer backed by the user-installed standalone FFmpeg executable.
 *
 * The playlist is stitched/remuxed with `-c copy` (no transcoding) so downloads are bound only by
 * network speed and the resulting file is a single playable container. FFmpeg follows the playlist
 * itself, so no proxy is involved.
 */
internal object HlsRemuxer {

    private const val TAG = "HlsRemuxer"

    private val durationRegex = Regex("^(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)")
    private val sizeRegex = Regex("""size=\s*(\d+)\s*kB""", RegexOption.IGNORE_CASE)

    suspend fun remux(
        item: DownloadItem,
        temp: File,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        val ffmpeg = FfmpegRuntime.requireBinary()
        Log.i(TAG, "remux start url=${item.url} headers=${item.headers.size} out=${temp.absolutePath}")

        val headerString = item.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val args = mutableListOf(
            ffmpeg.absolutePath,
            "-hide_banner",
            "-nostdin",
            "-loglevel",
            "info",
            "-y",
        )
        FfmpegRuntime.certificateBundle()?.let { bundle ->
            args += listOf("-ca_file", bundle.absolutePath)
        }
        if (headerString.isNotBlank()) {
            args += listOf("-headers", headerString)
        }
        args += listOf(
            "-i",
            item.url,
            "-c",
            "copy",
            "-f",
            "matroska",
            temp.absolutePath,
        )
        Log.d(TAG, "ffmpeg args: ${args.joinToString(" ")}")

        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(args).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw DownloadException("ffmpeg could not start: ${e.message}")
            }

            // The reader blocks on the pipe, so a child job destroys the process when the download
            // is paused/cancelled; that closes the pipe and lets the reader finish.
            val watchdog = launch {
                try {
                    while (true) delay(200L)
                } finally {
                    if (process.isAlive) process.destroy()
                }
            }

            var totalDurationMs = 0L
            val tailLog = StringBuilder()
            try {
                readLines(process) { line ->
                    if (tailLog.length < 4_000) {
                        tailLog.append(line).append('\n')
                    } else {
                        tailLog.delete(0, tailLog.length - 3_000)
                        tailLog.append(line).append('\n')
                    }

                    if (totalDurationMs <= 0L && line.contains("Duration:")) {
                        parseDurationMs(line.substringAfter("Duration:"))?.let {
                            totalDurationMs = it
                            Log.i(TAG, "hls duration detected: ${it}ms")
                        }
                    }

                    val size = sizeRegex.find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                        ?.times(1024L)
                    val timeIndex = line.indexOf("time=")
                    val timeMs = if (timeIndex >= 0) {
                        parseDurationMs(line.substring(timeIndex + "time=".length))
                    } else {
                        null
                    }
                    when {
                        timeMs != null && totalDurationMs > 0L -> {
                            val fraction = (timeMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            onProgress(size ?: 0L, 0L, fraction)
                        }

                        size != null -> onProgress(size, 0L, -1f)
                    }
                }

                val exit = process.waitFor()
                if (!isActive()) throw DownloadCancelledException()
                if (exit != 0) {
                    val reason = tailLog.toString().trim().takeLast(500)
                    Log.e(TAG, "ffmpeg failed ($exit): $reason")
                    throw DownloadException("ffmpeg failed ($exit): $reason")
                }
                if (!temp.isFile || temp.length() <= 0L) {
                    throw DownloadException("ffmpeg produced an empty file")
                }
                Log.i(TAG, "ffmpeg output=${temp.length()} bytes")
            } finally {
                watchdog.cancel()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun readLines(process: Process, onLine: (String) -> Unit) {
        val reader = process.inputStream.bufferedReader()
        val current = StringBuilder()
        val buffer = CharArray(2_048)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                val ch = buffer[index]
                if (ch == '\r' || ch == '\n') {
                    if (current.isNotEmpty()) {
                        onLine(current.toString())
                        current.clear()
                    }
                } else {
                    current.append(ch)
                    if (current.length > 16_384) {
                        onLine(current.takeLast(16_384))
                        current.clear()
                    }
                }
            }
        }
        if (current.isNotEmpty()) onLine(current.toString())
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
