package ani.sanin.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class RedditPost(
    val id: String,
    val title: String,
    val thumbnail: String,
    val videoUrl: String?,
    val videoGif: String?,
    val image: String?,
    val permalink: String
)

object RedditApi {

    private const val TAG = "RedditApi"
    private const val SUBREDDIT = "Animeedits"
    private const val USER_AGENT = "android:ani.sanin:v1.0 (by /u/anphex)"

    suspend fun fetchPosts(limit: Int = 50): List<RedditPost> = withContext(Dispatchers.IO) {
        val posts = mutableListOf<RedditPost>()
        try {
            val url = URL("https://www.reddit.com/r/$SUBREDDIT/hot.json?limit=$limit&raw_json=1")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val code = conn.responseCode
            Log.d(TAG, "Reddit response: $code")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: "{}"
            conn.disconnect()

            if (code !in 200..299) {
                Log.d(TAG, "Reddit error: ${text.take(300)}")
                return@withContext posts
            }

            val json = JSONObject(text)
            val children = json.getJSONObject("data").getJSONArray("children")
            Log.d(TAG, "Reddit posts: ${children.length()}")

            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                val isVideo = post.optBoolean("is_video", false)
                val hint = post.optString("post_hint", "")
                val url0 = post.optString("url", "")

                var videoUrl: String? = null
                var videoGif: String? = null
                var image: String? = null

                if (isVideo) {
                    val media = post.optJSONObject("media")
                    val redditVideo = media?.optJSONObject("reddit_video")
                    videoUrl = redditVideo?.optString("fallback_url")?.let { cleanRedditUrl(it) }
                    if (redditVideo != null) {
                        if (redditVideo.optBoolean("is_gif", false)) {
                            videoGif = redditVideo.optString("fallback_url")?.let { cleanRedditUrl(it) }
                        }
                    }
                } else if (hint == "hosted:video" || hint == "rich:video") {
                    val media = post.optJSONObject("media")
                    val redditVideo = media?.optJSONObject("reddit_video")
                    videoUrl = redditVideo?.optString("fallback_url")?.let { cleanRedditUrl(it) }
                } else if (hint == "image" || url0.matches(Regex(""".*\.(jpg|jpeg|png|gif|webp)(\?.*)?$""", RegexOption.IGNORE_CASE))) {
                    image = url0
                }

                // Fall back to a thumbnail from preview images if present
                var thumb = post.optString("thumbnail", "")
                if (!thumb.startsWith("http")) {
                    try {
                        val preview = post.optJSONObject("preview")
                        val images = preview?.optJSONArray("images")
                        val first = images?.getJSONObject(0)
                        val src = first?.optJSONObject("source")
                        thumb = src?.optString("url", "") ?: ""
                    } catch (_: Exception) {}
                }

                val permalink = post.optString("permalink", "")

                posts.add(
                    RedditPost(
                        id = post.optString("id", ""),
                        title = post.optString("title", "Untitled"),
                        thumbnail = if (thumb.startsWith("http")) thumb else "",
                        videoUrl = videoUrl,
                        videoGif = videoGif,
                        image = image,
                        permalink = permalink
                    )
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reddit fetch failed: ${e.message}")
        }
        Log.d(TAG, "Reddit parsed ${posts.size} posts, ${posts.count { it.videoUrl != null }} videos")
        posts
    }

    /** Reddit fallback URLs have query params that break ExoPlayer — strip them. */
    private fun cleanRedditUrl(raw: String): String {
        val clean = raw.substringBefore("?")
        return clean.replace("DASH_720", "DASH_540")
            .replace("DASH_480", "DASH_360")
            .ifEmpty { raw.substringBefore("?") }
    }
}
