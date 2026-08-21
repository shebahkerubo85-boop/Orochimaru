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

    suspend fun getSubtitles(media: Media, season: Int, episode: Int): List<StremioSub> {
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
        val imdbId = media.idIMDB

        return withContext(Dispatchers.IO) {
            if (imdbId == null) return@withContext emptyList()

            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<StremioSub>>>()

            if (providers.contains("Wyzie")) {
                jobs += async {
                    try {
                        val wyzieSubs = WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)
                        Logger.log("StremioSubtitles: Wyzie returned ${wyzieSubs.size} subs")
                        wyzieSubs.map {
                            StremioSub(id = it.id, url = it.url, lang = it.displayLabel, source = "wyzie")
                        }
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles: Wyzie error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("Stremio")) {
                jobs += async {
                    try {
                        Logger.log("StremioSubtitles: Fetching Stremio OpenSubtitles...")
                        val isMovie = media.format == "MOVIE"
                        val urlsToTry = if (isMovie) {
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
                                Logger.log("StremioSubtitles: url failed $url -> ${e.message}")
                            }
                        }
                        result
                    } catch (e: Exception) {
                        Logger.log("StremioSubtitles: Stremio error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("OpenSubtitles")) {
                jobs += async {
                    try {
                        Logger.log("StremioSubtitles: Fetching official OpenSubtitles...")
                        val subs = OpenSubtitles.search(imdbId, season, episode, media.userPreferredName)
                        Logger.log("OpenSubtitles: returned ${subs.size} subs")
                        subs
                    } catch (e: Exception) {
                        Logger.log("OpenSubtitles: Error - ${e.message}")
                        emptyList()
                    }
                }
            }

            if (providers.contains("SubSource")) {
                jobs += async {
                    try {
                        Logger.log("StremioSubtitles: Fetching SubSource...")
                        val subsourceSubs = SubSourceSubtitles.getSubtitles(imdbId, episode, season)
                        Logger.log("SubSource: returned ${subsourceSubs.size} subs")
                        subsourceSubs.mapNotNull { sub ->
                            val downloadUrl = runCatching { SubSourceSubtitles.getDownloadUrl(sub) }.getOrNull()
                            downloadUrl?.let {
                                StremioSub(id = it, url = it, lang = sub.lang, source = "subsource")
                            }
                        }
                    } catch (e: Exception) {
                        Logger.log("SubSource: Error - ${e.message}")
                        emptyList()
                    }
                }
            }

            jobs.awaitAll().flatten()
        }
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
