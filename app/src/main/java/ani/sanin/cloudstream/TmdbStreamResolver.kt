package ani.sanin.cloudstream

import android.content.Context
import android.content.Intent
import android.util.Log
import ani.sanin.FileUrl
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.media.Media
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.TimeoutCancellationException
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
            val headers: Map<String, String> = emptyMap()
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
            response is TvSeriesLoadResponse && season != null && episodeNumber != null -> {
                val episode = response.episodes.firstOrNull {
                    it.episode == episodeNumber && (it.season == null || it.season == season)
                } ?: response.episodes.firstOrNull { it.episode == episodeNumber }
                episode?.data ?: return ApiResolve(
                    emptyList(),
                    "${api.name}: episode $episodeNumber (season $season) not found in load response"
                )
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
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers
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

    /**
     * Plays a resolved TMDB stream through the full-featured anime player
     * (ExoplayerView), so every button/behavior is identical to anime playback.
     * The plugin link is wrapped into a synthetic one-episode [Media] with a
     * single [VideoExtractor] carrying the URL + headers.
     */
    fun openInAnimePlayer(context: Context, title: String, link: StreamResult.PlayableLink, mediaId: Int) {
        val url = link.url
        val videoType = when {
            url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
            url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
            else -> VideoType.CONTAINER
        }
        val headers = HashMap(link.headers).apply {
            link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val video = Video(quality = null, format = videoType, file = FileUrl(url, headers))
        val extractor = object : VideoExtractor() {
            override val server = VideoServer("TMDB", "")
            override suspend fun extract() = VideoContainer(listOf(video))
        }
        val episode = Episode(
            number = "1",
            title = title,
            selectedExtractor = "TMDB",
            selectedVideo = 0,
            selectedSubtitle = -1,
            extractors = mutableListOf(extractor),
            extractorsSource = 0,
        )
        val anime = Anime(
            selectedEpisode = "1",
            episodes = mutableMapOf("1" to episode),
            totalEpisodes = 1,
            episodeDuration = 1,
        )
        val id = syntheticId(mediaId)
        val media = Media(
            anime = anime,
            id = id,
            name = title,
            nameRomaji = title,
            userPreferredName = title,
            isAdult = false,
        )
        ExoplayerView.media = media
        ExoplayerView.initialized = true
        Logger.log(
            "TMDB_PLAY: launching ExoplayerView type=${videoType.name} " +
                "headers=${headers} mediaId=$id"
        )
        context.startActivity(Intent(context, ExoplayerView::class.java))
    }
}
