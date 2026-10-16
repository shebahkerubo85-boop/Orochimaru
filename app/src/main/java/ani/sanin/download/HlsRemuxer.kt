package ani.sanin.download

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * Compatibility entry point for the queue engine. Media3 downloads HLS into a persistent cache
 * that ExoPlayer can play offline; no remuxing or FFmpeg is involved.
 */
@UnstableApi
internal object HlsRemuxer {
    suspend fun download(
        context: Context,
        item: DownloadItem,
        client: okhttp3.OkHttpClient,
        onProgress: (downloaded: Long, total: Long, fraction: Float) -> Unit,
        isActive: () -> Boolean,
    ) = DownloadHlsCache.download(context, item, client, onProgress, isActive)
}
