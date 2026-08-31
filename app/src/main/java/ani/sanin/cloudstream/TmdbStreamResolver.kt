package ani.sanin.cloudstream

import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Base64
import ani.sanin.FileUrl
import ani.sanin.defaultHeaders
import ani.sanin.okHttpClient
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.parsers.Video
import eu.kanade.tachiyomi.animesource.model.Track
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.DrmInfo
import ani.sanin.parsers.VideoType
import ani.sanin.settings.saving.PrefManager
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import com.lagradost.cloudstream3.utils.PLAYREADY_UUID
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shared CloudStream resolution + player-launch helpers used by the TMDB
 * watch tab. Keeps the provider pipeline (search -> load -> loadLinks) in one
 * place so the details screen and watch tab behave identically.
 */
object TmdbStreamResolver {

    sealed class StreamResult {
        data class PlayableLink(
            val label: String,
            val url: String,
            val referer: String? = null,
            val headers: Map<String, String> = emptyMap(),
            val drm: DrmInfo? = null,
            val audioTracks: List<AudioFile> = emptyList(),
        )

        data class Success(val links: List<PlayableLink>, val matchName: String? = null) : StreamResult()
        data class Error(val message: String) : StreamResult()
    }

    /** Negative id space so it can never collide with a real anilist id. */
    fun syntheticId(mediaId: Int): Int = -1000000000 - (mediaId % 1000000)

    /** Tries every installed source and returns the first one that resolves streams. */
    suspend fun resolveAuto(
        context: Context,
        sources: List<CsInstalledSource>,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): Pair<CsInstalledSource?, StreamResult> {
        for (source in sources) {
            val result = resolveStreams(context, source, d, season, episodeNumber)
            if (result is StreamResult.Success) return source to result
        }
        return null to StreamResult.Error("No playable streams found on any source")
    }

    suspend fun resolveStreams(
        context: Context,
        source: CsInstalledSource,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): StreamResult {
        val apis = CsRuntime.apisFor(context, source)
        if (apis.isEmpty()) {
            val why = CsRuntime.lastError?.let { "\n$it" } ?: ""
            return StreamResult.Error("Failed to load ${source.name} — is it a .cs3 plugin?$why")
        }
        val failures = mutableListOf<String>()
        for (api in apis) {
            Logger.log(
                "TMDB_PLAY: trying provider ${api.name} for '${d.displayTitle}' " +
                    "(season=$season ep=$episodeNumber)"
            )
            Log.i("TmdbDetails", "Trying provider ${api.name} for '${d.displayTitle}' (season=$season ep=$episodeNumber)")
            try {
                // A provider stuck in Cloudflare solving / slow HTML parsing must not hang
                // playback forever; give it a budget and move on to the next provider.
                val (streams, reason, matchName) = withTimeout(60_000) {
                    resolveFromApi(api, d, season, episodeNumber)
                }
                Logger.log(
                    "TMDB_PLAY: ${api.name} returned ${streams.size} playable links " +
                        "reason=${if (reason.isNotBlank()) reason else "ok"}"
                )
                Log.i("TmdbDetails", "${api.name}: returned ${streams.size} playable links")
                if (streams.isNotEmpty()) return StreamResult.Success(streams, matchName)
                if (reason.isNotBlank()) failures += reason
            } catch (t: TimeoutCancellationException) {
                val detail = "${api.name}: timed out after 60s"
                Logger.log(Log.ERROR, "TMDB_PLAY: provider ${api.name} timed out")
                failures += detail
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: provider ${api.name} threw $detail")
                Logger.log(t)
                Log.e("TmdbDetails", "Provider ${api.name} failed", t)
                failures += "${api.name}: $detail"
            }
        }
        val why = failures.lastOrNull()?.let { " — $it" } ?: ""
        return StreamResult.Error("No playable streams found on ${source.name}$why")
    }

