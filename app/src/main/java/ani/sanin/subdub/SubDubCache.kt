package ani.sanin.subdub

import android.util.LruCache
import ani.sanin.util.Logger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Lightweight in-memory LRU cache for sub/dub episode counts.
 * Fetches on-demand from AniVault's mobile API, keyed by anime title.
 */
object SubDubCache {

    private const val BASE_URL = "https://anivault-proxy.shemaus58.workers.dev"

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
    //  Fetch from Cloudflare Worker (scores + caches at edge)
    // ──────────────────────────────────────────────────────────────────
    private suspend fun fetchFromApi(title: String): SubDubInfo? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = URL("$BASE_URL/?q=$encoded")
            val conn = url.openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.getInputStream().bufferedReader().readText()
            val json = JSONObject(body)
            val sub = json.optInt("sub", 0)
            val dub = json.optInt("dub", 0)
            val total = json.optInt("total", 0)
            if (sub == 0 && dub == 0 && total == 0) null else SubDubInfo(sub, dub, total)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("SubDubCache fetch error: ${e.message}")
            null
        }
    }
}
