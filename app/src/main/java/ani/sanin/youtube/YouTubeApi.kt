package ani.sanin.youtube

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
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
    val duration: Long,
    val viewCount: Long,
    val publishedAt: String
)

object YouTubeApi {

    private const val BASE = "https://www.googleapis.com/youtube/v3"
    private const val TAG = "YouTubeApi"

    private fun key(): String = PrefManager.getVal(PrefName.YouTubeApiKey)
    private fun channelId(): String = PrefManager.getVal(PrefName.YouTubeChannelId)

    private suspend fun uploadsPlaylistId(): String = withContext(Dispatchers.IO) {
        val apiKey = key()
        val cid = channelId()
        Logger.d(TAG, "Fetching uploads for channel: $cid, key length: ${apiKey.length}")
        val url = URL("$BASE/channels?part=contentDetails&id=$cid&key=$apiKey")
        val json = request(url)
        Logger.d(TAG, "Channel response: $json")
        val playlistId = json.getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("contentDetails")
            .getJSONObject("relatedPlaylists")
            .getString("uploads")
        Logger.d(TAG, "Uploads playlist: $playlistId")
        playlistId
    }

    suspend fun fetchShorts(maxResults: Int = 50): List<YouTubeShort> = withContext(Dispatchers.IO) {
        val playlistId = uploadsPlaylistId()
        Logger.d(TAG, "Fetching shorts from playlist: $playlistId")
        val shorts = mutableListOf<YouTubeShort>()
        var pageToken: String? = null
        var remaining = maxResults * 2

        do {
            val tokenParam = pageToken?.let { "&pageToken=$it" } ?: ""
            val url = URL(
                "$BASE/playlistItems?part=snippet,contentDetails&playlistId=$playlistId" +
                    "&maxResults=50&key=${key()}$tokenParam"
            )
            Logger.d(TAG, "Fetching playlist page...")
            val json = request(url)
            val items = json.getJSONArray("items")
            Logger.d(TAG, "Got ${items.length()} items from playlist")

            val videoIds = (0 until items.length())
                .map { i -> items.getJSONObject(i).getJSONObject("contentDetails").getString("videoId") }
                .filter { it.isNotBlank() }
            Logger.d(TAG, "Video IDs: $videoIds")

            val durations = fetchDurations(videoIds)
            Logger.d(TAG, "Durations: $durations")

            (0 until items.length()).forEach { i ->
                val item = items.getJSONObject(i)
                val videoId = item.getJSONObject("contentDetails").optString("videoId", "")
                if (videoId.isBlank()) return@forEach
                val snippet = item.getJSONObject("snippet")
                val second = durations[videoId] ?: 0L
                Logger.d(TAG, "Video $videoId duration=${second}s")
                if (second in 1..60) {
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
            Logger.d(TAG, "Next page token: $pageToken")
            remaining -= videoIds.size
        } while (!pageToken.isNullOrBlank() && remaining > 0)

        Logger.d(TAG, "Total shorts found: ${shorts.size}")
        shorts
    }

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
                Logger.d(TAG, "Fetching durations for ${chunk.size} videos")
                val json = request(url)
                (0 until json.getJSONArray("items").length()).forEach { i ->
                    val item = json.getJSONArray("items").getJSONObject(i)
                    val id = item.optString("id", "")
                    val iso = item.getJSONObject("contentDetails").optString("duration", "PT0S")
                    result[id] = parseIsoDuration(iso)
                }
            } catch (e: Exception) {
                Logger.d(TAG, "Duration fetch failed: ${e.message}")
            }
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
        Logger.d(TAG, "Request: $url")
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        Logger.d(TAG, "Response code: $code")
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"
        conn.disconnect()
        if (code !in 200..299) {
            Logger.d(TAG, "API Error: $text")
            throw Exception("YouTube API error $code")
        }
        return JSONObject(text)
    }
}
