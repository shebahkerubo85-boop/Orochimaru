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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
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
 *  - HLS playlists are downloaded with Media3's native HlsDownloader into a persistent
 *    ExoPlayer cache. Playback uses that same cache offline; no remuxing or native FFmpeg is used.
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
        try {
            when (item.videoType) {
                VideoType.M3U8, VideoType.DASH -> downloadHls(item, onProgress, isActive)
                else -> downloadDirect(item, temp, onProgress, isActive)
            }
            Log.i(TAG, "download finished url=${item.url} size=${temp.length()}")
            return temp
        } catch (error: Throwable) {
            // Direct-file partials are retained for a verified Range resume. Segmented downloads
            // remove their own parts on failure; invalid resume responses delete the stale temp.
            throw error
        }
    }

    private suspend fun downloadDirect(
        item: DownloadItem,
        temp: File,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val existingBytes = temp.takeIf { it.exists() }?.length() ?: 0L
        val probedTotal = resolveLength(item)
        val total = probedTotal.takeIf { it in 1 until MAX_DOWNLOAD_BYTES } ?: 0L
        val segments = PrefManager.getVal<Int>(PrefName.DownloadSegments).coerceIn(1, 8)

        // Segmented files aren't resumable without a manifest. Only use them on a clean temp
        // and after the server confirms the exact byte-range protocol.
        if (existingBytes == 0L && total >= MIN_SEGMENT_SIZE && segments > 1 && isActive() &&
            supportsRanges(item, total)
        ) {
            Log.i(TAG, "trying $segments verified range segments for ${item.id}, total=$total")
            try {
                downloadSegments(item, temp, total, segments, onProgress, isActive)
                return@withContext
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "segmented download failed; restarting as a single stream: ${e.message}")
                temp.delete()
                for (part in 0 until 8) File("${temp.path}.part$part").delete()
            }
        }
        downloadSingle(item, temp, total, onProgress, isActive)
    }

    /** Resume a partial file only when the origin confirms the requested byte offset. */
    private suspend fun downloadSingle(
        item: DownloadItem,
        temp: File,
        knownTotal: Long,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        var offset = temp.takeIf { it.exists() }?.length() ?: 0L
        val builder = Request.Builder().url(item.url).apply {
            item.headers.forEach { (k, v) -> addHeader(k, v) }
        }
        if (offset > 0L) builder.header("Range", "bytes=$offset-")
        val call = client.newCall(builder.build())
        val job = currentCoroutineContext().job
        val cancellation = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { res ->
                val range = parseContentRange(res.header("Content-Range"))
                if (offset > 0L && res.code == 206) {
                    if (range == null || range.first != offset ||
                        (knownTotal > 0L && range.total != knownTotal)
                    ) {
                        temp.delete()
                        throw DownloadException("Invalid resume Content-Range for byte $offset")
                    }
                } else if (res.code == 200) {
                    // Server ignored Range (or this is a fresh request): truncate, never append.
                    offset = 0L
                } else if (!res.isSuccessful) {
                    throw DownloadException("HTTP ${res.code} for ${item.url}")
                } else if (offset > 0L && res.code != 206) {
                    temp.delete()
                    throw DownloadException("Server did not honour resume Range (HTTP ${res.code})")
                } else if (res.code == 206 && range == null) {
                    throw DownloadException("Invalid Content-Range on partial response")
                }

                val body = res.body ?: throw DownloadException("Empty response body")
                val actualTotal = range?.total?.takeIf { it > 0L }
                    ?: knownTotal.takeIf { it > 0L }
                    ?: body.contentLength().takeIf { it > 0L }?.let { it + offset }
                    ?: 0L
                body.byteStream().use { input ->
                    temp.parentFile?.mkdirs()
                    java.io.FileOutputStream(temp, offset > 0L).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = offset
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!isActive()) throw DownloadCancelledException()
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, actualTotal, -1f)
                        }
                        output.fd.sync()
                    }
                }
                val finalLength = temp.length()
                if (actualTotal > 0L && finalLength != actualTotal) {
                    throw DownloadException("Incomplete download: $finalLength of $actualTotal bytes")
                }
                if (finalLength <= 0L) throw DownloadException("Downloaded file is empty")
            }
        } finally {
            cancellation.dispose()
        }
    }

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
                    val call = client.newCall(rangeRequest)
                    currentCoroutineContext().job.invokeOnCompletion { cause ->
                        if (cause is CancellationException) call.cancel()
                    }
                    call.execute().use { res ->
                        val range = parseContentRange(res.header("Content-Range"))
                        if (res.code != 206 || range == null || range.first != start ||
                            range.last != end || range.total != total
                        ) {
                            throw DownloadException(
                                "Invalid range response: HTTP ${res.code}, " +
                                    "Content-Range=${res.header("Content-Range")}",
                            )
                        }
                        val rangeBody = res.body
                            ?: throw DownloadException("Empty range response body")
                        if (rangeBody.contentLength() >= 0L &&
                            rangeBody.contentLength() != end - start + 1L
                        ) throw DownloadException("Wrong range body length for segment $i")
                        rangeBody.byteStream().use { input ->
                            RandomAccessFile(part, "rw").use { raf ->
                                raf.setLength(0L)
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
            jobs.joinAll()
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
        if (temp.length() != total) {
            val actual = temp.length()
            temp.delete()
            throw DownloadException("Merged file length $actual != expected $total")
        }
        onProgress(total, total, -1f)
        Log.i(TAG, "segmented download done: $segments ranges -> ${temp.length()} bytes")
    }

    /**
     * Best-effort size probe (HEAD, then a 1-byte range GET) used only when the actual download
     * response has no Content-Length. Returns -1 when the size genuinely cannot be determined.
     */
    private data class ParsedRange(val first: Long, val last: Long, val total: Long)

    private fun parseContentRange(value: String?): ParsedRange? {
        val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (first < 0L || last < first || total <= last) return null
        return ParsedRange(first, last, total)
    }

    /** Trust only a successful HEAD length or a valid one-byte 206 probe. */
    private fun resolveLength(item: DownloadItem): Long {
        val head = probeLength(item, head = true)
        if (head > 0L) return head
        return probeLength(item, head = false)
    }

    private fun probeLength(item: DownloadItem, head: Boolean): Long = try {
        val builder = Request.Builder().url(item.url)
        item.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (head) builder.head() else builder.get().header("Range", "bytes=0-0")
        client.newCall(builder.build()).execute().use { res ->
            if (head) {
                if (!res.isSuccessful) return -1L
                res.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
            } else {
                val range = parseContentRange(res.header("Content-Range"))
                if (res.code == 206 && range != null && range.first == 0L &&
                    range.last == 0L
                ) range.total else -1L
            }
        }
    } catch (_: Exception) {
        -1L
    }

    private fun supportsRanges(item: DownloadItem, total: Long): Boolean = try {
        val builder = Request.Builder().url(item.url).get().header("Range", "bytes=0-0")
        item.headers.forEach { (k, v) -> builder.addHeader(k, v) }
        client.newCall(builder.build()).execute().use { res ->
            val range = parseContentRange(res.header("Content-Range"))
            res.code == 206 && range != null && range.first == 0L &&
                range.last == 0L && range.total == total &&
                (res.body?.contentLength() ?: -1L).let { it == -1L || it == 1L }
        }
    } catch (_: Exception) {
        false
    }

    private suspend fun downloadHls(
        item: DownloadItem,
        onProgress: (Long, Long, Float) -> Unit,
        isActive: () -> Boolean,
    ) = HlsRemuxer.download(context, item, client, onProgress, isActive)

    companion object {
        private const val TAG = "AnimeDownload"
        private const val MIN_SEGMENT_SIZE = 1024L * 1024L
        private const val MAX_DOWNLOAD_BYTES = 21474836480L
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

        fun cleanup(context: Context, item: DownloadItem) {
            val temp = DownloadStorageHelper.tempFile(context, item)
            temp.delete()
            for (part in 0 until 8) File("${temp.path}.part$part").delete()
        }

        fun defaultClient(): OkHttpClient {
            // OkHttp defaults to five concurrent requests per host. That silently caps a user
            // selecting 6–8 segments, so align the per-host dispatcher with the segment ceiling.
            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 8
            }
            return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        }
    }
}