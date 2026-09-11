package ani.sanin.youtube

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class YouTubeShort(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val duration: Long,       // seconds
    val viewCount: Long,
    val publishedAt: String
)

/**
 * Minimal YouTube Data API v3 client for the channel's Shorts.
 * Uses sync HttpURLConnection inside coroutines (Dispatchers.IO).
 */
object YouTubeApi {

    private const val BASE = "https://www.googleapis.com/youtube/v3"

    private fun key(): String = PrefManager.getVal(PrefName.YouTubeApiKey)
    private fun channelId(): String = PrefManager.getVal(PrefName.YouTubeChannelId)

    /** Fetch the uploads playlist id for the configured channel. */
    private suspend fun uploadsPlaylistId(): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE/channels?part=contentDetails&id=${channelId()}&key=${key()}")
        val json = request(url)
        json.getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("contentDetails")
            .getJSONObject("relatedPlaylists")
            .getString("uploads")
    }

    /** Fetch all videos from a playlist (paginated), filtering <= 60s as Shorts. */
    suspend fun fetchShorts(maxResults: Int = 50): List<YouTubeShort> = withContext(Dispatchers.IO) {
        val playlistId = uploadsPlaylistId()
        val shorts = mutableListOf<YouTubeShort>()
        var pageToken: String? = null
        var remaining = maxResults * 2 // fetch extra to account for non-shorts

        do {
            val tokenParam = pageToken?.let { "&pageToken=$it" } ?: ""
            val url = URL(
                "$BASE/playlistItems?part=snippet,contentDetails&playlistId=$playlistId" +
                    "&maxResults=50&key=${key()}$tokenParam"
            )
            val json = request(url)

            // Batch-fetch durations for the items
            val videoIds = (0 until json.getJSONArray("items").length())
                .map { i -> json.getJSONArray("items").getJSONObject(i).getJSONObject("contentDetails").getString("videoId") }
                .filter { it.isNotBlank() }
            val durations = fetchDurations(videoIds)

            (0 until json.getJSONArray("items").length()).forEach { i ->
                val item = json.getJSONArray("items").getJSONObject(i)
                val videoId = item.getJSONObject("contentDetails").optString("videoId", "")
                if (videoId.isBlank()) return@forEach
                val snippet = item.getJSONObject("snippet")
                val second = durations[videoId] ?: 0L
                if (second in 1..60) { // Shorts = duration <= 60s
                    val thumb = snippet.getJSONObject("thumbnails")
                    val thumbUrl = thumb.optJSONObject("high")?.getString("url")
                        ?: thumb.optJSONObject("medium")?.getString("url")
                        ?: thumb.optJSONObject("default")?.getString("url")
                        ?: ""
                    shorts.add(
                        YouTubeShort(
                            id = videoId,
                            title = snippet.optString("title", "Untitled"),
                            thumbnailUrl = thumbUrl,
                            duration = second,
                            viewCount = 0,
                            publishedAt = snippet.optString("publishedAt", "")
                        )
                    )
                }
                if (shorts.size >= maxResults) return@withContext shorts
            }

            pageToken = json.optString("nextPageToken", null ?: "")
            remaining -= videoIds.size
        } while (!pageToken.isNullOrBlank() && remaining > 0)

        shorts
    }

    /** Fetch video duration + view counts in one shot. */
    private suspend fun fetchDurations(ids: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val chunked = ids.chunked(50)
        val result = mutableMapOf<String, Long>()
        chunked.forEach { chunk ->
            val idParam = chunk.joinToString(",")
            val url = URL(
                "$BASE/videos?part=contentDetails,statistics&id=$idParam&key=${key()}"
            )
            try {
                val json = request(url)
                (0 until json.getJSONArray("items").length()).forEach { i ->
                    val item = json.getJSONArray("items").getJSONObject(i)
                    val id = item.optString("id", "")
                    val iso = item.getJSONObject("contentDetails").optString("duration", "PT0S")
                    result[id] = parseIsoDuration(iso)
                }
            } catch (_: Exception) { }
        }
        result
    }

    private fun parseIsoDuration(iso: String): Long {
        val regex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
        val m = regex.find(iso) ?: return 0L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toLongOrNull() ?: 0L
        return h * 3600 + min * 60 + s
    }

    private fun request(url: URL): JSONObject {
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"
        conn.disconnect()
        val json = JSONObject(text)
        if (code !in 200..299) {
            throw Exception("YouTube API error $code: ${json.optString("error")}")
        }
        return json
    }
}
