package ani.sanin.subdub

import android.util.LruCache
import ani.sanin.util.Logger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight in-memory LRU cache for sub/dub episode counts.
 * Fetches on-demand from AniVault's mobile API, keyed by anime title.
 */
object SubDubCache {

    private const val BASE_URL = "https://www.anivault.co/api/mobile"

    private val cache = LruCache<String, SubDubInfo>(300)

    /**
     * Get sub/dub info for [title].
     * Returns cached data immediately, or launches a background fetch.
     * [onResult] is called on the main thread with the data (or null on error).
     */
    fun get(title: String, scope: CoroutineScope, onResult: (SubDubInfo?) -> Unit) {
        val key = title.lowercase().trim()
        if (key.isBlank()) { onResult(null); return }
        val cached = cache.get(key)
        if (cached != null) {
            onResult(cached)
            return
        }
        // Fire-and-forget background fetch
        scope.launch(Dispatchers.IO) {
            val info = fetchFromApi(key)
            if (info != null) cache.put(key, info)
            withContext(Dispatchers.Main) { onResult(info) }
        }
    }

    /**
     * Synchronous check — returns cached data or null.
     * Use when binding views where you don't want to trigger network.
     */
    fun getCached(title: String): SubDubInfo? =
        cache.get(title.lowercase().trim())

    /** Batch-fetch multiple titles (e.g. for a section). */
    fun prefetch(
        titles: List<String>,
        scope: CoroutineScope,
        onDone: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            titles.forEach { title ->
                val key = title.lowercase().trim()
                if (cache.get(key) == null) {
                    val info = fetchFromApi(key)
                    if (info != null) cache.put(key, info)
                }
            }
            onDone?.let { withContext(Dispatchers.Main) { it() } }
        }
    }

    fun invalidate(title: String) {
        cache.remove(title.lowercase().trim())
    }

    fun clear() { cache.evictAll() }

    // ──────────────────────────────────────────────────────────────────
    //  Private API fetch (AniVault mobile API)
    // ──────────────────────────────────────────────────────────────────
    private suspend fun fetchFromApi(title: String): SubDubInfo? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = URL("$BASE_URL/browse?q=$encoded")
            val conn = url.openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            val body = conn.getInputStream().bufferedReader().readText()
            val json = JSONObject(body)
            val arr = json.optJSONArray("data") ?: return@withContext null
            if (arr.length() == 0) return@withContext null

            // Prefer TV series (main show) over movies/specials; pick highest-episode
            // TV match when there are multiple (e.g. Hunter x Hunter 2011 vs 1999).
            val titleLower = title.lowercase().trim()
            var bestTv: Triple<Int, Int, JSONObject>? = null  // (score, episodes, item)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val itemTitle = item.optString("title", "").lowercase().trim()
                val eps = item.optInt("episodes", 0)
                val type = item.optString("type", "")
                val isTv = type.equals("TV", ignoreCase = true)
                val titleScore = when {
                    itemTitle == titleLower -> 100
                    itemTitle.startsWith(titleLower) || titleLower.startsWith(itemTitle) -> 50
                    else -> 0
                }
                val tvScore = if (isTv) 30 else 0
                val score = titleScore + tvScore + (if (eps > 0) 1 else 0) + (if (isTv) 0 else 0)
                val cur = Triple(score, eps, item)
                if (bestTv == null || cur.first > bestTv!!.first ||
                    (cur.first == bestTv!!.first && cur.second > bestTv!!.second)) {
                    bestTv = cur
                }
            }
            val item = bestTv?.third ?: arr.getJSONObject(0)

            val episodes = item.optInt("episodes", 0)
            val airedInfo = item.optJSONObject("airedInfo")
            val aired = airedInfo?.optInt("aired", 0) ?: 0
            val total = if (episodes > 0) episodes else aired
            val sub = if (aired > 0) aired else total
            val dubbedLangs = item.optJSONArray("dubbedLangs") ?: JSONArray()
            val dub = if (dubbedLangs.length() > 0) sub else 0
            SubDubInfo(sub, dub, total)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("SubDubCache fetch error: ${e.message}")
            null
        }
    }
}
