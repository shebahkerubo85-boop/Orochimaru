package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ReanimeProvider : NativeAnimeParser() {

    override val name = "Reanime"
    override val saveName = "reanime"

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://reanime.to"

    override val knownServers: List<String> = listOf("Reanime")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8")
                val jsonStr = get("$baseUrl/api/v1/search?limit=20&q=$encoded")
                parseSearchResponse(jsonStr)
            } catch (e: Exception) {
                Logger.log("Reanime search error: ${e.message}")
                emptyList()
            }
        }
    }

    private suspend fun parseSearchResponse(jsonStr: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withContext emptyList()
                val data = obj["results"] as? JsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val titles = item["title"] as? JsonObject ?: return@mapNotNull null
                    val title = (titles["english"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: (titles["romaji"] as? JsonPrimitive)?.contentOrNull
                        ?: (titles["native"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    val animeId = (item["anime_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val anilistId = (item["anilist_id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { (it.toIntOrNull() ?: 0) > 0 }
                    val covers = item["cover_image"] as? JsonObject
                    val poster = (covers?.get("extra_large") as? JsonPrimitive)?.contentOrNull
                        ?: (covers?.get("large") as? JsonPrimitive)?.contentOrNull
                        ?: (covers?.get("medium") as? JsonPrimitive)?.contentOrNull
                    val total = (item["episodes"] as? JsonPrimitive)?.intOrNull
                    ShowResponse(
                        name = title,
                        link = animeId,
                        coverUrl = FileUrl(poster ?: defaultImage),
                        total = total,
                        extra = mutableMapOf("slug" to animeId).apply {
                            anilistId?.let { put("anilistId", it) }
                        },
                    )
                }
            } catch (e: Exception) {
                Logger.log("Reanime parseSearchResponse error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            val savedAnilistId = saved.extra?.get("anilistId")
            val savedEpisodes = loadEpisodes(
                saved.link,
                saved.extra,
                saved.sAnime ?: SAnime.create().apply { url = saved.link }
            )
            if (savedEpisodes.isNotEmpty() && !savedAnilistId.isNullOrBlank() && (savedAnilistId.toIntOrNull() ?: 0) > 0) {
                return saved
            }
            Logger.log("Reanime discarding invalid saved selection: id=${savedAnilistId ?: "missing"}")
        }
        setUserText("Searching Reanime: ${mediaObj.mainName()}")
        return searchWithFallback(mediaObj.mainName()).firstOrNull()
            ?: searchWithFallback(mediaObj.nameRomaji).firstOrNull()
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        if (animeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val jsonStr = get("$baseUrl/api/v1/anime/$animeLink/episodes?limit=2000")
                val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withContext emptyList()
                val data = obj["data"] as? JsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val epNum = (item["episode_number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    val playUrl = (item["play_url"] as? JsonPrimitive)?.contentOrNull
                    val title = (item["title"] as? JsonPrimitive)?.contentOrNull ?: "Episode ${epNum}"
                    Episode(
                        number = epNum.toString(),
                        link = playUrl ?: epNum.toString(),
                        title = title,
                        extra = extra
                    )
                }
            } catch (e: Exception) {
                Logger.log("Reanime loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val anilistId = extra?.get("anilistId")
        val episodeNumber = episodeLink.toIntOrNull()
        if (anilistId.isNullOrBlank() || (anilistId.toIntOrNull() ?: 0) <= 0 || episodeNumber == null) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val body = get("$baseUrl/api/flix/$anilistId/$episodeNumber?v=1")
                val obj = Mapper.json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()
                val servers = obj["servers"] as? JsonArray ?: return@withContext emptyList()
                val languages = if (selectDub) listOf("sub", "dub") else listOf("sub")
                servers.mapNotNull { serverElement ->
                    val server = serverElement as? JsonObject ?: return@mapNotNull null
                    val url = (server["dataLink"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val name = (server["serverName"] as? JsonPrimitive)?.contentOrNull ?: "Reanime"
                    val languageType = (server["dataType"] as? JsonPrimitive)?.contentOrNull
                    if (languageType !in languages) return@mapNotNull null
                    val resolved = StreamResolvers.flixCloud(url).firstOrNull()
                        ?: return@mapNotNull null
                    videoServer("$name · ${languageType?.uppercase() ?: "SUB"}", resolved.stream)
                }.distinctBy { it.embed.url }
            } catch (e: Exception) {
                Logger.log("Reanime loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun videoServer(name: String, resolved: ResolvedStream): VideoServer {
        val extraData = mutableMapOf<String, String>("format" to resolved.format.name)
        resolved.quality?.let { extraData["quality"] = it.toString() }
        if (resolved.subtitles.isNotEmpty()) {
            extraData["subtitles"] = JsonArray(resolved.subtitles.map { subtitle ->
                buildJsonObject {
                    put("url", subtitle.file.url)
                    put("language", language(subtitle.language))
                    put("type", subtitle.type.name.lowercase())
                }
            }).toString()
        }
        return VideoServer(name, ani.sanin.FileUrl(resolved.url, resolved.headers), extraData)
    }
}
