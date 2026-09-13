package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URLEncoder
import kotlin.math.abs

class AniKotoProvider : NativeAnimeParser() {

    override val name = "AniKoto"
    override val saveName = "AniKoto"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://anikototv.to"
    override val knownServers = listOf("AniKoto", "MegaPlay")

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val html = get("$baseUrl/filter?keyword=${encode(query)}", "$baseUrl/", "text/html,*/*")
            parseSearchResults(html)
        } catch (e: Exception) {
            Logger.log("AniKoto search error: ${e.message}")
            emptyList()
        }
    }

    private fun parseSearchResults(html: String): List<ShowResponse> {
        val found = linkedMapOf<String, Triple<String, String, String>>() // slug, name, jp
        val re = Regex(
            """<a\s+class="name d-title"\s+href="https://anikototv\.to/watch/([^"/]+)(?:/ep-\d+)?"[^>]*data-jp="([^"]*)"[^>]*>([\s\S]*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in re.findAll(html)) {
            val slug = m.groupValues[1]
            val jp = m.groupValues[2].trim()
            val name = stripTags(m.groupValues[3])
            if (name.isBlank()) continue
            found.putIfAbsent(slug, Triple(name, jp, slug))
        }
        if (found.isEmpty()) {
            val reFallback = Regex(
                """<a\s+href="https://anikototv\.to/watch/([^"/]+)(?:/ep-\d+)?"[^>]*>([\s\S]*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (m in reFallback.findAll(html)) {
                found.putIfAbsent(m.groupValues[1], Triple(m.groupValues[1], "", m.groupValues[1]))
            }
        }
        return found.values.map { (name, _, slug) ->
            ShowResponse(
                name = name,
                link = slug,
                coverUrl = defaultImage,
                extra = mutableMapOf("slug" to slug)
            )
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            saveShowResponse(mediaObj.id, saved, true)
            return saved
        }
        setUserText("Searching AniKoto: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val primaryEn = mediaObj.mainName()
                val primaryRom = mediaObj.nameRomaji
                val synonyms = mediaObj.synonyms ?: emptyList()
                val keywords = buildList {
                    add(primaryEn)
                    if (primaryRom.isNotBlank()) add(primaryRom)
                    synonyms.forEach { add(it) }
                }.filter { it.isNotBlank() }.distinct().take(5)

                val allCandidates = linkedMapOf<String, ShowResponse>()
                for (k in keywords) {
                    runCatching { parseSearchResults(get("$baseUrl/filter?keyword=${encode(k)}", "$baseUrl/", "text/html,*/*")) }
                        .getOrDefault(emptyList()).forEach { allCandidates.putIfAbsent(it.link, it) }
                }
                val best = allCandidates.values.map { it to scoreCandidate(it.name, primaryEn, primaryRom, synonyms) }
                    .maxByOrNull { it.second }?.first ?: return@withContext null

                val watchHtml = runCatching { get("$baseUrl/watch/${best.link}", "$baseUrl/", "text/html,*/*") }.getOrNull()
                    ?: return@withContext null
                val showId = Regex("""data-id="(\d+)"""", RegexOption.IGNORE_CASE).find(watchHtml)?.groupValues?.get(1)
                    ?: return@withContext null
                val response = ShowResponse(
                    name = best.name,
                    link = showId,
                    coverUrl = defaultImage,
                    extra = mutableMapOf("slug" to best.link, "showId" to showId)
                )
                saveShowResponse(mediaObj.id, response)
                response
            } catch (e: Exception) {
                Logger.log("AniKoto autoSearch error: ${e.message}")
                null
            }
        }
    }

    private fun scoreCandidate(name: String, primaryEn: String, primaryRom: String, synonyms: List<String>): Int {
        var score = 0
        val normName = normalize(name)
        val normEn = normalize(primaryEn)
        val normRom = normalize(primaryRom)
        if (normEn.isNotEmpty() && normName == normEn) score += 1000
        if (normRom.isNotEmpty() && normName == normRom) score += 900
        val target = "$primaryEn $primaryRom ${synonyms.joinToString(" ")}".lowercase()
        val mods = listOf("ova", "movie", "special", "specials", "tales", "journal", "part", "season", "kanwa", "spin-off", "theatre")
        for (mod in mods) {
            val candHas = normName.contains(mod) || name.lowercase().contains(mod)
            val targetHas = target.contains(mod)
            if (candHas && !targetHas) score -= 300
        }
        for (t in listOf(primaryEn, primaryRom) + synonyms) {
            val normT = normalize(t)
            if (normT.isEmpty() || normT.length < 3) continue
            when {
                normName == normT -> score += 200
                normName.startsWith(normT) || normT.startsWith(normName) -> score += 80
                normName.contains(normT) || normT.contains(normName) -> score += 40
            }
        }
        score -= abs(normName.length - (normEn.ifEmpty { normRom }).length) * 2
        return score
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        if (animeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val showId = animeLink
                val json = get("$baseUrl/ajax/episode/list/$showId", "$baseUrl/", "application/json,*/*")
                val list = (parseJsonObj(json)?.get("result") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val re = Regex("""<a\s[^>]*data-id="([^"]*)"[^>]*>""")
                val episodes = mutableListOf<Episode>()
                for (m in re.findAll(list)) {
                    val tag = m.value
                    val num = attr(tag, "num").toIntOrNull() ?: continue
                    val ids = attr(tag, "ids")
                    if (ids.isBlank()) continue
                    val hasSub = attr(tag, "sub") == "1"
                    val hasDub = attr(tag, "dub") == "1"
                    if (selectDub && !hasDub) continue
                    if (!selectDub && !hasSub) continue
                    val titleMatch = Regex("""<span class="d-title"[^>]*>([\s\S]*?)</span>""").find(tag)
                    val title = titleMatch?.let { stripTags(it.groupValues[1]) }?.ifBlank { null } ?: "Episode $num"
                    val epExtra = mutableMapOf("ids" to ids, "showId" to showId, "num" to num.toString())
                    episodes.add(Episode(number = num.toString(), link = num.toString(), title = title, extra = epExtra))
                }
                episodes.sortedBy { it.number.toIntOrNull() ?: 0 }
            } catch (e: Exception) {
                Logger.log("AniKoto loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun parseJsonObj(s: String): JsonObject? = runCatching {
        Mapper.json.parseToJsonElement(s) as? JsonObject
    }.getOrNull()

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        val ids = extra?.get("ids") ?: return emptyList()
        val audio = if (selectDub) "dub" else "sub"
        return withContext(Dispatchers.IO) {
            try {
                val serverJson = get("$baseUrl/ajax/server/list?servers=${encode(ids)}", "$baseUrl/", "application/json,*/*")
                val serverHtml = (parseJsonObj(serverJson)?.get("result") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val serverItems = mutableListOf<Pair<String, String>>() // linkId, name
                val typeRe = Regex("""<div class="type" data-type="([^"]+)">([\s\S]*?)</ul>\s*</div>""")
                for (tm in typeRe.findAll(serverHtml)) {
                    val typeName = tm.groupValues[1]
                    if (typeName == "dl" || typeName.contains("download", true)) continue
                    if (typeName != audio) continue
                    for (li in Regex("""<li\s+([^>]*data-link-id[^>]*)>([\s\S]*?)</li>""").findAll(tm.groupValues[2])) {
                        val lid = Regex("""data-link-id="([^"]+)"""").find(li.groupValues[1])?.groupValues?.get(1) ?: continue
                        val name = stripTags(li.groupValues[2]).ifEmpty { typeName }
                        serverItems.add(lid to name)
                    }
                }
                if (serverItems.isEmpty()) return@withContext emptyList()

                val results = serverItems.map { (lid, name) ->
                    async {
                        try {
                            val resolvedJson = get("$baseUrl/ajax/server?get=${encode(lid)}", "$baseUrl/", "application/json,*/*")
                            val obj = parseJsonObj(resolvedJson)?.get("result") as? JsonObject ?: return@async null
                            val embedUrl = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@async null
                            val extraData = mutableMapOf("referer" to "$baseUrl/")
                            val intro = (obj["skip_data"] as? JsonObject)?.get("intro") as? JsonArray
                            val outro = (obj["skip_data"] as? JsonObject)?.get("outro") as? JsonArray
                            val directUrl = if (embedUrl.contains("#aHR0c")) {
                                runCatching {
                                    val b64 = embedUrl.substringAfter("#")
                                    val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                                    if (decoded.contains(".m3u8")) decoded else null
                                }.getOrNull()
                            } else null

                            if (directUrl != null) {
                                extraData["referer"] = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.authority}" } }.getOrDefault("$baseUrl/")
                                VideoServer(name, directUrl, extraData)
                            } else {
                                val res = MegaPlayExtractor.extract(embedUrl, "$baseUrl/")
                                val subs = res.subtitles
                                if (subs.isNotEmpty()) {
                                    val subJson = subs.joinToString(",") {
                                        "{\"url\":\"${it.url.replace("\"", "\\\"")}\",\"language\":\"${it.language}\",\"type\":\"${it.type}\"}"
                                    }
                                    extraData["subtitles"] = "[$subJson]"
                                }
                                intro?.let { arr ->
                                    val s = (arr.getOrNull(0) as? JsonPrimitive)?.intOrNull
                                    val e = (arr.getOrNull(1) as? JsonPrimitive)?.intOrNull
                                    if (s != null && e != null && e > s) extraData["intro"] = """{"start":$s,"end":$e}"""
                                }
                                outro?.let { arr ->
                                    val s = (arr.getOrNull(0) as? JsonPrimitive)?.intOrNull
                                    val e = (arr.getOrNull(1) as? JsonPrimitive)?.intOrNull
                                    if (s != null && e != null && e > s) extraData["outro"] = """{"start":$s,"end":$e}"""
                                }
                                res.intro?.let { (s, e) -> extraData["intro"] = """{"start":$s,"end":$e}""" }
                                res.outro?.let { (s, e) -> extraData["outro"] = """{"start":$s,"end":$e}""" }
                                VideoServer(name, res.urls.firstOrNull() ?: embedUrl, extraData)
                            }
                        } catch (e: Exception) {
                            Logger.log("AniKoto server resolve failed: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
                results
            } catch (e: Exception) {
                Logger.log("AniKoto loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }
}
