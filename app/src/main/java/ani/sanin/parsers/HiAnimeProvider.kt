package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * HiAnime (hianime.at).
 *
 * The site is a thin Laravel app that front-ends upstream players. Nothing is
 * encrypted:
 *
 *  - `/api/theme/episode/list/{animeId}` returns the episode HTML.
 *  - `/api/theme/episode/servers?episodeId={id}` returns a short server list
 *    where every entry carries `data-hash`, a base64 upstream embed URL.
 *  - Each upstream is a static page whose `window.__P` payload is
 *    base64 + repeating-XOR with the literal key `otaku-embed-v1` (published in
 *    the upstream's own `/core/obfuscate.js`). That JSON carries `src`, the
 *    master.m3u8, plus a subtitle list.
 *
 * The HLS hosts reject requests without the upstream embed's own origin as
 * Referer (plain curl gets a 403), so the media headers have to be carried
 * through to the player or the stream silently never plays.
 */
class HiAnimeProvider : NativeAnimeParser() {

    override val name = "HiAnime"
    override val saveName = "hianime"
    override val language = "English"
    override val defaultBaseUrl = "https://hianime.at"
    override val knownServers = listOf("ZokoAnime", "HD-1")

    private val api get() = "$baseUrl/api/theme"

    override suspend fun search(query: String): List<ShowResponse> =
        withContext(Dispatchers.IO) {
            try {
                val html = get(
                    "$baseUrl/search?q=${encode(query)}",
                    referer = "$baseUrl/",
                    accept = "text/html,application/xhtml+xml"
                )
                val seen = mutableSetOf<String>()
                SHOW_LINK.findAll(html).mapNotNull { match ->
                    val href = match.groupValues[1].trim()
                    val title = decodeEntities(match.groupValues[2]).trim()
                    if (title.isBlank() || !seen.add(href)) return@mapNotNull null
                    val id = href.substringAfterLast('-', "").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ShowResponse(
                        name = title,
                        link = href,
                        coverUrl = FileUrl("$baseUrl/theme/images/favicon-192.png"),
                        total = null,
                        extra = mutableMapOf("id" to id)
                    )
                }.toList()
            } catch (e: Exception) {
                hiLog("search error: ${e.message}")
                emptyList()
            }
        }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        try {
            val id = extra?.get("id") ?: animeLink.substringAfterLast('-', "")
            val body = hiGet("$api/episode/list/$id", referer = "$baseUrl/")
            // The endpoint answers with a JSON envelope ({"status":..,"html":".."}),
            // so the markup has to be unescaped before the row regex can see it.
            val markup = hiEpisodeHtml(body)
            if (markup.isBlank()) {
                hiLog("episode list empty for id=$id (body starts: ${body.take(80)})")
            }
            val episodes = mutableListOf<Episode>()
            EPISODE_ITEM.findAll(markup).forEach { match ->
                val epId = match.groups["id"]?.value.orEmpty().trim()
                val number = match.groups["number"]?.value.orEmpty().trim().toIntOrNull()
                if (epId.isBlank() || number == null) return@forEach
                val title = decodeEntities(match.groups["title"]?.value.orEmpty()).trim()
                episodes.add(
                    Episode(
                        number = number.toString(),
                        link = "$baseUrl/watch/${animeLink.substringAfterLast('/')}",
                        title = title.ifBlank { "Episode $number" },
                        extra = mutableMapOf(
                            "animeId" to id,
                            "episodeId" to epId,
                            "number" to number.toString()
                        )
                    )
                )
            }
            episodes.sortedBy { it.number.toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            hiLog("loadEpisodes error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        try {
            val episodeId = extra?.get("episodeId")
                ?: return@withContext emptyList()
            val body = hiGet("$api/episode/servers?episodeId=$episodeId", referer = episodeLink)
            val seen = mutableSetOf<String>()
            SERVER_ITEM.findAll(body).mapNotNull { match ->
                val channel = match.groups["channel"]?.value.orEmpty().trim().lowercase()
                val name = decodeEntities(match.groups["name"]?.value.orEmpty()).trim()
                val hash = match.groups["hash"]?.value.orEmpty().trim()
                if (hash.isBlank() || name.isBlank()) return@mapNotNull null
                val embed = hiDecodeHash(hash) ?: return@mapNotNull null
                // "ZokoAnime SUB" and "ZokoAnime DUB" are the same upstream channel
                // split by language, so key on the decoded path's language segment
                // instead of showing each as a separate server.
                if (!seen.add(embed)) return@mapNotNull null
                val family = hiHostFamily(embed) ?: "Upstream"
                VideoServer(
                    name = if (channel == "dub") "$family DUB" else family,
                    embed = FileUrl(embed, mapOf("Referer" to "$baseUrl/")),
                    extraData = mapOf("host" to family, "channel" to channel)
                )
            }.toList()
        } catch (e: Exception) {
            hiLog("loadVideoServers error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor =
        HiAnimeZokoExtractor(server)


    companion object {
        private val SHOW_LINK = Regex(
            """href="(https://hianime\.at/watch/[a-z0-9-]+)"[^>]*title="([^"]*)"""",
            RegexOption.IGNORE_CASE
        )

        private val EPISODE_ITEM = Regex(
            """<a title="(?<title>[^"]*)"[^>]*class="[^"]*ssl-item[^"]*"[^>]*data-number="(?<number>[^"]*)"[^>]*data-id="(?<id>\d+)"""",
            RegexOption.IGNORE_CASE
        )

        private val SERVER_ITEM = Regex(
            """data-type="(?<channel>[^"]*)"[^>]*data-server-name="(?<name>[^"]*)""" +
                """[^>]*data-hash="(?<hash>[^"]*)"""",
            RegexOption.IGNORE_CASE
        )

        private fun hiDecodeHash(hash: String): String? = try {
            val decoded = String(Base64.getDecoder().decode(hash), Charsets.UTF_8)
            decoded.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            hiLog("hash decode failed: ${e.message}")
            null
        }

        fun hiHostFamily(embed: String): String? {
            val host = runCatching { URI(embed).host }.getOrNull()?.lowercase()?.removePrefix("www.")
                ?: return null
            return when {
                host.contains("zokoanime") -> "ZokoAnime"
                host.contains("megaplay") -> "HD-1"
                else -> host.split(".").getOrNull(1)?.replaceFirstChar { it.uppercase() }
            }
        }
    }
}

/**
 * Upstream players on HiAnime (zokoanime.video and megaplay.buzz both serve the
 * same build). The stream descriptor is a repeating-XOR + base64 blob.
 */
private const val HI_OBF_KEY = "otaku-embed-v1"

/**
 * Mirrors upstream `deobfuscate`: btoa(xor(atob(blob))).
 *
 * The JS does `decodeURIComponent(escape(xor(atob(blob))))`, i.e. the XOR runs
 * over UTF-8 bytes. Kotlin has no byte-string type, so the byte array is turned
 * into a Latin-1 string and re-decoded as UTF-8 to reproduce the round trip.
 */
private fun hiDeobfuscate(blob: String): String? = try {
    val raw = Base64.getDecoder().decode(blob)
    val key = HI_OBF_KEY.toByteArray(Charsets.UTF_8)
    val out = ByteArray(raw.size)
    for (i in raw.indices) {
        out[i] = (raw[i].toInt() xor key[i % key.size].toInt()).toByte()
    }
    String(out, Charsets.UTF_8)
} catch (e: Exception) {
    hiLog("deobfuscate failed: ${e.message}")
    null
}

class HiAnimeZokoExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val embed = server.embed.url
            val page = hiGet(embed, referer = "https://hianime.at/")
            val payload = HI_PAYLOAD.find(page)?.groupValues?.get(1)
            if (payload.isNullOrBlank()) {
                hiLog("no __P payload on $embed")
                return@withContext VideoContainer(emptyList())
            }
            val json = hiDeobfuscate(payload)
                ?: return@withContext VideoContainer(emptyList())
            val root = runCatching {
                Mapper.json.parseToJsonElement(json) as? JsonObject
            }.getOrNull()
            if (root == null) {
                hiLog("payload is not a JSON object")
                return@withContext VideoContainer(emptyList())
            }

            val master = (root["src"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("http") }
                ?: return@withContext VideoContainer(emptyList()).also {
                    hiLog("payload has no src for $embed")
                }

            // The CDN only serves with the embed's own origin as Referer; without
            // it every variant 403s and playback never starts.
            val embedOrigin = hiOrigin(embed)
            val headers = mapOf("Referer" to "$embedOrigin/", "Origin" to embedOrigin)

            val subtitles = (root["subtitles"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val src = (o["src"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: "Subtitles"
                val isDefault = (o["default"] as? JsonPrimitive)?.booleanOrNull == true
                val lang = (o["lang"] as? JsonPrimitive)?.contentOrNull ?: "en"
                Subtitle(
                    language = if (isDefault) "en" else "$lang (${label.take(24)})",
                    file = FileUrl(src, headers),
                    type = SubtitleType.VTT
                )
            }.orEmpty()

            hiLog("got ${master.take(110)} with ${subtitles.size} subtitles")
            val hls = hiResolveVariants(master, headers)
            val videos = if (hls.videos.isEmpty()) {
                listOf(Video(null, VideoType.M3U8, FileUrl(master, headers)))
            } else {
                hls.videos
            }
            VideoContainer(videos, subtitles, audioTracks = hls.audioTracks)
        } catch (e: Exception) {
            hiLog("extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun hiResolveVariants(master: String, headers: Map<String, String>): HiHlsResult = try {
        val body = hiGet(master, referer = headers["Referer"])
        val base = URI(master)
        val audioTracks = mutableListOf<Track>()
        val videos = mutableListOf<Video>()
        val lines = body.lines()
        for (line in lines) {
            val t = line.trim()
            if (!t.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            if (!t.contains("TYPE=AUDIO", ignoreCase = true)) continue
            val name = t.substringAfter("NAME=", "").substringAfter('"', "").substringBefore('"', "").trim()
            val lang = name.ifBlank {
                t.substringAfter("LANGUAGE=", "").substringAfter('"', "").substringBefore('"', "").trim()
            }.ifBlank { "und" }
            val uri = t.substringAfter("URI=", "").substringAfter('"', "").substringBefore('"', "").trim()
            if (uri.isNotBlank()) {
                audioTracks.add(
                    Track(url = if (uri.startsWith("http")) uri else base.resolve(uri).toString(), lang = lang)
                )
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val q = Regex("RESOLUTION=\\d+[xX](\\d+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        videos.add(
                            Video(
                                q,
                                VideoType.M3U8,
                                FileUrl(if (next.startsWith("http")) next else base.resolve(next).toString(), headers)
                            )
                        )
                        break
                    }
                    j++
                }
                i = j
            } else i++
        }
        HiHlsResult(videos, audioTracks)
    } catch (e: Exception) {
        hiLog("variant parse failed: ${e.message}")
        HiHlsResult(emptyList(), emptyList())
    }
}

private data class HiHlsResult(
    val videos: List<Video>,
    val audioTracks: List<Track>
)

private val HI_PAYLOAD = Regex("""window\.__P\s*=\s*"([^"]+)"""")

private fun hiOrigin(url: String): String = runCatching {
    URI(url).let { "${it.scheme}://${it.authority}" }
}.getOrDefault(url.substringBefore('/', ""))

/**
 * `/api/theme/episode/list/{id}` answers `{"status":..,"totalItems":..,"html":"<a ..>"}`.
 * The episode markup lives inside that JSON string, so it must be unescaped before
 * [HiAnimeProvider] parses it. Falls back to the raw body when it is already HTML.
 */
private fun hiEpisodeHtml(body: String): String {
    val trimmed = body.trimStart()
    if (trimmed.startsWith('<')) return body
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault(body)
}

private fun hiGet(url: String, referer: String? = null): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        .get().build()
    okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        return body
    }
}

private fun hiLog(message: String) {
    android.util.Log.d("HiAnime", message)
    Logger.log(message)
}
