package ani.sanin.youtube

import android.util.Log
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
    val duration: Long,
    val viewCount: Long,
    val publishedAt: String
)

data class YouTubeComment(
    val authorName: String,
    val authorThumb: String,
    val text: String,
    val likeCount: Int
)

object YouTubeApi {

    private const val BASE = "https://www.googleapis.com/youtube/v3"
    private const val TAG = "YouTubeApi"

    private fun key(): String = PrefManager.getVal(PrefName.YouTubeApiKey)
    private fun channelId(): String = PrefManager.getVal(PrefName.YouTubeChannelId)

    private suspend fun uploadsPlaylistId(): String = withContext(Dispatchers.IO) {
        val url = URL("$BASE/channels?part=contentDetails&id=${channelId()}&key=${key()}")
        val json = request(url)
        json.getJSONArray("items")
            .getJSONObject(0)
            .getJSONObject("contentDetails")
            .getJSONObject("relatedPlaylists")
            .getString("uploads")
    }

    suspend fun fetchYouTubeShorts(): List<YouTubeShort> = withContext(Dispatchers.IO) {
        val playlistId = uploadsPlaylistId()
        val shorts = mutableListOf<YouTubeShort>()
        var pageToken: String? = null

        do {
            val tokenParam = pageToken?.let { "&pageToken=$it" } ?: ""
            val url = URL(
                "$BASE/playlistItems?part=snippet,contentDetails&playlistId=$playlistId" +
                    "&maxResults=50&key=${key()}$tokenParam"
            )
            val json = request(url)
            val items = json.getJSONArray("items")

            val videoIds = (0 until items.length())
                .map { items.getJSONObject(it).getJSONObject("contentDetails").getString("videoId") }
                .filter { it.isNotBlank() }
            val durations = fetchDurations(videoIds)

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val videoId = item.getJSONObject("contentDetails").optString("videoId", "")
                if (videoId.isBlank()) continue
                val snippet = item.getJSONObject("snippet")
                val sec = durations[videoId] ?: 0L
                if (sec in 1..180) {
                    val thumb = snippet.getJSONObject("thumbnails")
                    val thumbUrl = thumb.optJSONObject("maxres")?.getString("url")
                        ?: thumb.optJSONObject("standard")?.getString("url")
                        ?: thumb.optJSONObject("high")?.getString("url")
                        ?: thumb.optJSONObject("medium")?.getString("url")
                        ?: ""
                    shorts.add(YouTubeShort(
                        id = videoId, title = snippet.optString("title", "Untitled"),
                        thumbnailUrl = thumbUrl, duration = sec, viewCount = 0,
                        publishedAt = snippet.optString("publishedAt", "")
                    ))
                }
            }
            pageToken = json.optString("nextPageToken", "").ifEmpty { null }
        } while (pageToken != null)

        shorts
    }

    suspend fun fetchComments(videoId: String, maxResults: Int = 20): List<YouTubeComment> = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "$BASE/commentThreads?part=snippet&videoId=$videoId" +
                    "&maxResults=$maxResults&order=relevance&key=${key()}"
            )
            val json = request(url)
            val items = json.getJSONArray("items")
            (0 until items.length()).map { i ->
                val snippet = items.getJSONObject(i).getJSONObject("snippet")
                    .getJSONObject("topLevelComment").getJSONObject("snippet")
                YouTubeComment(
                    authorName = snippet.optString("authorDisplayName", ""),
                    authorThumb = snippet.optString("authorProfileImageUrl", ""),
                    text = snippet.optString("textDisplay", ""),
                    likeCount = snippet.optInt("likeCount", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun fetchDurations(ids: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, Long>()
        ids.chunked(50).forEach { chunk ->
            try {
                val json = request(URL(
                    "$BASE/videos?part=contentDetails&id=${chunk.joinToString(",")}&key=${key()}"
                ))
                for (i in 0 until json.getJSONArray("items").length()) {
                    val item = json.getJSONArray("items").getJSONObject(i)
                    result[item.optString("id")] = parseIsoDuration(
                        item.getJSONObject("contentDetails").optString("duration", "PT0S")
                    )
                }
            } catch (_: Exception) {}
        }
        result
    }

    private fun parseIsoDuration(iso: String): Long {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(iso) ?: return 0
        return (m.groupValues[1].toLongOrNull() ?: 0) * 3600 +
            (m.groupValues[2].toLongOrNull() ?: 0) * 60 +
            (m.groupValues[3].toLongOrNull() ?: 0)
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
        if (code !in 200..299) throw Exception("API error $code")
        return JSONObject(text)
    }
}