    /** Resolves links for a title already loaded from a plugin (home card ->
     *  watch tab). Skips the title search entirely — the LoadResponse carries
     *  the episode data, so the plugin's own URL is used verbatim. */
    suspend fun resolvePluginStreams(
        context: Context,
        source: CsInstalledSource,
        load: LoadResponse,
        season: Int?,
        episodeNumber: Int?
    ): StreamResult {
        val apis = CsRuntime.apisFor(context, source)
        if (apis.isEmpty()) {
            val why = CsRuntime.lastError?.let { "\n$it" } ?: ""
            return StreamResult.Error("Failed to load ${source.name} — is it a .cs3 plugin?$why")
        }
        val api = apis.firstOrNull { it.name == load.apiName } ?: apis.firstOrNull()
            ?: return StreamResult.Error("No provider registered in ${source.name}")
        val dataUrl: String = when (load) {
            is TvSeriesLoadResponse -> {
                val episode = when {
                    episodeNumber != null && season != null ->
                        load.episodes.firstOrNull {
                            it.episode == episodeNumber && (it.season == null || it.season == season)
                        } ?: load.episodes.firstOrNull { it.episode == episodeNumber }
                    episodeNumber != null -> load.episodes.firstOrNull { it.episode == episodeNumber }
                    season != null -> load.episodes.firstOrNull { it.season == null || it.season == season }
                    else -> load.episodes.firstOrNull()
                }
                if (episode == null) {
                    return StreamResult.Error(
                        "${source.name}: no episode found (season=$season ep=$episodeNumber) in load response"
                    )
                }
                episode.data
            }
            is MovieLoadResponse -> load.dataUrl
            is LiveStreamLoadResponse -> load.dataUrl
            else -> load.url
        }
        if (dataUrl.isBlank()) {
            return StreamResult.Error("${source.name}: blank dataUrl after load")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} plugin-direct loadLinks dataUrl=${dataUrl.take(120)} " +
                "(season=$season ep=$episodeNumber)"
        )
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val ok = try {
            withTimeout(60_000) {
                api.loadLinks(
                    dataUrl,
                    isCasting = false,
                    subtitleCallback = { subs.add(it) },
                    callback = { links.add(it) }
                )
            }
        } catch (t: TimeoutCancellationException) {
            return StreamResult.Error("${api.name}: timed out after 60s")
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} loadLinks threw $detail")
            Logger.log(t)
            return StreamResult.Error("${api.name}: $detail")
        }
        Logger.log("TMDB_PLAY: ${api.name} loadLinks ok=$ok links=${links.size} (plugin direct)")
        if (!ok || links.isEmpty()) {
            return StreamResult.Error("${api.name}: loadLinks ok=$ok links=${links.size}")
        }
        val seen = HashSet<String>()
        val playable = links.mapNotNull { link ->
            val url = link.url
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val quality = Qualities.getStringByInt(link.quality).ifBlank { link.name }
            val drmInfo = drmForLink(link)
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
                audioTracks = link.audioTracks,
            )
        }
        return StreamResult.Success(playable, load.name)
    }

    private data class ApiResolve(
        val links: List<StreamResult.PlayableLink>,
        val reason: String,
        val matchName: String? = null
    )

    private suspend fun resolveFromApi(
        api: MainAPI,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): ApiResolve {
        val wantMovie = d.displayTitle.isNotBlank() && (d.releaseDate != null || d.title != null)
        // Providers implement search(query, page) (paginated) OR the legacy search(query).
        // Calling the one-arg form throws NotImplementedError in the base class, which made
        // paginated-only providers (MovieBox, Goojara, ...) report "no streams". Mirror
        // CloudStream: call search(query, 1) first, then fall back to quickSearch().
        var searchError: String? = null
        val results = runCatching { api.search(d.displayTitle, 1)?.items }
            .getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                searchError = "search threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                Logger.log(t)
                Log.e("TmdbDetails", "${api.name}: search(query,1) failed", t)
                null
            }?.takeIf { it.isNotEmpty() }
            ?: runCatching { api.quickSearch(d.displayTitle) }
                .getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    searchError = "quickSearch threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                    Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                    Logger.log(t)
                    Log.e("TmdbDetails", "${api.name}: quickSearch failed", t)
                    null
                } ?: emptyList()
        Logger.log(
            "TMDB_PLAY: ${api.name} search '${d.displayTitle}' -> ${results.size} results " +
                (searchError ?: "")
        )
        Log.i("TmdbDetails", "${api.name}: search '${d.displayTitle}' -> ${results.size} results")
        var match = bestSearchMatch(results, d.displayTitle, wantMovie)
        Log.i("TmdbDetails", "${api.name}: best match = ${match?.url ?: "NONE"}")
        if (match == null && results.isEmpty()) {
            // Live-TV / catalog-driven plugins (SKTech, PlayZTV) don't implement
            // text search; their live events live on the home page. Mirror
            // CloudStream: browse Home and match the title against the catalog.
            val homeItems = homePageItems(api)
            Logger.log("TMDB_PLAY: ${api.name} home fallback -> ${homeItems.size} catalog items")
            Log.i("TmdbDetails", "${api.name}: home fallback -> ${homeItems.size} catalog items")
            match = bestSearchMatch(homeItems, d.displayTitle, wantMovie)
            if (match != null) {
                Logger.log("TMDB_PLAY: ${api.name} matched '${d.displayTitle}' from home page: '${match.name}'")
            }
        }
        if (match == null) {
            return ApiResolve(emptyList(), "${api.name}: no search result matched '${d.displayTitle}' (${results.size} results)")
        }

        val response = try {
            api.load(match.url)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} load threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: load failed", t)
            return ApiResolve(emptyList(), "${api.name}: load threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} load '${match.url}' -> " +
                (response?.javaClass?.simpleName ?: "null response")
        )
        Log.i("TmdbDetails", "${api.name}: load ${match.url} -> ${response?.javaClass?.simpleName ?: "null"}")
        if (response == null) {
            return ApiResolve(emptyList(), "${api.name}: load returned null for ${match.url}")
        }
        val dataUrl: String = when {
            response is TvSeriesLoadResponse -> {
                val episode = when {
                    episodeNumber != null && season != null ->
                        response.episodes.firstOrNull {
                            it.episode == episodeNumber && (it.season == null || it.season == season)
                        } ?: response.episodes.firstOrNull { it.episode == episodeNumber }
                    episodeNumber != null ->
                        response.episodes.firstOrNull { it.episode == episodeNumber }
                    season != null ->
                        response.episodes.firstOrNull { it.season == null || it.season == season }
                    else -> response.episodes.firstOrNull()
                }
                if (episode == null) {
                    return ApiResolve(
                        emptyList(),
                        "${api.name}: no episode found (season=$season ep=$episodeNumber) in load response"
                    )
                }
                if (episodeNumber == null) {
                    Logger.log(
                        "TMDB_PLAY: ${api.name} no episode selected — using " +
                            "S${episode.season ?: season}E${episode.episode} data"
                    )
                }
                episode.data
            }
            response is MovieLoadResponse -> response.dataUrl
            response is LiveStreamLoadResponse -> response.dataUrl
            else -> response.url
        }
        Log.i("TmdbDetails", "${api.name}: dataUrl = ${dataUrl.take(160).ifBlank { "<blank>" }}")
        if (dataUrl.isBlank()) {
            return ApiResolve(emptyList(), "${api.name}: blank dataUrl after load")
        }

        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val ok = try {
            api.loadLinks(dataUrl, isCasting = false, subtitleCallback = { subs.add(it) }, callback = { links.add(it) })
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} loadLinks threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: loadLinks failed", t)
            return ApiResolve(emptyList(), "${api.name}: loadLinks threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} loadLinks ok=$ok links=${links.size} subs=${subs.size} " +
                "dataUrl=${dataUrl.take(120)}"
        )
        Log.i("TmdbDetails", "${api.name}: loadLinks ok=$ok links=${links.size} subs=${subs.size}")
        if (!ok || links.isEmpty()) {
            return ApiResolve(emptyList(), "${api.name}: loadLinks ok=$ok links=${links.size}")
        }

        val seen = HashSet<String>()
        val playable = links.mapNotNull { link ->
            val url = link.url
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val quality = Qualities.getStringByInt(link.quality).ifBlank { link.name }
            val drmInfo = drmForLink(link)
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
                audioTracks = link.audioTracks,
            )
        }
        return ApiResolve(playable, "ok", match?.name)
    }

    fun bestSearchMatch(
        results: List<SearchResponse>,
        title: String,
        wantMovie: Boolean
    ): SearchResponse? {
        val exact = results.firstOrNull {
            (!wantMovie || it.type?.isMovieType() == true) && looseMatch(it.name, title)
        }
        if (exact != null) return exact
        return results.firstOrNull { (!wantMovie || it.type?.isMovieType() == true) }
            ?: results.firstOrNull()
    }

    fun looseMatch(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
    return x.contains(y) || y.contains(x)
    }

    // ── Home-page fallback (live-TV / catalog-driven providers) ─────────────

    /** Flattens the home page catalog: tries the provider's declared [MainAPI.mainPage]
     *  entries first, then a synthetic "Home" request — same cascade CloudStream uses. */
    private suspend fun homePageItems(api: MainAPI): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val pages = api.mainPage.filter { it.data.isNotBlank() }
        if (pages.isEmpty()) {
            // Synthetic fallback — some providers override getMainPage even when mainPage is empty
            val resp = runCatching {
                api.getMainPage(1, MainPageRequest("Home", "", false))
            }.getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                null
            }
            resp?.items?.forEach { items.addAll(it.list) }
        } else {
            for (page in pages) {
                val resp = runCatching {
                    api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                }.getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    null
                }
                resp?.items?.forEach { items.addAll(it.list) }
                if (items.isNotEmpty()) break
            }
        }
        return items
    }

    // ── DRM inference (mirrors CloudStream DrmUtil) ─────────────────────────

    private const val WIDEVINE_SCHEME_URI =
        "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY_SCHEME_URI =
        "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"

    /** True when a DASH link's URL/headers signal encryption (CENC/Widevine/PlayReady). */
    private fun looksEncrypted(link: ExtractorLink): Boolean {
        if (!link.url.contains(".mpd", ignoreCase = true)) return false
        val hay = buildString {
            append(link.url.lowercase())
            link.headers.forEach { (k, v) ->
                append(' ').append(k.lowercase()).append('=').append(v.lowercase())
            }
            link.referer.takeIf { it.isNotBlank() }?.let {
                append(' ').append(it.lowercase())
            }
        }
        return hay.contains("drm=") || hay.contains("cenc") ||
            hay.contains("encrypted") || hay.contains("widevine") ||
            hay.contains("playready")
    }

    /** Fetches a DASH manifest and extracts the DRM scheme + license server URL from
     *  its ContentProtection elements (same as CloudStream's DrmUtil.getDrmData). */
    private suspend fun manifestDrm(
        url: String,
        link: ExtractorLink
    ): DrmInfo? {
        val body = withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            // Send the standard app defaults (User-Agent at minimum) so CDNs
            // that reject empty/manifest requests still serve the protected MPD.
            defaultHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
            // Mirror the plugin's own headers/referer so the server sees the
            // same Origin/Referer it would get from the player's OkHttp client.
            link.headers.forEach { (k, v) ->
                if (!k.equals("Referer", ignoreCase = true)) {
                    requestBuilder.header(k, v)
                }
            }
            link.referer.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("Referer", it)
            }
            // Build a short-lived client so a hung MPD fetch can't block
            // the entire 60s provider budget.
            val client = okHttpClient.newBuilder()
                .callTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            runCatching {
                client.newCall(requestBuilder.build()).execute().use { r ->
                    Log.i("TmdbDetails", "manifestDrm: HTTP ${r.code} success=${r.isSuccessful} contentLen=${r.body?.contentLength()}")
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrElse { t ->
                Log.i("TmdbDetails", "manifestDrm: fetch FAILED: ${t::class.java.simpleName}: ${t.message}")
                null
            }
        } ?: return null
        return parseManifestDrm(body)
    }

    /** Minimal regex-based MPD parser — finds [ContentProtection] scheme UUIDs
     *  (Widevine / PlayReady) and any `laurl` license server URL, including
     *  LA_URLs embedded inside base64 PlayReady PSSH / <mspr:pro> objects.
     *  Keeps the dependency footprint at zero (no XmlPullParser, no Ksoup). */
    private fun parseManifestDrm(mpd: String): DrmInfo? {
        if (mpd.isBlank()) return null
        val widevine = Regex(WIDEVINE_SCHEME_URI, RegexOption.IGNORE_CASE)
            .containsMatchIn(mpd)
            || Regex("""(?i)value\s*=\s*"widevine"""").containsMatchIn(mpd)
        val playready = Regex(PLAYREADY_SCHEME_URI, RegexOption.IGNORE_CASE)
            .containsMatchIn(mpd)
            || Regex("""(?i)value\s*=\s*"playready"""").containsMatchIn(mpd)
        Log.i("TmdbDetails", "parseManifestDrm: mpd_len=${mpd.length} widevine=$widevine playready=$playready")
        if (!widevine && !playready) {
            Log.i("TmdbDetails", "parseManifestDrm: no DRM schemes found, returning null")
            return null
        }
        // Extract the license URL: both attribute form (laurl="...") and element form
        // (<ms:laurl>...</ms:laurl>) are common in DASH manifests.
        val plainLicense: String? = listOf(
            Regex("""(?i)(?:laurl|license[_-]?url)\s*[=:]\s*["']?([^"'<>\s]+)"""),
            Regex("""(?i)<(?:[a-zA-Z0-9_:]+)?laurl[^>]*>\s*([^<>\s]+)\s*</(?:[a-zA-Z0-9_:]+)?laurl>""")
        ).asSequence()
            .flatMap { it.findAll(mpd) }
            .mapNotNull { runCatching { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull() }
            .firstOrNull { it.startsWith("http", ignoreCase = true) }
        // Fallback: Amazon-style CDNs put the license URL inside a base64
        // PlayReady PSSH / <mspr:pro> object instead of a plain laurl.
        val license = plainLicense ?: extractLaUrlFromPssh(mpd)
        val uuid = when {
            widevine -> WIDEVINE_UUID
            playready -> PLAYREADY_UUID
            else -> WIDEVINE_UUID
        }
        Logger.log(
            "TMDB_PLAY: DRM inferred from manifest uuid=$uuid " +
                "license=${license?.take(80) ?: "<none>"}"
        )
        Log.i("TmdbDetails", "parseManifestDrm: widevine=$widevine playready=$playready uuid=$uuid license=${license?.take(120) ?: "<none>"}")
        return DrmInfo(
            licenseUrl = license,
            uuid = uuid,
            keyRequestParameters = hashMapOf(),
        )
    }

    /** Decodes every base64 <mspr:pro> / <cenc:pssh> block in the MPD and
     *  searches the PlayReady object inside for a <LA_URL>...</LA_URL> entry.
     *  PlayReady objects are UTF-16LE XML, so we probe each byte alignment of
     *  the PSSH header until the XML shows through. */
    private fun extractLaUrlFromPssh(mpd: String): String? {
        val payloads = Regex(
            """(?is)<(?:mspr:pro|cenc:pssh)[^>]*>\s*([A-Za-z0-9+/=\s]+?)\s*</(?:mspr:pro|cenc:pssh)>"""
        ).findAll(mpd)
            .mapNotNull { m -> runCatching { Base64.decode(m.groupValues[1], Base64.DEFAULT) }.getOrNull() }
        val laUrl = Regex("""(?i)LA_URL>\s*([^<\s]+)\s*<""")
        for (bytes in payloads) {
            for (off in 0..minOf(64, bytes.size)) {
                val s = runCatching {
                    String(bytes, off, bytes.size - off, Charsets.UTF_16LE)
                }.getOrNull() ?: continue
                laUrl.find(s)?.let { match ->
                    val url = match.groupValues[1].trim()
                    if (url.startsWith("http", ignoreCase = true)) return url
                }
            }
        }
        return null
    }

    /** Returns [DrmInfo] for a link — extracts from [DrmExtractorLink] or, for
     *  plain CENC DASH streams, mirrors CloudStream's DrmUtil by fetching the
     *  manifest and parsing its ContentProtection. */
    private suspend fun drmForLink(link: ExtractorLink): DrmInfo? {
        Log.i("TmdbDetails", "drmForLink: url=${link.url.take(120)} class=${link::class.java.simpleName}")
        val encrypted = looksEncrypted(link)
        Log.i("TmdbDetails", "drmForLink: looksEncrypted=$encrypted")
        val result: DrmInfo? = when (link) {
            is DrmExtractorLink -> {
                val license = link.licenseUrl?.takeIf { it.isNotBlank() }
                license?.let { url ->
                    DrmInfo(
                        licenseUrl = url,
                        uuid = link.uuid.toJavaUuid(),
                        keyRequestParameters = link.keyRequestParameters,
                        kid = link.kid,
                        key = link.key,
                        kty = link.kty,
                    )
                } ?: runCatching {
                    val base = runCatching {
                        val u = java.net.URI(link.url)
                        "${u.scheme}://${u.host}${u.path}"
                    }.getOrNull() ?: link.url
                    val drm = manifestDrm(link.url, link)
                    if (drm != null) synchronized(drmCache) { drmCache.putIfAbsent(base, drm) }
                    drm
                }.getOrNull()
            }
            else -> {
                // Zangetsu approach: only use DRM when the plugin explicitly
                // provides a DrmExtractorLink. Inferring DRM from manifest
                // ContentProtection causes spurious license-server failures
                // (UnknownHostException) on streams that play fine without it.
                Log.i("TmdbDetails", "drmForLink: non-DRM link, skipping DRM inference")
                null
            }
        }
        Log.i("TmdbDetails", "drmForLink: result uuid=${result?.uuid} license=${result?.licenseUrl?.take(80) ?: "<none>"}")
        return result
    }

    // ── Synthetic TMDB media session (watch tab -> anime player) ─────────────

    data class SyntheticSession(
        val mediaId: Int,
        val mediaType: String,
        val detail: TmdbDetail,
        val source: CsInstalledSource,
        val load: LoadResponse? = null,
        val isLive: Boolean = load is LiveStreamLoadResponse
    )

    /** Per-plugin episode shells (extractors live on them, so re-launching the
     *  player for the same source shows servers instantly — no re-fetch). */
    private val episodeShells = mutableMapOf<String, Map<String, Episode>>()

    /** Resolved links per episode, keyed by plugin + season/ep; shared between
     *  the watch tab and the player so nothing is ever fetched twice. */
    private val linksCache = mutableMapOf<String, StreamResult.Success>()

    private val sessions = mutableMapOf<Int, SyntheticSession>()

    /** Deduplicates DRM manifest fetches so N quality variants of the same live
     *  stream don't each round-trip the MPD. Keyed by scheme://host/path. */
    private val drmCache = mutableMapOf<String, DrmInfo>()

    private fun cacheKey(mediaId: Int, sourceName: String, season: Int?, ep: Int?) =
        "$mediaId|$sourceName|$season|$ep"

    fun cachedLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?): StreamResult.Success? =
        linksCache[cacheKey(mediaId, sourceName, season, ep)]

    fun cacheLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?, result: StreamResult.Success) {
        linksCache[cacheKey(mediaId, sourceName, season, ep)] = result
    }

    /** Plugin name backing a synthetic media id, shown in the server sheet. */
    fun syntheticSourceName(syntheticId: Int): String? = sessions[syntheticId]?.source?.name

    /** Public accessor for the synthetic session (used by ExoplayerView for Simkl scrobble). */
    fun sessionFor(syntheticId: Int): SyntheticSession? = sessions[syntheticId]

    /** Drops every cached server sheet for a title — used when the user switches
     *  plugins so stale links from the previous source are never shown. */
    fun invalidateLinks(mediaId: Int) {
        linksCache.keys.removeAll { it.startsWith("$mediaId|") }
    }

    private fun qualityFromLabel(label: String): Int? =
        Regex("\\d{3,4}").find(label)?.value?.toIntOrNull()

    private fun videoTypeFor(url: String): VideoType = when {
        url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
        url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
        else -> VideoType.CONTAINER
    }

    /** Best-effort language label for a CloudStream audio track. The vendored
     *  AudioFile only carries url+headers, so the language is derived from the
     *  url when the plugin bakes it in (e.g. "track-eng.m3u8", "english.mp4").
     *  Returns a language name the player's LanguageMapper understands (the
     *  same path anime sources use), so the merged track is tagged, prepared
     *  and labelled correctly in the rail. Falls back to "Unknown" so the
     *  track stays selectable in the rail. */
    private fun audioLanguage(audio: AudioFile): String {
        val hay = audio.url.lowercase()
        val rules = listOf(
            "english" to "English", "eng" to "English",
            "japanese" to "Japanese", "jpn" to "Japanese", "日本語" to "Japanese",
            "hindi" to "Hindi", "hin" to "Hindi",
            "korean" to "Korean", "kor" to "Korean",
            "mandarin" to "Chinese", "cantonese" to "Chinese",
            "chinese" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
            "spanish" to "Spanish", "spa" to "Spanish",
            "french" to "French", "francais" to "French", "fra" to "French",
            "german" to "German", "deu" to "German",
            "italian" to "Italian", "ita" to "Italian",
            "portuguese" to "Portuguese", "por" to "Portuguese",
            "arabic" to "Arabic", "ara" to "Arabic",
            "russian" to "Russian", "rus" to "Russian",
            "tamil" to "Tamil", "tam" to "Tamil",
            "telugu" to "Telugu", "tel" to "Telugu",
            "malayalam" to "Malayalam", "mal" to "Malayalam",
            "thai" to "Thai", "tha" to "Thai",
            "vietnamese" to "Vietnamese", "vie" to "Vietnamese",
            "indonesian" to "Indonesian", "ind" to "Indonesian",
            "turkish" to "Turkish", "tur" to "Turkish",
            "polish" to "Polish", "pol" to "Polish",
            "ukrainian" to "Ukrainian", "ukr" to "Ukrainian",
            "portugues" to "Portuguese",
        )
        for ((needle, name) in rules) {
            if (Regex("(^|[^a-z0-9])$needle([^a-z0-9]|$)").containsMatchIn(hay)) return name
        }
        return "Unknown"
    }

    /** One extractor per link, named after the link label (quality/host), so the
     *  player's server sheet lists every server the plugin returned. */
    fun buildExtractors(links: List<StreamResult.PlayableLink>): List<VideoExtractor> {
        val used = HashSet<String>()
        return links.mapIndexedNotNull { index, link ->
            if (link.url.isBlank()) return@mapIndexedNotNull null
            var name = link.label.ifBlank { "Server ${index + 1}" }
            if (!used.add(name)) name = "$name ${index + 1}"
            val headers = HashMap(link.headers).apply {
                link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            }
            val video = Video(
                quality = qualityFromLabel(name),
                format = videoTypeFor(link.url),
                file = FileUrl(link.url, headers),
                drm = link.drm,
            )
            val audioTracks = link.audioTracks.map { Track(url = it.url, lang = audioLanguage(it)) }
            object : VideoExtractor() {
                override val server = VideoServer(name, "", mapOf("quality" to name))
                override suspend fun extract() =
                    VideoContainer(videos = listOf(video), audioTracks = audioTracks)
            }.apply {
                videos = listOf(video)
                this.audioTracks = audioTracks
            }
        }
    }

    private fun episodeKey(season: Int, ep: Int) = "S${season}E${ep}"

    private fun tmdbEpisode(mediaId: Int, season: Int, ep: TmdbEpisode): Episode = Episode(
        number = episodeKey(season, ep.episodeNumber),
        title = ep.name ?: "Episode ${ep.episodeNumber}",
        desc = ep.overview,
        thumb = Tmdb.imageUrl(ep.stillPath, 780)?.let { FileUrl(it) },
        date = ep.airDate,
        rating = ep.voteAverage.takeIf { it > 0 }
            ?.let { String.format(java.util.Locale.US, "%.1f", it) },
        selectedSubtitle = -1,
        extra = mapOf(
            "season" to season.toString(),
            "episode" to ep.episodeNumber.toString(),
            "tmdbId" to mediaId.toString()
        )
    )

    private suspend fun episodesFor(
        mediaId: Int,
        mediaType: String,
        d: TmdbDetail,
        sourceName: String
    ): Map<String, Episode> {
        val key = "$mediaId|$sourceName"
        episodeShells[key]?.let { return it }
        val map = linkedMapOf<String, Episode>()
        if (mediaType == "tv") {
            for (season in Tmdb.seasons(mediaType, mediaId)) {
                for (ep in Tmdb.episodes(mediaType, mediaId, season.seasonNumber)) {
                    map[episodeKey(season.seasonNumber, ep.episodeNumber)] =
                        tmdbEpisode(mediaId, season.seasonNumber, ep)
                }
            }
        } else {
            map["1"] = Episode(
                number = "1",
                title = d.displayTitle,
                desc = d.overview,
                thumb = Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780)?.let { FileUrl(it) },
                date = d.releaseDate,
                rating = d.voteAverage.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                selectedSubtitle = -1,
                extra = mapOf("tmdbId" to mediaId.toString())
            )
        }
        episodeShells[key] = map
        Logger.log("TMDB_PLAY: built ${map.size} episode shells for '$mediaType' id=$mediaId source=$sourceName")
        return map
    }

    /** Player episode shells straight from a plugin's per-season episodes —
     *  used when the watch tab was opened from a plugin home card, so the rail
     *  reflects the plugin's data (no TMDB lookups on a synthetic id). */
    fun pluginEpisodeShells(
        mediaId: Int,
        seasonEpisodes: Map<Int, List<TmdbEpisode>>
    ): Map<String, Episode> {
        val map = linkedMapOf<String, Episode>()
        seasonEpisodes.forEach { (season, eps) ->
            eps.forEach { ep ->
                map[episodeKey(season, ep.episodeNumber)] = tmdbEpisode(mediaId, season, ep)
            }
        }
        return map
    }

    /** Single "episode" shell for a plugin movie (key "1", mirrors the TMDB
     *  movie branch of [episodesFor]). */
    fun pluginMovieShell(mediaId: Int, d: TmdbDetail): Map<String, Episode> =
        linkedMapOf(
            "1" to Episode(
                number = "1",
                title = d.displayTitle,
                desc = d.overview,
                thumb = Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780)?.let { FileUrl(it) },
                date = d.releaseDate,
                rating = d.voteAverage.takeIf { it > 0 }
                    ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                selectedSubtitle = -1,
                extra = mapOf("tmdbId" to mediaId.toString())
            )
        )

    /** Resolves (or reuses cached) links for a synthetic episode and fills its
     *  extractors so the player can build media. Returns false if nothing played. */
    suspend fun populateSyntheticEpisode(context: Context, media: Media, ep: Episode): Boolean =
        withContext(Dispatchers.IO) {
            if (!ep.extractors.isNullOrEmpty() && ep.allStreams) return@withContext true
            val session = sessions[media.id] ?: return@withContext false
            val season = ep.extra?.get("season")?.toIntOrNull()
            val episodeNum = ep.extra?.get("episode")?.toIntOrNull()
            val cached = cachedLinks(session.mediaId, session.source.name, season, episodeNum)
            val result = cached
                ?: if (session.load != null) {
                    (resolvePluginStreams(context, session.source, session.load, season, episodeNum)
                        as? StreamResult.Success)
                        .also { if (it != null) cacheLinks(session.mediaId, session.source.name, season, episodeNum, it) }
                } else {
                    (resolveStreams(context, session.source, session.detail, season, episodeNum)
                        as? StreamResult.Success)
                        .also { if (it != null) cacheLinks(session.mediaId, session.source.name, season, episodeNum, it) }
                }
                ?: return@withContext false
            val extractors = buildExtractors(result.links)
            if (extractors.isEmpty()) return@withContext false
            ep.extractors = extractors.toMutableList()
            ep.extractorsSource = 0
            ep.allStreams = true
            Logger.log(
                "TMDB_PLAY: populated synthetic ep '${ep.number}' with ${extractors.size} " +
                    "servers via ${session.source.name}"
            )
            true
        }

    /** Launches the full anime player for a TMDB title: all episodes across all
     *  seasons are wired into the rail, the picked episode carries every server
     *  the plugin returned (server button switches without re-fetching), and the
     *  metadata drives the pause overlay + online subtitles. */
    suspend fun launchPlayer(
        context: Context,
        mediaId: Int,
        mediaType: String,
        d: TmdbDetail,
        source: CsInstalledSource,
        season: Int?,
        epNumber: Int?,
        links: List<StreamResult.PlayableLink>,
        pickedLabel: String,
        episodesOverride: Map<String, Episode>? = null,
        load: LoadResponse? = null
    ) {
        val id = syntheticId(mediaId)
        sessions[id] = SyntheticSession(mediaId, mediaType, d, source, load)
        if (sessions.size > 24) {
            sessions.entries.take(sessions.size - 24).forEach { sessions.remove(it.key) }
        }
        val episodes = episodesOverride ?: episodesFor(mediaId, mediaType, d, source.name)
        val currentKey = if (mediaType == "tv") {
            episodeKey(season ?: 1, epNumber ?: 1)
        } else "1"
        val current = episodes[currentKey] ?: episodes.values.firstOrNull() ?: return
        val extractors = buildExtractors(links)
        if (extractors.isEmpty()) return
        current.extractors = extractors.toMutableList()
        current.extractorsSource = 0
        current.allStreams = true
        current.selectedExtractor = pickedLabel.ifBlank { extractors.first().server.name }
        current.selectedVideo = 0
        val title = d.displayTitle
        val media = Media(
            anime = Anime(
                totalEpisodes = episodes.size,
                selectedEpisode = currentKey,
                episodes = episodes.toMutableMap()
            ),
            id = id,
            name = title,
            nameRomaji = title,
            userPreferredName = title,
            description = d.overview,
            genres = ArrayList(d.genres.map { it.name }),
            meanScore = (d.voteAverage * 10).toInt().coerceIn(0, 100),
            cover = Tmdb.imageUrl(d.posterPath, 500),
            banner = Tmdb.imageUrl(d.backdropPath, 780),
            logoUrl = Tmdb.logoUrl(d),
            format = if (mediaType == "tv") "TV" else "MOVIE",
            idIMDB = d.externalIds?.imdbId,
            isAdult = false,
            status = d.status?.uppercase()
        )
        // Persist the picked server so auto-next/rail clicks keep playing the same
        // plugin server immediately (anime mode's "Make Default" continuity).
        val selected = Selected(sourceIndex = 0, server = pickedLabel, video = 0)
        media.selected = selected
        PrefManager.setCustomVal("Selected-$id", selected)
        ExoplayerView.media = media
        ExoplayerView.initialized = true
        Logger.log(
            "TMDB_PLAY: launching ExoplayerView id=$id title='$title' eps=${episodes.size} " +
                "servers=${extractors.size} picked='$pickedLabel' imdb=${media.idIMDB}"
        )
        context.startActivity(Intent(context, ExoplayerView::class.java))
    }
}
