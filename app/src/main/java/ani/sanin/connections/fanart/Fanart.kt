package ani.sanin.connections.fanart

import android.util.Log
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import kotlin.math.min

/**
 * One piece of artwork on Fanart, as the `hdtvlogo`/`showbackground`/... groups return them.
 *
 * [lang] is the reason this file exists. AniZip cannot be asked for a language, and the
 * clear logos it proxies from TheTVDB are frequently character art rather than a wordmark.
 * Fanart tags its artwork with a `lang`, so the wrong-language half of that bug is fixed by
 * filtering rather than by hoping: across the anime measured, 11 of 17 titles carried logos in
 * more than one language (en 63, ja 15, fr 6, zh 5, es 4, ko 3), and a naive "first entry" pick
 * hands back Japanese or French for most of them. `en` was present on every title that had any
 * logo at all, which is what makes a strict filter safe — for the groups whose text is drawn on
 * the image. It is not applied to backgrounds or posters, which Fanart tags inconsistently and
 * which have no language to be wrong in; see [anyLanguage].
 *
 * [width]/[height] are carried through as the strings Fanart sends them as. Nothing here reads
 * them, and decoding them into numbers would lean on lenient mode to accept the quotes — a
 * dependency worth not taking on a field with no caller, on a response every logo depends on.
 */
@Serializable
data class FanartImage(
    val url: String? = null,
    val lang: String? = null,
    @SerialName("likes") val likes: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("width") val width: String? = null,
    @SerialName("height") val height: String? = null,
)

/**
 * A TV show's artwork, keeping only the groups this app asks for.
 *
 * Every field defaults to an empty list rather than being nullable: Fanart omits a group
 * entirely when it has no asset for it, and an absent group and a present-but-empty one mean
 * the same thing to every caller here.
 *
 * Only the TV groups are modelled. Anime is what this client is for, and the movie groups
 * (`hdmovielogo` and friends) stay on TMDB.
 */
@Serializable
data class FanartTv(
    val name: String? = null,
    @SerialName("hdtvlogo") val hdLogo: List<FanartImage> = emptyList(),
    @SerialName("clearlogo") val clearLogo: List<FanartImage> = emptyList(),
    @SerialName("hdtvclearart") val hdClearart: List<FanartImage> = emptyList(),
    @SerialName("clearart") val clearart: List<FanartImage> = emptyList(),
    @SerialName("show4kbackground") val background4k: List<FanartImage> = emptyList(),
    @SerialName("showbackground") val background: List<FanartImage> = emptyList(),
    @SerialName("tvposter") val poster: List<FanartImage> = emptyList(),
)

/**
 * Fanart.tv artwork for anime, by TheTVDB id.
 *
 * ## The id must be a TVDB id
 *
 * This is the one thing that will silently break the whole client. Fanart's `/tv/` endpoint is
 * keyed by **TheTVDB**, and it does not accept the TMDB or IMDb ids that every other service in
 * this app uses. Measured on six anime, all three id spaces were tried against every title:
 * TVDB resolved 6/6, TMDB 404'd 6/6, IMDb 404'd 6/6. Passing a TMDB id produces a clean, logged
 * "TV show not found" rather than an error, so it looks like missing artwork and not a wrong id.
 * AniZip hands out `thetvdb_id` alongside the TMDB one, which is where [tvdbId] comes from.
 *
 * ## Rate limiting
 *
 * Fanart publishes no request ceiling. Its own access-tier table lists every tier as
 * "Unlimited", yet 429s do occur, which is why Fanart's reference client ships automatic
 * `Retry-After` handling and exponential backoff. So the limit is treated as unbounded but
 * throttle-prone: [fetch] honours `Retry-After` when the server sends one and backs off
 * otherwise, and a give-up returns null so the caller falls through to TMDB rather than
 * showing an empty frame.
 *
 * Note this only governs the metadata call. The image files themselves are served from the
 * `assets.fanart.tv` CDN, which is a separate host with its own (far softer) limits.
 */
object Fanart {
    private const val BASE = "https://webservice.fanart.tv/v3.2"

    /** Same bound and rationale as the AniZip cache: a rail of rows re-binds on every scroll. */
    private const val MAX_CACHE = 64

