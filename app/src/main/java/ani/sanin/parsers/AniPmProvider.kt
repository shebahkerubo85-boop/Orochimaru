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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * ani.pm.
 *
 * By far the cleanest API of the three: every route is plain JSON, no HTML
 * scraping, no auth, no signature on the catalogue side.
 *
 *   - `/api/anime/search?q=` and `/api/anime/series/{id}?routes=e4` give the
 *     catalogue and the full episode list, including per-episode `sub`/`dub`
 *     flags and `subExact`/`dubExact`.
 *   - `/api/anime/playback-bootstrap/settlar/{id}?ep={n}&lang={sub|dub}`
 *     returns metadata plus, with `&backup=1`, a `backupEmbed` block holding
 *     an `embed.settlar.io` stream token.
 *
 * Two non-obvious header requirements, both discovered by probing:
 *
 *  1. The `embed.settlar.io` token endpoint only answers when `Origin` is
 *     `https://ani.pm`. `Referer` alone gets a 403.
 *  2. The returned `master`/`tracks` URLs are same-origin proxies whose last
 *     path segment is base64 of the *real* CDN URL. Those proxies 403 from
 *     anywhere, so the payload is decoded and the real CDN is requested
 *     directly with `Referer: https://megaplay.buzz/`, which is the only
 *     referer the CDN accepts.
 */
class AniPmProvider : NativeAnimeParser() {

    override val name = "Ani.pm"
    override val saveName = "anipm"
    override val language = "English"
    override val defaultBaseUrl = "https://ani.pm"
    override val knownServers = listOf("Sub", "Dub")

    private val api get() = "$baseUrl/api"

