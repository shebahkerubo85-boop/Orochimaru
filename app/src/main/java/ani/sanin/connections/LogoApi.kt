package ani.sanin.connections

import android.content.Context
import ani.sanin.Mapper
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.fanart.Fanart
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves the artwork a title's own screens show: its wordmark, its clear art, its banner and
 * its poster, all keyed by AniList id because that is what every caller holds.
 *
 * ## Why this is Fanart and not AniZip
 *
 * This used to read AniZip's `Clearlogo` directly, which produced two defects. AniZip proxies
 * that field from TheTVDB, whose `icons` and `clearlogo` buckets are crowd-uploaded and often
 * hold character or key art rather than a wordmark, so a card would show a picture of a person
 * where a title belonged. And AniZip cannot be asked for a language, so a title could come back
 * in whatever language its artwork happened to carry.
 *
 * Fanart fixes the second outright: its artwork is tagged with a `lang`, and across the anime
 * measured 11 of 17 titles carried a logo in more than one language, so filtering on `en` is
 * the difference between a title and a random translation of it. It fixes the first by having a
 * real logo group, `hdtvlogo`, that was present for every title measured. The filter covers the
 * text-bearing artwork only — backgrounds and posters are not language-tagged in any usable way,
 * so requiring English of them would throw away nearly all of them.
 *
 * AniZip is still used here, but only for what it is good for: mapping an AniList id to the
 * TheTVDB id that Fanart's `/tv/` endpoint is keyed by. It supplies no image of its own.
 *
 * ## Caching
 *
 * Two layers, because these screens rebind constantly. [memoryCache] covers a session and
 * survives a scroll; the on-disk file covers a cold start, where otherwise every visible row
 * would fire a Fanart request before anything could be drawn.
 *
 * The file name carries a version because the old one is full of TheTVDB URLs this is being
 * changed to stop showing. Reading it would pin every one of those to its card for good, since
 * a disk cache has no expiry — the bug would survive the release that was meant to fix it.
 */
object LogoApi {
    /** Role suffixes, so one AniList id can cache each kind of asset separately. */
    private const val ROLE_LOGO = "logo"
    private const val ROLE_CLEARART = "clearart"
    private const val ROLE_BACKGROUND = "bg"
    private const val ROLE_POSTER = "poster"

    /**
     * Bumped to `fanart-v1` when this stopped serving AniZip clearlogos.
     *
     * The old `logos.json` holds TheTVDB URLs, which are the character art this change exists to
     * stop showing. Nothing in it can expire, so it has to be renamed away rather than merged.
     */
    private const val CACHE_FILE = "logos_fanart_v1.json"

    private var cacheDir: File? = null

    private val memoryCache = HashMap<String, String>()
    private var diskLoaded = false

    fun init(context: Context) {
        if (cacheDir != null) return
        cacheDir = File(context.cacheDir, "logo_cache").also { it.mkdirs() }
    }

    private fun getCacheFile(): File? =
        cacheDir?.let { File(it, CACHE_FILE) }

    private fun loadFromDisk() {
        if (diskLoaded) return
        diskLoaded = true
        try {
            val file = getCacheFile() ?: return
            if (!file.exists()) return
            val map = Mapper.json.decodeFromString<Map<String, String>>(file.readText())
            memoryCache.putAll(map)
            Logger.log("LogoApi: loaded ${map.size} cached entries")
        } catch (e: Exception) {
            Logger.log("LogoApi: disk load error - ${e.message}")
        }
    }

    private fun saveToDisk() {
        try {
            val file = getCacheFile() ?: return
            file.writeText(Mapper.json.encodeToString(memoryCache))
        } catch (e: Exception) {
            Logger.log("LogoApi: disk save error - ${e.message}")
        }
    }

    /**
     * The wordmark for a title, or null when it has none.
     *
     * The single entry point behind the landscape cards and the detail screen's logo slots, so
     * every one of those reads the same asset and there is one cache to reason about. Callers
     * fall through to TMDB and then to plain text on a null.
     */
    suspend fun getLogoUrl(anilistId: Int): String? = resolve(anilistId, ROLE_LOGO) { tvdbId ->
        Fanart.logoUrl(tvdbId)
    }

