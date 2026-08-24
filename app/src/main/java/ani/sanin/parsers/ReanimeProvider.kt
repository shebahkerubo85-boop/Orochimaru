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

    override fun isDubAvailableSeparately(sourceLang: Int? = null): Boolean = false

    override val defaultBaseUrl = "https://reanime.to"

    override val knownServers: List<String> = listOf("Reanime")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8")
                val jsonStr = get("$baseUrl/api/v1/anime/search?q=$encoded")
                val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withContext emptyList()
                val data = obj["data"] as? JsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val title = (item["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val animeId = (item["anime_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val slug = (item["slug"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val poster = (item["thumbnail"] as? JsonPrimitive)?.contentOrNull
                    val total = (item["episodes"] as? JsonPrimitive)?.intOrNull
                    ShowResponse(
                        name = title,
                        link = animeId ?: slug,
                        coverUrl = FileUrl(poster ?: defaultImage),
                        total = total,
                        extra = mutableMapOf("slug" to slug)
                    )
                }
            } catch (e: Exception) {
                Logger.log("Reanime search error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) return saved
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
        // episodeLink may already carry play_url from /api/flix
        val playUrl = episodeLink.takeIf { it.startsWith("http") } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(playUrl, "utf-8")
                val body = get("$baseUrl/api/flix${episodeLink}?v=1")
                val obj = Mapper.json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()
                
                val servers = mutableListOf<VideoServer>()
                // flixcloud embed format: https://flixcloud.cc/e/{access_id}?v=1
                val directUrl = (obj["url"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["file"] as? JsonPrimitive)?.contentOrNull
                if (!directUrl.isNullOrBlank()) {
                    servers.add(buildServer("Reanime", directUrl, obj))
                }
                servers.distinctBy { it.embed.url }
            } catch (e: Exception) {
                Logger.log("Reanime loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun buildServer(name: String, streamUrl: String, obj: JsonObject): VideoServer {
        val extraData = mutableMapOf<String, String>()
        val subs = obj["subtitles"] as? JsonArray
        if (subs != null && subs.isNotEmpty()) {
            val subJson = subs.mapNotNull { sub ->
                val subObj = sub as? JsonObject ?: return@mapNotNull null
                val url = (subObj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val lang = (subObj["lang"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
                val code = language(lang)
                "{\"url\":\"${url.replace("\"", "\\\"")}\",\"language\":\"$code\",\"type\":\"vtt\"}"
            }
            if (subJson.isNotEmpty()) extraData["subtitles"] = "[${subJson.joinToString(",")}]"
        }
        obj["intro"]?.let { if (it is JsonObject || it is JsonPrimitive) extraData["intro"] = it.toString() }
        obj["outro"]?.let { if (it is JsonObject || it is JsonPrimitive) extraData["outro"] = it.toString() }
        return VideoServer(name, streamUrl, extraData)
    }
}
