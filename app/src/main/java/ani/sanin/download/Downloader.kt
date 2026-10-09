package ani.sanin.download

import android.content.Context
import android.util.Log
import ani.sanin.parsers.VideoType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DownloadException(message: String) : Exception(message)
class DownloadCancelledException : Exception("cancelled")

/**
 * Turns a resolved [DownloadItem] into a finished temp file.
 *
 *  - Direct files (mp4, mkv, ...): when the server reports an authoritative size, the file is
 *    pulled over N parallel range connections (Segments per episode setting, up to 8) so one
 *    download saturates the connection the way download-managers do. Otherwise it is streamed
 *    over a single connection and progress is exact.
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
            // Only a server-provided length enables segmented (multi-connection) downloads; a
            // provider estimate must never be used to split ranges, or a wrong estimate would
            // truncate the file.
            var serverTotal = body.contentLength()
            if (serverTotal <= 0L) serverTotal = resolveLength(item)
            val total = if (serverTotal > 0L) serverTotal else item.totalBytes

            val segments = PrefManager.getVal<Int>(PrefName.DownloadSegments).coerceIn(1, 8)
            if (serverTotal > 0L && total >= MIN_SEGMENT_SIZE && segments > 1 && isActive()) {
                try {
                    downloadSegments(item, temp, total, segments, onProgress, isActive)
                    return@withContext
                } catch (e: DownloadCancelledException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "segmented download failed, falling back: ${e.message}")
                }
            }

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
     * IDM-style acceleration: splits the server-confirmed byte range into `segments` chunks and
     * pulls each one over its own Range request into a `.partN` file, then merges them in order.
     */
    private suspend fun downloadSegments(
        item: DownloadItem,
        temp: File,
        total: Long,
        segments: Int,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val chunkSize = total / segments
        val doneTotal = AtomicLong(0L)
        val remaining = AtomicInteger(segments)
        val failure = AtomicReference<Throwable?>()

        fun partFile(i: Int) = File(temp.path + ".part$i")
        fun cleanup() {
            for (i in 0 until segments) {
                partFile(i).takeIf { it.exists() }?.delete()
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = (0 until segments).map { i ->
            val start = chunkSize * i
            val end = if (i == segments - 1) total - 1 else start + chunkSize - 1
            scope.launch {
                val part = partFile(i)
                try {
                    if (!isActive()) throw DownloadCancelledException()
                    val rangeRequest = Request.Builder().url(item.url)
                        .apply { item.headers.forEach { (k, v) -> addHeader(k, v) } }
                        .header("Range", "bytes=$start-$end")
                        .build()
                    client.newCall(rangeRequest).execute().use { res ->
                        if (res.code != 206) {
                            throw DownloadException("HTTP ${res.code} for range $start-$end")
                        }
                        val rangeBody = res.body
                            ?: throw DownloadException("Empty range response body")
                        rangeBody.byteStream().use { input ->
                            RandomAccessFile(part, "rw").use { raf ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (!isActive()) throw DownloadCancelledException()
                                    raf.write(buffer, 0, read)
                                    doneTotal.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                    if (part.length() != end - start + 1) {
                        throw DownloadException(
                            "segment $i short: ${part.length()} != ${end - start + 1}",
                        )
                    }
                } catch (e: Throwable) {
                    // A pause cancels the siblings too; surface it as a pause, never a generic
                    // cancellation that would leave the item stuck in DOWNLOADING.
                    val wrapped = if (!isActive() && e !is DownloadCancelledException) {
                        DownloadCancelledException()
                    } else {
                        e
                    }
                    failure.compareAndSet(null, wrapped)
                } finally {
                    remaining.decrementAndGet()
                }
            }
        }

        try {
            while (remaining.get() > 0 && failure.get() == null) {
                if (!isActive()) jobs.forEach { it.cancel() }
                onProgress(doneTotal.get(), total, -1f)
                delay(100)
            }
        } finally {
            scope.cancel()
        }

        val err = failure.get()
        if (err != null) {
            cleanup()
            throw err
        }

        temp.outputStream().use { out ->
            for (i in 0 until segments) {
                partFile(i).inputStream().use { input -> input.copyTo(out) }
            }
        }
        cleanup()
        onProgress(total, total, -1f)
        Log.i(TAG, "segmented download done: $segments ranges -> ${temp.length()} bytes")
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
        private const val MIN_SEGMENT_SIZE = 1024L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}