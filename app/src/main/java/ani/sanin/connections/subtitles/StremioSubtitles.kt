package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import ani.sanin.media.Media
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger

object StremioSubtitles {

    // The free Stremio OpenSubtitles v3 endpoint
    private const val BASE_URL = "https://opensubtitles-v3.strem.io/subtitles"

    suspend fun getSubtitles(media: Media, season: Int, episode: Int): List<StremioSub> {
        // Check if Online Subtitles are enabled
        val enabled = PrefManager.getVal<Boolean>(PrefName.OnlineSubtitlesEnabled)
        if (!enabled) return emptyList()

        val providers = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleProviders)
        val allSubs = mutableListOf<StremioSub>()

        return withContext(Dispatchers.IO) {
            // 1. Try Wyzie if enabled
            if (providers.contains("Wyzie")) {
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val wyzieSubs = WyzieSubtitles.getWyzieSubtitles(imdbId, season, episode)
                        Logger.log("StremioSubtitles: Wyzie returned ${wyzieSubs.size} subs")
                        if (wyzieSubs.isNotEmpty()) {
                            val mapped = wyzieSubs.map {
                                StremioSub(
                                    id = it.id,
                                    url = it.url,
                                    lang = it.displayLabel,
                                    source = "wyzie"
                                )
                            }
                            allSubs.addAll(mapped)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Try OpenSubtitles (Stremio proxy) if enabled
            if (providers.contains("Stremio")) {
                Logger.log("StremioSubtitles: Fetching Stremio OpenSubtitles...")
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val isMovie = media.format == "MOVIE"
                        val urlsToTry = mutableListOf<String>()
                        if (isMovie) {
                            urlsToTry.add("$BASE_URL/movie/$imdbId.json")
                        } else {
                            urlsToTry.add("$BASE_URL/episode/$imdbId:$season:$episode.json")
                            urlsToTry.add("$BASE_URL/episode/$imdbId:1:$episode.json")
                            urlsToTry.add("$BASE_URL/episode/$imdbId:$episode.json")
                            urlsToTry.add("$BASE_URL/series/$imdbId:$season:$episode.json")
                        }

                        for (url in urlsToTry) {
                            try {
                                val request = Request.Builder().url(url).build()
                                val response = okHttpClient.newCall(request).execute()
                                if (response.isSuccessful && response.body != null) {
                                    val text = response.body!!.string()
                                    val data = Mapper.json.decodeFromString<StremioResponse>(text)
                                    allSubs.addAll(data.subtitles.map { it.copy(source = "stremio") })
                                    if (data.subtitles.isNotEmpty()) break
                                }
                            } catch (e: Exception) {
                                Logger.log("StremioSubtitles: url failed $url -> ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Try OpenSubtitles (official API) if enabled
            if (providers.contains("OpenSubtitles")) {
                Logger.log("StremioSubtitles: Fetching official OpenSubtitles...")
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val subs = OpenSubtitles.search(imdbId, season, episode)
                        Logger.log("OpenSubtitles: returned ${subs.size} subs")
                        allSubs.addAll(subs)
                    }
                } catch (e: Exception) {
                    Logger.log("OpenSubtitles: Error - ${e.message}")
                }
            }

            // 4. Try SubSource if enabled
            if (providers.contains("SubSource")) {
                Logger.log("StremioSubtitles: Fetching SubSource...")
                try {
                    val imdbId = media.idIMDB
                    if (imdbId != null) {
                        val subsourceSubs = SubSourceSubtitles.getSubtitles(imdbId, episode, season)
                        Logger.log("SubSource: returned ${subsourceSubs.size} subs")
                        for (sub in subsourceSubs) {
                            val downloadUrl = runCatching { SubSourceSubtitles.getDownloadUrl(sub) }.getOrNull()
                            if (downloadUrl != null) {
                                allSubs.add(
                                    StremioSub(
                                        id = downloadUrl,
                                        url = downloadUrl,
                                        lang = sub.lang,
                                        source = "subsource"
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.log("SubSource: Error - ${e.message}")
                }
            }

            allSubs
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
    val source: String = "online"
)