    /** Enough to outlast a scroll burst without holding a screen's worth of artwork forever. */
    private const val MAX_429_RETRIES = 3

    private const val BASE_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 8_000L

    /**
     * One artwork document per show, keyed by TVDB id.
     *
     * Bounded and drop-oldest, because a browse session touches far more titles than fit and
     * the whole document is fetched to read one field from it.
     */
    private val cache = object : LinkedHashMap<Int, FanartTv?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FanartTv?>?): Boolean =
            size > MAX_CACHE
    }

    /**
     * Collapses concurrent fetches of one show.
     *
     * A row is rebound while its artwork is still loading often enough that without this the
     * same document goes out two or three times at once, and Fanart is the one upstream here
     * that can answer with a 429.
     */
    private val inFlight = HashMap<Int, CompletableDeferred<FanartTv?>>()

    val apiKey: String get() = PrefManager.getVal(PrefName.FanartApiKey)

/**
     * The artwork document for [tvdbId], or null when the call could not be completed.
     *
     * An empty document means Fanart answered and has nothing for this show, and is cached: that
     * is the common case for an older title and it is final. Null means the request itself failed
     * or was throttled, is not cached, and is retried on the next bind — the distinction matters
     * because treating a dropped connection as "no artwork" would blank a title for the session.
     *
     * Either way every role below reads as null, so a caller can chain straight to its next
     * source without caring which of the two it got.
     */
suspend fun tv(tvdbId: Int): FanartTv? {
        synchronized(cache) {
            if (cache.containsKey(tvdbId)) return cache[tvdbId]
        }

        val mine = CompletableDeferred<FanartTv?>()
        val pending = synchronized(inFlight) { inFlight.putIfAbsent(tvdbId, mine) ?: mine }
        if (pending !== mine) return pending.await()

        val parsed = fetch(tvdbId)
        synchronized(inFlight) { inFlight.remove(tvdbId) }
        mine.complete(parsed)

        // Only successes are cached. A 429 or a dropped connection says nothing about whether
        // the show has artwork, so caching one would blank a title for the rest of the session.
        if (parsed != null) synchronized(cache) { cache[tvdbId] = parsed }
        return parsed
    }

    private suspend fun fetch(tvdbId: Int): FanartTv? {
        val key = apiKey
        if (key.isBlank()) {
            Logger.log(Log.WARN, "Fanart: no API key, skipping tv/$tvdbId")
            return null
        }

        var attempt = 0
        while (true) {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE/tv/$tvdbId?api_key=$key")
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        when {
                            // A show Fanart does not carry. Not an error: plenty of anime are
                            // missing, and the caller has a fallback for exactly this.
                            response.code == 404 -> Outcome.Missing
                            response.code == 429 -> Outcome.Throttled(response.header("Retry-After"))
                            !response.isSuccessful -> {
                                Logger.log(Log.WARN, "Fanart tv/$tvdbId HTTP ${response.code}")
                                Outcome.Failed
                            }
                            else -> Outcome.Body(response.body?.string().orEmpty())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(Log.WARN, "Fanart tv/$tvdbId failed: ${e.message}")
                return null
            }

            when (outcome) {
                // An empty document rather than null, so it is cached. A 404 is Fanart saying it
                // does not carry this show, which will not change while the app runs, and these
                // titles get rebound on every scroll — without this they would re-request for as
                // long as the screen is open. A dropped connection or a throttle returns null
                // below and stays uncached, so a real failure is retried rather than believed.
                is Outcome.Missing -> return FanartTv()
                is Outcome.Failed -> return null
                is Outcome.Body -> return runCatching {
                    Mapper.json.decodeFromString<FanartTv>(outcome.text)
                }.onFailure {
                    Logger.log(Log.WARN, "Fanart tv/$tvdbId decode failed: ${it.message}")
                }.getOrNull()
                is Outcome.Throttled -> {
                    if (attempt >= MAX_429_RETRIES) {
                        Logger.log(Log.WARN, "Fanart tv/$tvdbId still throttled after $attempt retries")
                        return null
                    }
                    val wait = outcome.retryAfterMs() ?: backoffMs(attempt)
                    Logger.log(Log.WARN, "Fanart tv/$tvdbId 429, waiting ${wait}ms")
                    delay(wait)
                    attempt++
                }
            }
        }
    }

    private sealed interface Outcome {
        data object Missing : Outcome
        data object Failed : Outcome
        data class Body(val text: String) : Outcome
        data class Throttled(val retryAfter: String?) : Outcome
    }

    /**
     * `Retry-After` as milliseconds, when the server sent one we can use.
     *
     * The header is either a count of seconds or an HTTP date. Only the numeric form is
     * honoured, and a value that is absurd is dropped rather than obeyed — a date form or a
     * typo should not park a screen for an hour waiting out a limit that was never set.
     */
    private fun Outcome.Throttled.retryAfterMs(): Long? {
        val seconds = retryAfter?.trim()?.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        return min(seconds * 1000L, MAX_BACKOFF_MS)
    }

    private fun backoffMs(attempt: Int): Long =
        min(BASE_BACKOFF_MS shl attempt, MAX_BACKOFF_MS)

