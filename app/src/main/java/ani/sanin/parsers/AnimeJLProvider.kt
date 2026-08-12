package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.net.URI

class AnimeJLProvider : NativeAnimeParser() {

    override val name = "AnimeJL"
    override val saveName = "animejl"
    override val defaultBaseUrl = "https://www.anime-jl.net"
    override val knownServers = listOf("Voe", "Mp4Upload", "YourUpload", "Okru", "StreamWish", "Universal")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val json = get("$baseUrl/api/search?q=${encode(query)}", "$baseUrl/")
                val arr = Mapper.json.parseToJsonElement(json) as? JsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val slug = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val cover = (obj["cover"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val coverUrl = if (cover.isNotEmpty()) "$baseUrl/storage/$cover" else defaultImage
                    ShowResponse(
                        name = title,
                        link = "$id/$slug",
                        coverUrl = coverUrl,
                        extra = mutableMapOf("id" to id, "slug" to slug)
                    )
                }
            } catch (e: Exception) {
                Logger.log("AnimeJL search error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val id = extra?.get("id") ?: animeLink.substringBefore('/')
        val slug = extra?.get("slug") ?: animeLink.substringAfter('/')
        return withContext(Dispatchers.IO) {
            try {
                val html = get("$baseUrl/anime/$id/$slug", "$baseUrl/")
                val episodesPattern = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
                val episodesMatch = episodesPattern.find(html) ?: return@withContext emptyList()
                val episodesString = episodesMatch.groupValues[1]
                val episodePattern = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
                episodePattern.findAll(episodesString).mapNotNull { match ->
                    val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val coverPath = match.groupValues[3]
                    val thumb = if (coverPath.isNotEmpty()) "$baseUrl/storage/$coverPath" else null
                    Episode(
                        number = number.toString(),
                        link = "$baseUrl/anime/$id/$slug/episodio-$number",
                        thumbnail = thumb,
                        extra = mutableMapOf("id" to id, "slug" to slug)
                    )
                }.sortedBy { it.number.toIntOrNull() ?: 0 }.toList()
            } catch (e: Exception) {
                Logger.log("AnimeJL loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        return withContext(Dispatchers.IO) {
            try {
                val html = get(episodeLink, "$baseUrl/")
                val videoPattern = Regex("""video\[\d+\] = '<iframe src="(.*?)"""")
                val seen = mutableSetOf<String>()
                videoPattern.findAll(html).mapNotNull { match ->
                    val embed = match.groupValues[1].trim()
                    if (!embed.startsWith("http")) return@mapNotNull null
                    val family = hostFamily(embed) ?: return@mapNotNull null
                    if (!seen.add(family)) return@mapNotNull null
                    VideoServer(
                        family,
                        embed,
                        mapOf("referer" to "$baseUrl/", "host" to family)
                    )
                }.toList()
            } catch (e: Exception) {
                Logger.log("AnimeJL loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return when (server.extraData?.get("host")) {
            "Voe" -> VoeExtractor(server)
            "Mp4Upload" -> Mp4UploadExtractor(server)
            "StreamWish" -> SourceApiExtractor(server)
            "YourUpload" -> YourUploadExtractor(server)
            "Okru" -> OkruExtractor(server)
            else -> UniversalEmbedExtractor(server)
        }
    }

    companion object {
        fun hostFamily(embed: String): String? {
            val host = runCatching { URI(embed).host }
                .getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
            return when {
                host == "voe.sx" || host.endsWith(".voe.sx") -> "Voe"
                host == "mp4upload.com" || host.endsWith(".mp4upload.com") -> "Mp4Upload"
                host == "yourupload.com" || host.endsWith(".yourupload.com") -> "YourUpload"
                host == "ok.ru" || host.endsWith(".ok.ru") -> "Okru"
                host.contains("streamwish") || host.contains("playerwish") -> "StreamWish"
                else -> null
            }
        }
    }
}

class YourUploadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer")
            val page = ajlGet(server.embed.url, referer)
            val mp4 = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(page).map { it.groupValues[1].trim() }
                .firstOrNull { it.contains("yourupload", ignoreCase = true) }
                ?: Regex("""data-src=["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(page)?.groupValues?.get(1)
            if (mp4.isNullOrBlank()) {
                Logger.log("AnimeJL YourUpload: no mp4 found in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                Logger.log("AnimeJL YourUpload: got ${mp4.take(120)}")
                VideoContainer(
                    listOf(Video(null, VideoType.CONTAINER, FileUrl(mp4, mapOf("Referer" to ajlOrigin(mp4)))))
                )
            }
        } catch (e: Exception) {
            Logger.log("AnimeJL YourUpload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class OkruExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer")
            val page = ajlGet(server.embed.url, referer)
            val videosJson = Regex("""\"videos\"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                .find(page)?.groupValues?.get(1) ?: run {
                Logger.log("AnimeJL Okru: no videos array in ${server.embed.url}")
                return@withContext VideoContainer(emptyList())
            }
            val arr = Mapper.json.parseToJsonElement(videosJson) as? JsonArray ?: return@withContext VideoContainer(emptyList())
            val videos = arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
                val quality = name.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
                Video(quality, VideoType.M3U8, FileUrl(url, mapOf("Referer" to "https://ok.ru/")))
            }.sortedByDescending { it.quality ?: 0 }
            if (videos.isEmpty()) {
                Logger.log("AnimeJL Okru: no valid video URLs")
                VideoContainer(emptyList())
            } else {
                Logger.log("AnimeJL Okru: ${videos.size} quality options")
                VideoContainer(videos)
            }
        } catch (e: Exception) {
            Logger.log("AnimeJL Okru extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class UniversalEmbedExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer")
            val page = ajlGet(server.embed.url, referer)
            val hlsUrls = ajlHlsUrls(page)
            val mp4Urls = Regex("""https?://[^"'<>\s]+?\.mp4[^"'<>\s]*""", RegexOption.IGNORE_CASE)
                .findAll(page).map { it.value }.distinct().toList()
            val allUrls = hlsUrls + mp4Urls
            if (allUrls.isEmpty()) {
                Logger.log("AnimeJL Universal: no streams found in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                Logger.log("AnimeJL Universal: ${allUrls.size} streams found")
                VideoContainer(allUrls.map { url ->
                    val format = if (url.contains(".m3u8", ignoreCase = true)) VideoType.M3U8 else VideoType.CONTAINER
                    Video(null, format, FileUrl(url, mapOf("Referer" to ajlOrigin(url))))
                })
            }
        } catch (e: Exception) {
            Logger.log("AnimeJL Universal extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

private fun ajlGet(url: String, referer: String? = null): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .header("Accept", "application/json, */*")
        .get().build()
    okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        return body
    }
}

private fun ajlOrigin(url: String): String = runCatching {
    URI(url).let { "${it.scheme}://${it.authority}" }
}.getOrDefault(url.substringBefore('/', ""))

private fun ajlHlsUrls(html: String): List<String> = Regex(
    "(?:https?:)?(?:\\\\/|/)[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*",
    RegexOption.IGNORE_CASE
).findAll(html)
    .map { it.value.replace("\\/", "/").replace("\\u003d", "=") }
    .map { if (it.startsWith("//")) "https:$it" else it }
    .distinct()
    .toList()
