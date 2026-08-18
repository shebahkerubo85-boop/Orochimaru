package ani.sanin.media

import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SubDubFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Returns Pair(subCount, dubCount) for an anime from reanime.to API.
     * Only works for anime (not manga).
     */
    suspend fun fetchSubDubCounts(anilistId: Int, episode: Int = 1): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://reanime.to/api/flix/$anilistId/$episode"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Pair(0, 0)

                val body = response.body?.string() ?: return@withContext Pair(0, 0)
                val json = org.json.JSONObject(body)

                val servers = json.optJSONArray("servers") ?: return@withContext Pair(0, 0)
                var subCount = 0
                var dubCount = 0

                for (i in 0 until servers.length()) {
                    val server = servers.optJSONObject(i) ?: continue
                    val dataType = server.optString("dataType", "")
                    when (dataType) {
                        "sub", "s-sub" -> subCount++
                        "dub", "s-dub" -> dubCount++
                    }
                }

                Logger.log("SubDubFetcher: anilist=$anilistId sub=$subCount dub=$dubCount")
                Pair(subCount, dubCount)
            } catch (e: Exception) {
                Pair(0, 0)
            }
        }
    }
}