/**
 * The English asset from [group], or null when it has none.
 *
 * Strictly `lang == "en"` with no fallback to whatever is first. Every anime measured that
 * had any logo also had an English one, so the permissive version would buy nothing and
 * would reintroduce exactly the wrong-language titles this replaced. A title with artwork
 * but no English of it correctly yields null and drops to the caller's next source.
 *
 * Only for assets that carry text. See [anyLanguage] for the ones that do not.
 */
private fun english(group: List<FanartImage>): FanartImage? =
group.firstOrNull { it.lang.equals("en", ignoreCase = true) && !it.url.isNullOrBlank() }

/**
 * The first usable asset from [group], whatever language Fanart tagged it.
 *
 * For backgrounds and posters, which are pictures of places and people rather than text and so
 * have no language to be wrong in. Fanart does not tag them consistently either: every 4k
 * background measured came back with an empty `lang`, and 122 of 123 standard backgrounds did,
 * while posters carried a mix of `en`, `ja`, `es` and a literal `00`. Applying [english] to any
 * of them discards effectively all of it, so the filter is confined to the groups where the word
 * is drawn on the image and the language is therefore visible.
 */
private fun anyLanguage(group: List<FanartImage>): FanartImage? =
group.firstOrNull { !it.url.isNullOrBlank() }

private fun urlOf(group: List<FanartImage>): String? = english(group)?.url

    /**
     * The wordmark for carousels and landscape cards.
     *
     * `hdtvlogo` because it is the only logo group with full coverage on anime, and it is the
     * HD transparent one, which is what a header wants. `clearlogo` is the older,
     * standard-size group and was present for barely half the titles measured, so it is only
     * reached when there is no HD logo at all.
     */
    suspend fun logoUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let { urlOf(it.hdLogo) ?: urlOf(it.clearLogo) }

    /**
     * The wordmark for the franchise header: clear art, then the logo.
     *
     * `hdtvclearart` is the preferred asset but it is empty for every anime measured — anime
     * on Fanart is not illustrated in that style, so asking for it directly always falls
     * through. The logo is therefore not a rare fallback but the usual answer here, and this
     * stays ordered the way it is so the clear art is still used the moment any title carries
     * one. [FranchiseActivity] falls through to TMDB after this.
     */
    suspend fun clearartOrLogoUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let {
            urlOf(it.hdClearart) ?: urlOf(it.clearart) ?: urlOf(it.hdLogo) ?: urlOf(it.clearLogo)
        }

    /**
     * The full-screen banner behind the detail screen's tabs.
     *
     * `show4kbackground` first, `showbackground` second. The 4k group is worth preferring on a
     * TV-sized panel and covers only about two thirds of anime, so the standard background is
     * a real second source rather than a formality — without it roughly a third of detail
     * screens would have no banner at all. [MediaDetailsActivity] falls through to TMDB after.
     */
    suspend fun backgroundUrl(tvdbId: Int): String? =
        tv(tvdbId)?.let { anyLanguage(it.background4k)?.url ?: anyLanguage(it.background)?.url }

    /**
     * The portrait poster for the detail screen.
     *
     * `tvposter`, not `hdtvposter`: the HD poster group was empty for every anime measured
     * while the standard one covered all of them.
     */
    suspend fun posterUrl(tvdbId: Int): String? = tv(tvdbId)?.let { anyLanguage(it.poster)?.url }
}