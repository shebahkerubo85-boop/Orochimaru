package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import ani.sanin.media.Media
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

object StremioSubtitles {

    private const val BASE_URL = "https://opensubtitles-v3.strem.io/subtitles"

    /**
     * Streaming fetch: emits each provider's results as they finish, so the UI
     * can render Wyzie's subs while SubDL / OpenSubtitles are still loading.
     * The [onAllDone] lambda runs after every provider has emitted.
     */
    fun getSubtitlesStreaming(
        imdbId: String?,
        season: Int,
        episode: Int,
        isTvSeries: Boolean,
        queryText: String? = null,
        onAllDone: () -> Unit = {},
    ): Flow<List<StremioSub>> = channelFlow {
            val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
            if (!enabled) return@channelFlow
            val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
            if (imdbId == null) return@channelFlow

            val totalProviders = mutableListOf<String>()
            if (providers.contains("Wyzie")) totalProviders.add("Wyzie")
            if (providers.contains("Stremio")) totalProviders.add("Stremio")
            if (providers.contains("OpenSubtitles")) totalProviders.add("OpenSubtitles")
            if (providers.contains("SubSource")) totalProviders.add("SubSource")
            if (providers.contains("SubDL")) totalProviders.add("SubDL")

            if (totalProviders.isEmpty()) return@channelFlow

            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val totalCount = totalProviders.size
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

            fun launchProvider(name: String, block: suspend () -> List<StremioSub>) {
                scope.launch {
                    try {
                        val subs = block()
                        Logger.log("StremioSubtitles($name): ${subs.size} subs")
                        if (subs.isNotEmpty()) send(subs)
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles($name): error - ${e.message}")
                    } finally {
                        if (completed.incrementAndGet() >= totalCount) {
                            onAllDone()
                        }
                    }
                }
            }

            if (providers.contains("Wyzie")) {
                launchProvider("Wyzie") {
                    WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)
                        .map { sub ->
                            val enrichedLabel = if (sub.displayLabel.equals(sub.language, ignoreCase = true)) {
                                "${sub.displayLabel} (${sub.format.uppercase()})"
                            } else {
                                sub.displayLabel
                            }
                            StremioSub(id = sub.id, url = sub.url, lang = sub.displayLabel, source = "wyzie", label = enrichedLabel)
                        }
                }
            }

            if (providers.contains("Stremio")) {
                launchProvider("Stremio") {
                    val urlsToTry = if (!isTvSeries) {
                        listOf("$BASE_URL/movie/$imdbId.json")
                    } else {
                        listOf(
                            "$BASE_URL/episode/$imdbId:$season:$episode.json",
                            "$BASE_URL/episode/$imdbId:1:$episode.json",
                            "$BASE_URL/episode/$imdbId:$episode.json",
                            "$BASE_URL/series/$imdbId:$season:$episode.json"
                        )
                    }
                    val result = mutableListOf<StremioSub>()
                    for (url in urlsToTry) {
                        try {
                            val request = Request.Builder().url(url).build()
                            val response = okHttpClient.newCall(request).execute()
                            if (response.isSuccessful && response.body != null) {
                                val text = response.body!!.string()
                                val data = Mapper.json.decodeFromString<StremioResponse>(text)
                                result.addAll(data.subtitles.map { it.copy(source = "stremio") })
                                if (data.subtitles.isNotEmpty()) break
                            }
                        } catch (e: Exception) {
                            Logger.log("StremioSubtitles(Stremio): url failed $url -> ${e.message}")
                        }
                    }
                    result
                }
            }

            if (providers.contains("OpenSubtitles")) {
                launchProvider("OpenSubtitles") {
                    OpenSubtitles.search(imdbId, season, episode, queryText)
                }
            }

            if (providers.contains("SubSource")) {
                launchProvider("SubSource") {
                    SubSourceSubtitles.getSubtitles(imdbId, episode, season)
                        .mapNotNull { sub ->
                            val downloadUrl = runCatching { SubSourceSubtitles.getDownloadUrl(sub) }.getOrNull()
                            downloadUrl?.let {
                                StremioSub(id = it, url = it, lang = sub.lang, source = "subsource")
                            }
                        }
                }
            }

            if (providers.contains("SubDL")) {
                launchProvider("SubDL") {
                    SubDLSubtitles.getSubtitles(imdbId, season, episode)
                }
            }
        }

    /** Original blocking fetch — keeps anime-mode and other callers unchanged. */
    suspend fun getSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        isTvSeries: Boolean,
        queryText: String? = null
    ): List<StremioSub> {
        val result = mutableListOf<StremioSub>()
        getSubtitlesStreaming(imdbId, season, episode, isTvSeries, queryText).collect { batch ->
            result.addAll(batch)
        }
        return result
    }

    /** Anime-mode overload — unwraps Media and delegates. */
    suspend fun getSubtitles(media: Media, season: Int, episode: Int): List<StremioSub> {
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val imdbId = media.idIMDB
        val isTvSeries = media.format != "MOVIE"
        val queryText = media.userPreferredName

        return getSubtitles(imdbId, season, episode, isTvSeries, queryText)
    }
}

@Serializable
data class StremioResponse(
    val subtitles: List<StremioSub> = emptyList()
)

@Serializable
data class StremioSub(
    val id: String,
    val url: String,
    val lang: String,
    val source: String = "online",
    val label: String? = null,
    val headers: Map<String, String> = emptyMap()
)