    /**
     * The wordmark for a franchise header: clear art first, logo second.
     *
     * Clear art is preferred because it is the asset that reads as a franchise banner, but it is
     * empty for every anime on Fanart, so the logo is what this actually returns in practice.
     * Both are tried rather than just the logo so a title that does gain clear art is picked up
     * without another change.
     */
    suspend fun getClearartUrl(anilistId: Int): String? = resolve(anilistId, ROLE_CLEARART) { tvdbId ->
        Fanart.clearartOrLogoUrl(tvdbId)
    }

    /**
     * The full-screen banner for the detail screen.
     *
     * Fanart's 4k background where it exists, its standard background where it does not, and
     * null for the caller's TMDB fallback. The 4k group covers roughly two thirds of anime, so
     * the second step is carrying real weight rather than guarding an edge case.
     */
    suspend fun getBackgroundUrl(anilistId: Int): String? = resolve(anilistId, ROLE_BACKGROUND) { tvdbId ->
        Fanart.backgroundUrl(tvdbId)
    }

    /** The portrait poster for the detail screen, with the AniList cover as the caller's fallback. */
    suspend fun getPosterUrl(anilistId: Int): String? = resolve(anilistId, ROLE_POSTER) { tvdbId ->
        Fanart.posterUrl(tvdbId)
    }

    /**
     * Wordmarks for a whole row of cards at once.
     *
     * A carousel binds every visible card together, and asking for them one at a time serialises
     * a round trip per item behind a load animation. Only the ids that resolve are returned, so
     * a caller can treat absence as "draw the title text" without a second lookup.
     */
    suspend fun getLogosBatch(anilistIds: List<Int>): Map<Int, String> {
        if (anilistIds.isEmpty()) return emptyMap()
        if (!diskLoaded) withContext(Dispatchers.IO) { loadFromDisk() }

        val pending = anilistIds.filter { !memoryCache.containsKey(key(it, ROLE_LOGO)) }
        if (pending.isNotEmpty()) {
            // Only the misses go to the network; the rest are already answered and re-reading
            // them would spend a Fanart call to learn something the cache knows. awaitAll, not a
            // plain iteration, or this would store the Deferreds rather than the URLs.
            val resolved = coroutineScope {
                pending.map { id -> async { id to getLogoUrl(id) } }.awaitAll()
            }
            for ((id, url) in resolved) {
                if (url != null) memoryCache[key(id, ROLE_LOGO)] = url
            }
            withContext(Dispatchers.IO) { saveToDisk() }
        }

        return anilistIds.mapNotNull { id ->
            memoryCache[key(id, ROLE_LOGO)]?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }

    private fun key(anilistId: Int, role: String) = "$anilistId:$role"

/**
     * Runs [fetch] against Fanart for [anilistId], behind both cache layers.
     *
     * Only a real answer is cached. A title Fanart carries nothing for costs one request per
     * cold read rather than one per rebind, because [Fanart.tv] holds that verdict itself — it
     * caches the empty document it gets from a 404, which is final, and leaves a throttled or
     * dropped call uncached so it is retried. Caching a failure here as if it were an absence
     * would be the one way to make a transient error permanent.
     */
    private suspend fun resolve(
        anilistId: Int,
        role: String,
        fetch: suspend (tvdbId: Int) -> String?,
    ): String? {
        if (!diskLoaded) withContext(Dispatchers.IO) { loadFromDisk() }
        memoryCache[key(anilistId, role)]?.let { return it.ifEmpty { null } }

        return withContext(Dispatchers.IO) {
            val tvdbId = AniZip.getMappings(anilistId)?.tvdbId
            if (tvdbId == null) {
                Logger.log("LogoApi: $anilistId has no TheTVDB id, skipping Fanart")
                return@withContext null
            }

            val url = fetch(tvdbId)
            if (url != null) {
                memoryCache[key(anilistId, role)] = url
                saveToDisk()
                Logger.log("LogoApi: $anilistId/$role -> $url")
            }
            url
        }
    }

    fun clearCache() {
        memoryCache.clear()
        diskLoaded = false
        getCacheFile()?.delete()
    }
}