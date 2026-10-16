package ani.sanin.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Durable, non-evicting cache for downloaded HLS episodes. It is separate from VideoCache, the
 * small LRU cache for online playback, and remains on disk until an episode is deleted.
 */
@UnstableApi
object DownloadHlsCache {
    const val LOCATOR_PREFIX = "sanin-hls-cache://"
    private const val TAG = "DownloadHlsCache"
    private const val RENDITION_INDEX = 0

    @Volatile private var cache: SimpleCache? = null
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "sanin-hls-download").apply { isDaemon = true }
    }

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        val directory = File(DownloadStorageHelper.appRoot(context), ".hls-cache")
        if (!directory.exists() && !directory.mkdirs()) {
            throw DownloadException("Could not create HLS download cache")
        }
        return SimpleCache(
            directory,
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    fun locator(id: String): String = LOCATOR_PREFIX + Uri.encode(id)

    private fun mediaItem(url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            // Download only one rendition, rather than every resolution in a master playlist.
            .setStreamKeys(
                listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, RENDITION_INDEX)),
            )
            .build()

    fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        offlineOnly: Boolean,
    ): CacheDataSource.Factory {
        val factory = CacheDataSource.Factory().setCache(getCache(context))
        if (!offlineOnly) {
            val httpFactory = OkHttpDataSource.Factory(Downloader.defaultClient())
                .setDefaultRequestProperties(headers)
            factory.setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        }
        return factory
    }

    fun offlineDataSourceFactory(context: Context): CacheDataSource.Factory =
        dataSourceFactory(context, emptyMap(), offlineOnly = true)

    suspend fun download(
        context: Context,
        item: DownloadItem,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        if (!isActive()) throw DownloadCancelledException()
        // If the URL is a master playlist, remember the first variant's media playlist URL.
        // ExoPlayer can then play exactly the cached rendition without trying uncached qualities.
        item.offlinePlaybackUrl = resolvePlaybackUrl(item)
        val cacheFactory = dataSourceFactory(context, item.headers, offlineOnly = false)
        val downloader = HlsDownloader.Factory(cacheFactory)
            .setExecutor(executor)
            .create(mediaItem(item.url))
        val job = currentCoroutineContext().job
        val cancellation = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) downloader.cancel()
        }
        try {
            runInterruptible(Dispatchers.IO) {
                downloader.download { contentLength, bytesDownloaded, percentDownloaded ->
                    val fraction = if (percentDownloaded.isFinite() && percentDownloaded >= 0f) {
                        (percentDownloaded / 100f).coerceIn(0f, 1f)
                    } else {
                        -1f
                    }
                    onProgress(bytesDownloaded, contentLength.coerceAtLeast(0L), fraction)
                }
            }
            if (!isActive()) throw DownloadCancelledException()
            Log.i(TAG, "HLS cached: id=${item.id} url=${item.url}")
        } finally {
            cancellation.dispose()
        }
    }

    private suspend fun resolvePlaybackUrl(item: DownloadItem): String =
        runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(item.url).apply {
                item.headers.forEach { (name, value) -> addHeader(name, value) }
            }.build()
            Downloader.defaultClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadException("HTTP ${response.code} while resolving HLS playlist")
                }
                val playlist = response.body?.string()
                    ?: throw DownloadException("Empty HLS playlist response")
                val finalUrl = response.request.url.toString()
                val lines = playlist.lineSequence().map(String::trim).toList()
                val streamInfo = lines.indexOfFirst {
                    it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
                }
                val variant = if (streamInfo >= 0) {
                    lines.drop(streamInfo + 1).firstOrNull {
                        it.isNotBlank() && !it.startsWith("#")
                    }
                } else {
                    null
                }
                if (variant.isNullOrBlank()) item.url
                else URI(finalUrl).resolve(variant).toString()
            }
        }

    /** Remove the cached manifest graph and all its segments when the user deletes the episode. */
    suspend fun remove(context: Context, url: String, headers: Map<String, String>) {
        val cacheFactory = dataSourceFactory(context, headers, offlineOnly = false)
        val downloader = HlsDownloader.Factory(cacheFactory)
            .setExecutor(executor)
            .create(mediaItem(url))
        runInterruptible(Dispatchers.IO) { downloader.remove() }
        Log.i(TAG, "HLS cache removed: $url")
    }
}
