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

class AniVaultProvider : NativeAnimeParser() {

    override val name = "AniVault"
    override val saveName = "AniVault"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = false

    override val defaultBaseUrl = "https://anivault-api.vercel.app"

    /**
     * AniVault API v2 no longer exposes per-source server lists or dub/sub
     * toggles; it returns a single play_url per episode and resolves streams
     * via /stream. The source preference from v1 is kept only so existing user
     * settings don't break, but it no longer changes behaviour.
     */
    override val knownServers: List<String> = listOf("AniVault")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8")
                val jsonStr = get("$baseUrl/search?q=$encoded")
                val arr = Mapper.json.parseToJsonElement(jsonStr) as? JsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val slug = (obj["slug"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: slug
                    val poster = (obj["poster"] as? JsonPrimitive)?.contentOrNull
                    val total = (obj["episodes"] as? JsonPrimitive)?.intOrNull
                    ShowResponse(
                        name = title,
                        link = id,
                        coverUrl = FileUrl(poster ?: defaultImage),
                        total = total,
                        extra = mutableMapOf("slug" to slug)
                    )
                }
            } catch (e: Exception) {
                Logger.log("AniVault search error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) return saved
        setUserText("Searching AniVault: ${mediaObj.mainName()}")
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
                val jsonStr = get("$baseUrl/anime/$animeLink/episodes")
                val arr = Mapper.json.parseToJsonElement(jsonStr) as? JsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val num = (obj["episode"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    val playUrl = (obj["play_url"] as? JsonPrimitive)?.contentOrNull
                    Episode(
                        number = num.toString(),
                        link = playUrl ?: num.toString(),
                        title = "Episode $num",
                        extra = extra
                    )
                }
            } catch (e: Exception) {
                Logger.log("AniVault loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        // When link already carries a play_url (v2), use /stream to resolve sources.
        val playUrl = episodeLink.takeIf { it.startsWith("http") } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(playUrl, "utf-8")
                val body = get("$baseUrl/stream?episode_url=$encoded")
                val obj = Mapper.json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()

                val servers = mutableListOf<VideoServer>()
                // v2 may return a direct m3u8 or a list of sources.
                val directM3u8 = (obj["m3u8"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["url"] as? JsonPrimitive)?.contentOrNull
                if (!directM3u8.isNullOrBlank()) {
                    servers.add(buildServer("AniVault", directM3u8, obj))
                }
                val sources = obj["sources"] as? JsonArray
                sources?.forEach { srcEl ->
                    val src = srcEl as? JsonObject ?: return@forEach
                    val url = (src["url"] as? JsonPrimitive)?.contentOrNull
                        ?: (src["file"] as? JsonPrimitive)?.contentOrNull
                        ?: return@forEach
                    if (url.isBlank()) return@forEach
                    val quality = (src["quality"] as? JsonPrimitive)?.contentOrNull
                        ?: (src["label"] as? JsonPrimitive)?.contentOrNull
                        ?: "Default"
                    servers.add(buildServer(quality, url, obj))
                }
                servers.distinctBy { it.embed.url }
            } catch (e: Exception) {
                Logger.log("AniVault loadVideoServers error: ${e.message}")
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
