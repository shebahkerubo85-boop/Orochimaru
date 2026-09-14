package ani.sanin.subdub

import android.util.LruCache
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight in-memory LRU cache for sub/dub episode counts.
 * Fetches on-demand from hianime-api, keyed by anime title.
 */
object SubDubCache {

    private val cache = LruCache<String, SubDubInfo>(300)

    /** Call to configure. Base URL of a self-hosted hianime-api instance. */
    var baseUrl: String = ""
        set(value) { field = value.trimEnd('/') }

    /**
     * Get sub/dub info for [title].
     * Returns cached data immediately, or launches a background fetch.
     * [onResult] is called on the main thread with the data (or null on error).
     */
    fun get(title: String, scope: CoroutineScope, onResult: (SubDubInfo?) -> Unit) {
        val key = title.lowercase().trim()
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
    //  Private API fetch
    // ──────────────────────────────────────────────────────────────────
    private suspend fun fetchFromApi(title: String): SubDubInfo? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = URL("$baseUrl/api/v1/search?keyword=$encoded&page=1")
            val conn = url.openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.getInputStream().bufferedReader().readText()
            val json = JSONObject(body)
            val animes = json.optJSONObject("data")?.optJSONArray("animes") ?: return@withContext null

            // Find best match — first result whose title (case-insensitive) starts with the query
            for (i in 0 until animes.length()) {
                val item = animes.getJSONObject(i)
                val itemTitle = item.optString("title", "")
                if (itemTitle.lowercase().trim().startsWith(title) ||
                    title.startsWith(itemTitle.lowercase().trim())
                ) {
                    val eps = item.optJSONObject("episodes") ?: continue
                    val sub = eps.optInt("sub", 0)
                    val dub = eps.optInt("dub", 0)
                    val total = eps.optInt("eps", 0)
                    return@withContext SubDubInfo(sub, dub, total)
                }
            }

            // Fallback: use first result if any
            if (animes.length() > 0) {
                val item = animes.getJSONObject(0)
                val eps = item.optJSONObject("episodes") ?: return@withContext null
                val sub = eps.optInt("sub", 0)
                val dub = eps.optInt("dub", 0)
                val total = eps.optInt("eps", 0)
                return@withContext SubDubInfo(sub, dub, total)
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