    override suspend fun search(query: String): List<ShowResponse> =
        withContext(Dispatchers.IO) {
            try {
                val body = anipmGet("$api/anime/search?q=${encode(query)}")
                val items = anipmJson(body)["items"] as? JsonArray ?: return@withContext emptyList()
                items.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val slug = (o["slug"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val native = (o["native"] as? JsonPrimitive)?.contentOrNull
                    val cover = (o["poster"] as? JsonPrimitive)?.contentOrNull
                    val eps = (o["episodeCount"] as? JsonPrimitive)?.intOrNull
                        ?: (o["subCount"] as? JsonPrimitive)?.intOrNull
                    val anilistId = (o["anilistId"] as? JsonPrimitive)?.contentOrNull
                    ShowResponse(
                        name = title,
                        link = "$baseUrl/anime/$slug",
                        coverUrl = FileUrl(
                            cover?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
                                ?: "$baseUrl/api/anime/cover?anilistId=${anilistId ?: id}"
                        ),
                        otherNames = listOfNotNull(native?.takeIf { it.isNotBlank() }),
                        total = eps,
                        extra = mutableMapOf("id" to id.toString())
                    )
                }
            } catch (e: Exception) {
                anipmLog("search error: ${e.message}")
                emptyList()
            }
        }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        try {
            val id = extra?.get("id")
                ?: animeLink.substringAfterLast('/').substringAfterLast('-', "")
            if (id.isBlank()) {
                anipmLog("no anime id for $animeLink")
                return@withContext emptyList()
            }
            val body = anipmGet("$api/anime/series/$id?routes=e4")
            val core = anipmJson(body)
            val eps = core["episodes"] as? JsonArray ?: return@withContext emptyList()
            val episodes = eps.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val num = (o["number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val sub = (o["sub"] as? JsonPrimitive)?.booleanOrNull == true
                val dub = (o["dub"] as? JsonPrimitive)?.booleanOrNull == true
                val title = (o["title"] as? JsonPrimitive)?.contentOrNull?.trim()
                // Only offer a language that actually has this episode, so the
                // server list stays meaningful instead of 404ing at play time.
                val labels = buildList {
                    if (sub) add("sub")
                    if (dub) add("dub")
                }
                Episode(
                    number = num.toString(),
                    link = animeLink,
                    title = buildString {
                        if (!title.isNullOrBlank()) append(title)
                        if (labels.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(labels.joinToString("/") { it.uppercase() })
                        }
                    }.ifBlank { "Episode $num" },
                    extra = mutableMapOf(
                        "id" to id,
                        "ep" to num.toString(),
                        "sub" to sub.toString(),
                        "dub" to dub.toString()
                    )
                )
            }
            episodes.sortedBy { it.number.toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            anipmLog("loadEpisodes error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        try {
            val id = extra?.get("id") ?: return@withContext emptyList()
            val ep = extra?.get("ep") ?: return@withContext emptyList()
            val servers = mutableListOf<VideoServer>()
            val channels = buildList {
                if (extra["sub"] != "false") add("sub")
                if (extra["dub"] == "true") add("dub")
            }
            for (channel in channels) {
                servers.add(
                    VideoServer(
                        name = channel.replaceFirstChar { it.uppercase() },
                        embed = FileUrl(
                            "$baseUrl/anime/playback-bootstrap/settlar/$id?ep=$ep&lang=$channel&backup=1"
                        ),
                        extraData = mapOf("channel" to channel, "id" to id, "ep" to ep)
                    )
                )
            }
            servers
        } catch (e: Exception) {
            anipmLog("loadVideoServers error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor =
        AniPmExtractor(server)

    companion object {
        /**
         * The proxy paths are `/backup/v1/h/<base64>.<sig>.m3u8`; the first dot
         * segment is base64 of the real CDN URL. Decoding it lets the player hit
         * the CDN with the referer that CDN actually accepts, instead of the
         * proxy that refuses every caller.
         */
        fun anipmUnproxy(url: String, kind: String): String? = try {
            val tail = url.substringAfter("/backup/v1/$kind/").substringBefore('.')
            if (tail.isBlank()) null
            else String(Base64.getDecoder().decode(tail), Charsets.UTF_8)
                .takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            anipmLog("unproxy failed: ${e.message}")
            null
        }
    }
}

class AniPmExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            // 1. bootstrap -> backupEmbed.direct.stream token
            val boot = anipmGet(server.embed.url)
            val root = anipmJson(boot)
            val direct = (root["backupEmbed"] as? JsonObject)
                ?.get("direct") as? JsonObject
            val token = (direct?.get("stream") as? JsonPrimitive)?.contentOrNull
            if (token.isNullOrBlank()) {
                val reason = ((root["backupEmbed"] as? JsonObject)
                    ?.get("reason") as? JsonPrimitive)?.contentOrNull
                anipmLog("no stream token${reason?.let { " ($it)" } ?: ""} for ${server.embed.url}")
                return@withContext VideoContainer(emptyList())
            }

            // 2. token -> {master, tracks, skip}. Needs Origin: https://ani.pm
            //    or the endpoint 403s regardless of Referer.
            val payload = anipmGet(
                token,
                headers = mapOf(
                    "Referer" to "https://ani.pm/",
                    "Origin" to "https://ani.pm",
                    "Accept" to "application/json"
                )
            )
            val resolved = anipmJson(payload)
            val proxyMaster = (resolved["master"] as? JsonPrimitive)?.contentOrNull
            if (proxyMaster.isNullOrBlank()) {
                anipmLog("no master in token payload")
                return@withContext VideoContainer(emptyList())
            }

            // 3. decode the proxy path to the real CDN and use megaplay's origin
            //    as Referer, which is the only one the CDN serves.
            val mediaHeaders = mapOf(
                "Referer" to "https://megaplay.buzz/",
                "Origin" to "https://megaplay.buzz"
            )
            val realMaster = AniPmProvider.anipmUnproxy(proxyMaster, "h") ?: proxyMaster

            val subtitles = (resolved["tracks"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val raw = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val lang = (o["lang"] as? JsonPrimitive)?.contentOrNull ?: "en"
                val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: "Subtitles"
                val isDefault = (o["default"] as? JsonPrimitive)?.booleanOrNull == true
                val real = AniPmProvider.anipmUnproxy(raw, "v") ?: raw
                Subtitle(
                    language = if (isDefault) lang else "$lang (${label.take(20)})",
                    file = FileUrl(real, mediaHeaders),
                    type = SubtitleType.VTT
                )
            }.orEmpty()

            anipmLog("master ${realMaster.take(100)} + ${subtitles.size} subs")
            val hls = anipmResolveVariants(realMaster, mediaHeaders)
            val videos = if (hls.videos.isEmpty()) {
                listOf(Video(null, VideoType.M3U8, FileUrl(realMaster, mediaHeaders)))
            } else {
                hls.videos
            }
            VideoContainer(videos, subtitles, audioTracks = hls.audioTracks)
        } catch (e: Exception) {
            anipmLog("extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

private data class AniPmHlsResult(
    val videos: List<Video>,
    val audioTracks: List<Track>
)

private fun anipmResolveVariants(master: String, headers: Map<String, String>): AniPmHlsResult =
    try {
        val body = anipmGet(master, headers = headers)
        val base = URI(master)
        val audio = mutableListOf<Track>()
        val videos = mutableListOf<Video>()
        val lines = body.lines()
        for (raw in lines) {
            val t = raw.trim()
            if (!t.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            if (!t.contains("TYPE=AUDIO", ignoreCase = true)) continue
            val name = t.substringAfter("NAME=", "").substringAfter('"', "").substringBefore('"', "").trim()
            val lang = name.ifBlank {
                t.substringAfter("LANGUAGE=", "").substringAfter('"', "").substringBefore('"', "").trim()
            }.ifBlank { "und" }
            val uri = t.substringAfter("URI=", "").substringAfter('"', "").substringBefore('"', "").trim()
            if (uri.isNotBlank()) {
                audio.add(
                    Track(
                        url = if (uri.startsWith("http")) uri else base.resolve(uri).toString(),
                        lang = lang
                    )
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
                                FileUrl(
                                    if (next.startsWith("http")) next else base.resolve(next).toString(),
                                    headers
                                )
                            )
                        )
                        break
                    }
                    j++
                }
                i = j
            } else i++
        }
        AniPmHlsResult(videos, audio)
    } catch (e: Exception) {
        anipmLog("variant parse failed: ${e.message}")
        AniPmHlsResult(emptyList(), emptyList())
    }

private fun anipmJson(body: String): JsonObject =
    runCatching { Mapper.json.parseToJsonElement(body).jsonObject }.getOrElse {
        anipmLog("bad JSON: ${it.message}")
        JsonObject(emptyMap())
    }

private fun anipmGet(url: String, headers: Map<String, String> = emptyMap()): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .get().build()
    okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        return body
    }
}

private fun anipmLog(message: String) {
    android.util.Log.d("AniPm", message)
    Logger.log(message)
}
