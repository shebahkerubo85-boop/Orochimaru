package ani.sanin.download

import android.content.Context
import ani.sanin.connections.LogoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Offline extras the player and the download screens need on downloaded episodes: synopsis,
 * genres, and small "display-quality" artwork (a thumbnail and the title's wordmark) written to
 * disk so the pause overlay and the cards keep working with no network.
 *
 * Runs once per item during its PREPARING phase. Every step is soft — a failed artwork resolves
 * to nothing and the actual download goes on untouched.
 */
object DownloadMetadata {

    /** Safety cap. These are thumbnails/wordmarks, not full-res posters. */
    private const val MAX_META_BYTES = 1_048_576L

    /** Neutral extension — Glide sniffs the real format from the bytes. */
    private const val IMAGE_EXT = "img"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun metaDir(context: Context): File =
        File(context.filesDir, "download_meta").apply { mkdirs() }

    private fun fileFor(context: Context, name: String): File =
        File(metaDir(context), "$name.$IMAGE_EXT")

    /**
     * Fills the item's offline fields. Synopsis and genres are already captured on the item at
     * enqueue time; the remaining work is resolving the wordmark and pulling the small artwork.
     */
    suspend fun prepare(context: Context, item: DownloadItem) {
        withContext(Dispatchers.IO) {
            if (item.logoPath == null) {
                val logoUrl = try {
                    LogoApi.getLogoUrl(item.mediaId)
                } catch (_: Exception) {
                    null
                }
                item.logoPath = downloadImage(context, logoUrl, "logo_${item.mediaId}")
            }
            if (item.thumbPath == null) {
                item.thumbPath = downloadImage(
                    context,
                    item.thumbnail,
                    "thumb_${item.mediaId}_${item.episodeNumber}",
                )
            }
        }
    }

    private fun downloadImage(context: Context, url: String?, name: String): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_META_BYTES) return@use null
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_META_BYTES) return@use null
                val out = fileFor(context, name)
                out.writeBytes(bytes)
                out.absolutePath
            }
        }.getOrNull()
    }
}