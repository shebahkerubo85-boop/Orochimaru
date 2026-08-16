package ani.sanin.cloudstream

import android.content.Context
import android.content.Intent
import android.util.Log
import ani.sanin.FileUrl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.parsers.Video
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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
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
            val drmInfo = when (link) {
                is DrmExtractorLink -> DrmInfo(
                    licenseUrl = link.licenseUrl,
                    uuid = link.uuid.toJavaUuid(),
                    keyRequestParameters = link.keyRequestParameters,
                    kid = link.kid,
                    key = link.key,
                    kty = link.kty,
                )
                else -> null
            }
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
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
        val match = bestSearchMatch(results, d.displayTitle, wantMovie)
        Log.i("TmdbDetails", "${api.name}: best match = ${match?.url ?: "NONE"}")
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
            val drmInfo = when (link) {
                is DrmExtractorLink -> DrmInfo(
                    licenseUrl = link.licenseUrl,
                    uuid = link.uuid.toJavaUuid(),
                    keyRequestParameters = link.keyRequestParameters,
                    kid = link.kid,
                    key = link.key,
                    kty = link.kty,
                )
                else -> null
            }
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers,
                drm = drmInfo,
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

    // ── Synthetic TMDB media session (watch tab -> anime player) ─────────────

    data class SyntheticSession(
        val mediaId: Int,
        val mediaType: String,
        val detail: TmdbDetail,
        val source: CsInstalledSource,
        val load: LoadResponse? = null
    )

    /** Per-plugin episode shells (extractors live on them, so re-launching the
     *  player for the same source shows servers instantly — no re-fetch). */
    private val episodeShells = mutableMapOf<String, Map<String, Episode>>()

    /** Resolved links per episode, keyed by plugin + season/ep; shared between
     *  the watch tab and the player so nothing is ever fetched twice. */
    private val linksCache = mutableMapOf<String, StreamResult.Success>()

    private val sessions = mutableMapOf<Int, SyntheticSession>()

    private fun cacheKey(mediaId: Int, sourceName: String, season: Int?, ep: Int?) =
        "$mediaId|$sourceName|$season|$ep"

    fun cachedLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?): StreamResult.Success? =
        linksCache[cacheKey(mediaId, sourceName, season, ep)]

    fun cacheLinks(mediaId: Int, sourceName: String, season: Int?, ep: Int?, result: StreamResult.Success) {
        linksCache[cacheKey(mediaId, sourceName, season, ep)] = result
    }

    /** Plugin name backing a synthetic media id, shown in the server sheet. */
    fun syntheticSourceName(syntheticId: Int): String? = sessions[syntheticId]?.source?.name

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
            object : VideoExtractor() {
                override val server = VideoServer(name, "", mapOf("quality" to name))
                override suspend fun extract() = VideoContainer(listOf(video))
            }.apply { videos = listOf(video) }
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
