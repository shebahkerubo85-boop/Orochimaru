package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import ani.sanin.util.AnimeJLLog
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.net.URI

import android.util.Base64
import ani.sanin.others.JsUnpacker
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.util.Log
import androidx.fragment.app.FragmentActivity
import ani.sanin.currContext
import ani.sanin.others.webview.VideoCatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimeJLProvider : NativeAnimeParser() {

    override val name = "AnimeJL"
    override val saveName = "animejl"
    override val defaultBaseUrl = "https://www.anime-jl.net"
    override val knownServers = listOf("Voe", "Mp4Upload", "YourUpload", "Okru", "StreamWish", "Uqload", "VidHide", "Universal")

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
                ajlLog("AnimeJL search error: ${e.message}")
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
                    val thumb = if (coverPath.isNotEmpty()) "$baseUrl/storage/$coverPath" else defaultImage
                    Episode(
                        number = number.toString(),
                        link = "$baseUrl/anime/$id/$slug/episodio-$number",
                        thumbnail = thumb,
                        extra = mutableMapOf("id" to id, "slug" to slug)
                    )
                }.sortedBy { it.number.toIntOrNull() ?: 0 }.toList()
            } catch (e: Exception) {
                ajlLog("AnimeJL loadEpisodes error: ${e.message}")
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
                ajlLog("AnimeJL loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return when (server.extraData?.get("host")) {
            "Voe" -> AnimeJLWebViewExtractor(server)
            "Mp4Upload" -> Mp4UploadExtractor(server)
            "StreamWish" -> AnimeJLStreamWishExtractor(server)
            "YourUpload" -> YourUploadExtractor(server)
            "Okru" -> AnimeJLWebViewExtractor(server)
            "Uqload" -> AnimeJLUqloadExtractor(server)
            "VidHide" -> AnimeJLVidHideExtractor(server)
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
                host.contains("streamwish") || host.contains("playerwish") ||
                    host.contains("wishonly") || host.contains("filemoon") -> "StreamWish"
                host.contains("uqload") -> "Uqload"
                host.contains("vidhide") -> "VidHide"
                else -> null
            }
        }
    }
}

class YourUploadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://www.yourupload.com/"
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val baseData = doc.select("script").firstOrNull { it.data().contains("jwplayerOptions") }?.data()
            val mp4 = baseData?.substringAfter("file: '")?.substringBefore("',")?.takeIf { it.startsWith("http") }
            if (mp4.isNullOrBlank()) {
                ajlLog("AnimeJL YourUpload: no mp4 found in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL YourUpload: got ${mp4.take(120)}")
                VideoContainer(
                    listOf(Video(null, VideoType.CONTAINER, FileUrl(mp4, mapOf("Referer" to referer))))
                )
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL YourUpload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class OkruExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://ok.ru/"
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val videoString = doc.selectFirst("div[data-options]")?.attr("data-options")
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL Okru: no data-options in ${server.embed.url}")
                }
            val videos = when {
                "ondemandHls" in videoString -> listOf(
                    Video(null, VideoType.M3U8, FileUrl(extractOkruLink(videoString, "ondemandHls"), mapOf("Referer" to referer)))
                )
                "ondemandDash" in videoString -> listOf(
                    Video(null, VideoType.DASH, FileUrl(extractOkruLink(videoString, "ondemandDash"), mapOf("Referer" to referer)))
                )
                else -> okruVideosFromJson(videoString, referer)
            }
            if (videos.isEmpty()) {
                ajlLog("AnimeJL Okru: no video URLs parsed in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL Okru: ${videos.size} options")
                VideoContainer(videos)
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL Okru extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun extractOkruLink(videoString: String, attr: String): String =
        videoString.substringAfter("$attr\\\":\\\"").substringBefore("\\\"")
            .replace("\\u0026", "&")

    private fun okruVideosFromJson(videoString: String, referer: String): List<Video> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"").substringBefore("]")
        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val url = extractOkruLink(it, "url")
            val qualityLabel = it.substringBefore("\\\"")
            if (url.startsWith("https://")) {
                Video(okruQuality(qualityLabel), VideoType.M3U8, FileUrl(url, mapOf("Referer" to referer)))
            } else null
        }
    }

    private fun okruQuality(label: String): Int? = when (label) {
        "mobile" -> 144
        "lowest" -> 240
        "low" -> 360
        "sd" -> 480
        "hd" -> 720
        "full" -> 1080
        "quad" -> 1440
        "ultra" -> 2160
        else -> label.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()
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
                ajlLog("AnimeJL Universal: no streams found in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL Universal: ${allUrls.size} streams found")
                VideoContainer(allUrls.map { url ->
                    val format = if (url.contains(".m3u8", ignoreCase = true)) VideoType.M3U8 else VideoType.CONTAINER
                    Video(null, format, FileUrl(url, mapOf("Referer" to ajlOrigin(url))))
                })
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL Universal extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLVoeExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://voe.sx/"
            // OkHttp follows the JS redirect (voe.sx -> nicolehappyoutside.com)
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val script = doc.select("script").firstOrNull {
                val d = it.data()
                d.contains("sources") || d.contains("wc0") || d.contains("var source")
            }?.data() ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL Voe: no player script in ${server.embed.url}")
            }
            val (link, isHls) = when {
                script.contains("sources") -> {
                    val raw = script.substringAfter("hls': '").substringBefore("'")
                    val url = if (VOE_LINK_REGEX.matches(raw)) raw
                    else runCatching { String(Base64.decode(raw, Base64.DEFAULT)) }.getOrNull() ?: raw
                    url to url.contains(".m3u8", ignoreCase = true)
                }
                script.contains("wc0") -> {
                    val b64 = VOE_BASE64_REGEX.find(script)?.value?.removeSurrounding("'") ?: ""
                    val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull() ?: ""
                    val file = (runCatching {
                        Mapper.json.parseToJsonElement(decoded) as? JsonObject
                    }.getOrNull()?.get("file") as? JsonPrimitive)?.contentOrNull ?: decoded
                    file to file.contains(".m3u8", ignoreCase = true)
                }
                else -> {
                    val raw = script.substringAfter("var source='").substringBefore("'")
                    raw to raw.contains(".m3u8", ignoreCase = true)
                }
            }
            if (link.isBlank() || link.contains("test-videos.co.uk", ignoreCase = true)) {
                ajlLog("AnimeJL Voe: no usable link (placeholder?) in ${server.embed.url}")
                return@withContext VideoContainer(emptyList())
            }
            val headers = mapOf("Referer" to ajlOrigin(link))
            val format = if (isHls) VideoType.M3U8 else VideoType.CONTAINER
            ajlLog("AnimeJL Voe: resolved ${link.take(120)}")
            VideoContainer(listOf(Video(null, format, FileUrl(link, headers))))
        } catch (e: Exception) {
            ajlLog("AnimeJL Voe extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    companion object {
        private val VOE_LINK_REGEX = Regex("(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])")
        private val VOE_BASE64_REGEX = Regex("'.*'")
    }
}

class AnimeJLUqloadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://uqload.ws/"
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("sources:") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL Uqload: no sources script in ${server.embed.url}")
            }
            val videoUrl = script.substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.startsWith("http") }
                ?: script.substringAfter("sources: [\"").substringBefore('"')
                    .takeIf { it.startsWith("http") }
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL Uqload: no url in sources")
                }
            ajlLog("AnimeJL Uqload: ${videoUrl.take(120)}")
            VideoContainer(listOf(Video(null, VideoType.CONTAINER, FileUrl(videoUrl, mapOf("Referer" to ajlOrigin(videoUrl))))))
        } catch (e: Exception) {
            ajlLog("AnimeJL Uqload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLVidHideExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("m3u8") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL VidHide: no m3u8 script in ${server.embed.url}")
            }
            val master = script.substringAfter("source").substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.isNotBlank() && it.contains(".m3u8", ignoreCase = true) }
                ?: return@withContext VideoContainer(emptyList()).also { ajlLog("AnimeJL VidHide: no master") }
            ajlLog("AnimeJL VidHide: ${master.take(120)}")
            VideoContainer(ajlResolveHls(master, mapOf("Referer" to referer)))
        } catch (e: Exception) {
            ajlLog("AnimeJL VidHide extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLStreamWishExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("m3u8") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL StreamWish: no m3u8 script in ${server.embed.url}")
            }
            val master = script.substringAfter("source").substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.isNotBlank() && it.contains(".m3u8", ignoreCase = true) }
                ?: return@withContext VideoContainer(emptyList()).also { ajlLog("AnimeJL StreamWish: no master") }
            ajlLog("AnimeJL StreamWish: ${master.take(120)}")
            VideoContainer(ajlResolveHls(master, mapOf("Referer" to referer)))
        } catch (e: Exception) {
            ajlLog("AnimeJL StreamWish extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

private fun pickScript(doc: Document, marker: (String) -> Boolean): String? {
    for (raw in doc.select("script").map { it.data() }) {
        val unpacked = if (raw.contains("eval(function(p,a,c")) {
            JsUnpacker(raw).let { if (it.detect()) it.unpack() ?: raw else raw }
        } else raw
        if (marker(unpacked)) return unpacked
    }
    return null
}

private fun ajlResolveHls(masterUrl: String, headers: Map<String, String>): List<Video> {
    return try {
        val body = ajlGet(masterUrl, headers["Referer"])
        val lines = body.lines()
        val base = URI(masterUrl)
        val hasAudioGroup = lines.any {
            it.trim().startsWith("#EXT-X-MEDIA:", ignoreCase = true) && it.contains("TYPE=AUDIO", ignoreCase = true)
        }
        if (hasAudioGroup) return listOf(Video(null, VideoType.M3U8, FileUrl(masterUrl, headers)))
        val videos = mutableListOf<Video>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val quality = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        val variant = if (next.startsWith("http")) next else base.resolve(next).toString()
                        videos.add(Video(quality, VideoType.M3U8, FileUrl(variant, headers)))
                        break
                    }
                    j++
                }
                i = j
            } else i++
        }
        if (videos.isEmpty()) listOf(Video(null, VideoType.M3U8, FileUrl(masterUrl, headers)))
        else videos
    } catch (e: Exception) {
        listOf(Video(null, VideoType.M3U8, FileUrl(masterUrl, headers)))
    }
}

private fun ajlLog(message: String) {
    Log.d("AnimeJL", message)
    Logger.log(message)
    AnimeJLLog.write(message)
}

private suspend fun ajlWebViewCatch(embedUrl: String, referer: String?): List<String> {
    return withContext(Dispatchers.IO) {
        var result: Map<String, String>? = null
        val latch = CountDownLatch(1)
        val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
        val dialog = VideoCatcher(FileUrl(embedUrl, headers))
        dialog.callback = { result = it; latch.countDown() }
        val shown = withContext(Dispatchers.Main) {
            val activity = currContext() as? FragmentActivity
            val fm = activity?.supportFragmentManager
            if (fm != null) { dialog.show(fm, "animejl-webview-catcher"); true } else false
        }
        if (!shown) {
            ajlLog("AnimeJL WebView: no foreground activity to show dialog")
            return@withContext emptyList()
        }
        val captured = latch.await(45, TimeUnit.SECONDS)
        if (!captured) {
            withContext(Dispatchers.Main) { if (dialog.isAdded) dialog.dismiss() }
            ajlLog("AnimeJL WebView: timed out after 45s for $embedUrl")
        }
        result?.get("videos")?.split("\n")?.mapNotNull { u -> u.takeIf { it.isNotBlank() } } ?: emptyList()
    }
}

class AnimeJLWebViewExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val static = when (server.extraData?.get("host")) {
                "Okru" -> OkruExtractor(server).extract().videos
                "Voe" -> AnimeJLVoeExtractor(server).extract().videos
                else -> null
            }
            val referer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            if (!static.isNullOrEmpty()) {
                ajlLog("AnimeJL WebView(${server.name}): static extraction succeeded")
                return@withContext VideoContainer(static)
            }
            ajlLog("AnimeJL WebView(${server.name}): static empty, launching WebView for ${server.embed.url}")
            val urls = ajlWebViewCatch(server.embed.url, referer)
            if (urls.isEmpty()) {
                ajlLog("AnimeJL WebView(${server.name}): no media captured")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL WebView(${server.name}): captured ${urls.size} url(s)")
                VideoContainer(urls.map { url ->
                    val fmt = when {
                        url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
                        url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
                        else -> VideoType.CONTAINER
                    }
                    Video(null, fmt, FileUrl(url, mapOf("Referer" to referer)))
                })
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL WebView(${server.name}) extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

private fun ajlGet(url: String, referer: String? = null): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
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
