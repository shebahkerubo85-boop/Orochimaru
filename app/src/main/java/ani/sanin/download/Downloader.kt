package ani.sanin.download

import android.content.Context
import android.util.Log
import ani.sanin.parsers.VideoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ): File {
        val temp = DownloadStorageHelper.tempFile(context, item)
        Log.i(TAG, "download ${item.videoType} url=${item.url} -> ${temp.absolutePath}")
        when (item.videoType) {
            VideoType.M3U8 -> downloadHls(item, temp, onProgress, isActive)
            else -> downloadDirect(item, temp, onProgress, isActive)
        }
        Log.i(TAG, "download finished url=${item.url} size=${temp.length()}")
        return temp
    }

    private suspend fun downloadDirect(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long, Float) -> Unit,
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
            // Chunked/streaming responses return -1 here, which used to leave the progress bar
            // stuck on the indeterminate loop. Ask the server for the length before streaming.
            var total = body.contentLength()
            if (total <= 0L) total = resolveLength(item)
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
                        onProgress(done, total, -1f)
                    }
                }
            }
        }
    }

    /**
     * Best-effort size probe (HEAD, then a 1-byte range GET) used only when the actual download
     * response has no Content-Length. Returns -1 when the size genuinely cannot be determined.
     */
    private fun resolveLength(item: DownloadItem): Long {
        val head = probeLength(item, head = true)
        if (head > 0L) return head
        return probeLength(item, head = false)
    }

    private fun probeLength(item: DownloadItem, head: Boolean): Long = try {
        val builder = Request.Builder().url(item.url)
        item.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (head) {
            builder.head()
        } else {
            builder.get().addHeader("Range", "bytes=0-0")
        }
        client.newCall(builder.build()).execute().use { res ->
            // Content-Range: bytes 0-0/12345 gives the full size on a range response.
            val fromRange = res.header("Content-Range")
                ?.substringAfter('/', "")
                ?.takeIf { it.isNotBlank() && it != "*" }
                ?.toLongOrNull()
            val fromHeader = res.header("Content-Length")?.toLongOrNull()
            val fromBody = res.body?.contentLength() ?: -1L
            maxOf(fromRange ?: -1L, fromHeader ?: -1L, fromBody)
        }
    } catch (_: Exception) {
        -1L
    }

    private suspend fun downloadHls(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = HlsRemuxer.remux(item, temp, onProgress, isActive)

    companion object {
        private const val TAG = "AnimeDownload"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}