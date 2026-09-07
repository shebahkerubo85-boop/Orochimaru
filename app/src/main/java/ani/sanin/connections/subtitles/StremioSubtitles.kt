package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import ani.sanin.media.Media
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger

object StremioSubtitles {

    private const val BASE_URL = "https://opensubtitles-v3.strem.io/subtitles"

    /** CS3-compatible overload — takes raw IDs instead of Media object. */
    suspend fun getSubtitles(
        imdbId: String?,
        season: Int,
        episode: Int,
        isTvSeries: Boolean,
        queryText: String? = null
    ): List<StremioSub> {
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
        if (imdbId == null) return emptyList()

        return withContext(Dispatchers.IO) {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<StremioSub>>>()

            if (providers.contains("Wyzie")) {
                jobs += async {
                    try {
                        val subs = WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)
                        Logger.log("StremioSubtitles(Wyzie): ${subs.size} subs")
                        subs.map { StremioSub(id = it.id, url = it.url, lang = it.displayLabel, source = "wyzie") }
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles(Wyzie): error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("Stremio")) {
                jobs += async {
                    try {
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
                        Logger.log("StremioSubtitles(Stremio): ${result.size} subs")
                        result
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles(Stremio): error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("OpenSubtitles")) {
                jobs += async {
                    try {
                        val subs = OpenSubtitles.search(imdbId, season, episode, queryText)
                        Logger.log("StremioSubtitles(OpenSubtitles): ${subs.size} subs")
                        subs
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles(OpenSubtitles): error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("SubSource")) {
                jobs += async {
                    try {
                        val subsourceSubs = SubSourceSubtitles.getSubtitles(imdbId, episode, season)
                        Logger.log("StremioSubtitles(SubSource): ${subsourceSubs.size} subs")
                        subsourceSubs.mapNotNull { sub ->
                            val downloadUrl = runCatching { SubSourceSubtitles.getDownloadUrl(sub) }.getOrNull()
                            downloadUrl?.let {
                                StremioSub(id = it, url = it, lang = sub.lang, source = "subsource")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles(SubSource): error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("SubDL")) {
                jobs += async {
                    try {
                        val subs = SubDLSubtitles.getSubtitles(imdbId, season, episode)
                        Logger.log("StremioSubtitles(SubDL): ${subs.size} subs")
                        subs
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles(SubDL): error - ${e.message}")
                        emptyList()
                    }
                }
            }

            jobs.awaitAll().flatten()
        }
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
