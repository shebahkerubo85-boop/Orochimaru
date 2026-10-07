package ani.sanin.download

import android.content.Context
import ani.sanin.parsers.VideoType
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadException(message: String) : Exception(message)
class DownloadCancelledException : Exception("cancelled")

/**
 * Turns a resolved [DownloadItem] into a finished temp file.
 *
 *  - Direct files (mp4, mkv, ...) are streamed with OkHttp and progress is exact.
 *  - HLS playlists are remuxed with ffmpeg (selected because it is the only reliable way to
 *    stitch a live/segmented playlist into a single playable file). Proxy servers are not
 *    involved; ffmpeg follows the playlist itself.
 */
class Downloader(
    private val context: android.content.Context,
    private val client: OkHttpClient,
) {

    suspend fun download(
        item: DownloadItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isActive: () -> Boolean,
    ): File {
        val temp = DownloadStorageHelper.tempFile(context, item)
        when (item.videoType) {
            VideoType.M3U8 -> downloadHls(item, temp, onProgress, isActive)
            else -> downloadDirect(item, temp, onProgress, isActive)
        }
        return temp
    }

    private suspend fun downloadDirect(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(item.url)
            .apply { item.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val response = client.newCall(request).execute()
        response.use { res ->
            if (!res.isSuccessful) throw DownloadException("HTTP ${res.code} for ${item.url}")
            val body = res.body ?: throw DownloadException("Empty response body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        if (!isActive()) {
                            temp.delete()
                            throw DownloadCancelledException()
                        }
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        }
    }

    private suspend fun downloadHls(
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

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}