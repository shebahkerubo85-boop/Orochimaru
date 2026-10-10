package ani.sanin.parsers

import ani.sanin.Mapper
import ani.sanin.media.Media
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.xdrop.fuzzywuzzy.FuzzySearch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Native MKissa / AllAnime API provider.
 *
 * Uses the site's ordinary GraphQL responses only. If the service returns a
 * challenge or an encrypted stream payload, the provider fails cleanly rather
 * than attempting to defeat that protection.
 */
class MkissaProvider : NativeAnimeParser() {
    override val name = "MKissa"
    override val saveName = "MKissa"
    override val defaultBaseUrl = "https://mkissa.to"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true
    override val knownServers = listOf("MKissa")

    private val apiUrl = "https://api.mkissa.net/api"
    private val jsonMediaType = "application/json; charset=utf-8"

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val payload = buildJsonObject {
                put("query", SEARCH_QUERY)
                putJsonObject("variables") {
                    putJsonObject("search") {
                        put("allowAdult", false)
                        put("allowUnknown", true)
                        put("query", query)
                    }
                }
            }
            val root = graphql(payload)
            val edges = root["data"]?.jsonObject?.get("shows")?.jsonObject
                ?.get("edges")?.jsonArray ?: return@withContext emptyList()
            edges.mapNotNull { element ->
                val item = element.jsonObject
                val id = item.string("_id") ?: return@mapNotNull null
                val title = item.string("englishName")
                    ?.takeIf { it.isNotBlank() }
                    ?: item.string("name")?.takeIf { it.isNotBlank() }
                    ?: item.string("nativeName")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val image = item.string("thumbnail")?.let(::absoluteImageUrl) ?: defaultImage
                ShowResponse(
                    name = title,
                    link = id,
                    coverUrl = image,
                    otherNames = listOfNotNull(item.string("name"), item.string("nativeName")).distinct(),
                    extra = mutableMapOf(
                        "showId" to id,
                        "slugTime" to (item.string("slugTime") ?: "")
                    )
                )
            }.distinctBy { it.link }
        } catch (e: Exception) {
            Logger.log("MKissa search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            saveShowResponse(mediaObj.id, saved, true)
            return saved
        }
        setUserText("Searching MKissa: ${mediaObj.mainName()}")
        return withContext(Dispatchers.IO) {
            try {
                val queries = buildList {
                    add(mediaObj.mainName())
                    if (mediaObj.nameRomaji.isNotBlank()) add(mediaObj.nameRomaji)
                    mediaObj.synonyms?.forEach { add(it) }
                }.filter { it.isNotBlank() }.distinct().take(5)

                val candidates = linkedMapOf<String, ShowResponse>()
                for (query in queries) {
                    search(query).forEach { candidates.putIfAbsent(it.link, it) }
                }
                val best = candidates.values.maxByOrNull { result ->
                    val names = (listOf(result.name) + result.otherNames)
                    names.maxOfOrNull { FuzzySearch.ratio(it.lowercase(), mediaObj.mainName().lowercase()) } ?: 0
                } ?: return@withContext null
                val score = (listOf(best.name) + best.otherNames)
                    .maxOfOrNull { FuzzySearch.ratio(it.lowercase(), mediaObj.mainName().lowercase()) } ?: 0
                if (score < 65) return@withContext null
                saveShowResponse(mediaObj.id, best)
                best
            } catch (e: Exception) {
                Logger.log("MKissa autoSearch failed: ${e.message}")
                null
            }
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        val showId = extra?.get("showId") ?: animeLink
        if (showId.isBlank()) return@withContext emptyList()
        try {
            val root = graphql(buildJsonObject {
                put("query", SERIES_QUERY)
                putJsonObject("variables") { put("showId", showId) }
            })
            val show = root["data"]?.jsonObject?.get("show")?.jsonObject
                ?: return@withContext emptyList()
            val detail = show["availableEpisodesDetail"]?.jsonObject
                ?: return@withContext emptyList()
            val sub = detail["sub"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val dub = detail["dub"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val selected = if (selectDub && dub.isNotEmpty()) dub else sub.ifEmpty { dub }
            selected.mapNotNull { raw ->
                val number = raw.toFloatOrNull() ?: return@mapNotNull null
                val audio = if (selectDub && dub.contains(raw)) "dub" else if (sub.contains(raw)) "sub" else "dub"
                Episode(
                    number = raw,
                    link = raw,
                    title = "Episode $raw",
                    extra = mutableMapOf("showId" to showId, "episodeString" to raw, "audio" to audio)
                )
            }.distinctBy { it.number }.sortedBy { it.number.toFloatOrNull() ?: 0f }
        } catch (e: Exception) {
            Logger.log("MKissa loadEpisodes failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        val showId = extra?.get("showId") ?: return@withContext emptyList()
        val episodeString = extra["episodeString"] ?: episodeLink
        val audio = extra["audio"] ?: if (selectDub) "dub" else "sub"
        try {
            val root = graphql(buildJsonObject {
                put("query", EPISODE_QUERY)
                putJsonObject("variables") {
                    put("showId", showId)
                    put("translationType", audio)
                    put("episodeString", episodeString)
                }
            })
            val episode = root["data"]?.jsonObject?.get("episode")?.jsonObject
            val sources = episode?.get("sourceUrls")?.jsonArray
                ?: throw IOException("MKissa did not return public stream sources")
            sources.mapNotNull { element ->
                val source = element.jsonObject
                val url = source.string("sourceUrl")?.let { decodeEntities(it) } ?: return@mapNotNull null
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
                val sourceName = source.string("sourceName")?.takeIf { it.isNotBlank() } ?: "MKissa"
                // The shared native extractor/router handles known public embed hosts.
                VideoServer(sourceName, url, mapOf("referer" to "$baseUrl/"))
            }.distinctBy { it.embed.url }
        } catch (e: Exception) {
            Logger.log("MKissa streams unavailable: ${e.message}")
            emptyList()
        }
    }

    private fun graphql(payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .header("Origin", baseUrl)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType.toMediaType()))
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("MKissa API HTTP ${response.code}")
            val root = runCatching { Mapper.json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IOException("MKissa API returned non-JSON data (possible access challenge)")
            }
            if (root["errors"] != null) {
                val messages = root["errors"]?.jsonArray?.mapNotNull {
                    it.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
                throw IOException(messages.joinToString().ifBlank { "MKissa API returned an error" })
            }
            if (root["data"]?.jsonObject?.get("tobeparsed") != null) {
                throw IOException("MKissa returned an encrypted stream payload; this provider does not bypass that protection")
            }
            return root
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun absoluteImageUrl(url: String): String = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://allanime.day$url"
    }

    companion object {
        private const val SEARCH_QUERY = """
            query ($search: SearchInput!) {
              shows(search: $search) {
                edges {
                  _id
                  name
                  englishName
                  nativeName
                  thumbnail
                  slugTime
                }
              }
            }
        """

        private const val SERIES_QUERY = """
            query ($showId: String!) {
              show(_id: $showId) {
                _id
                availableEpisodesDetail {
                  sub
                  dub
                }
              }
            }
        """

        private const val EPISODE_QUERY = """
            query ($showId: String!, $translationType: String!, $episodeString: String!) {
              episode(showId: $showId, translationType: $translationType, episodeString: $episodeString) {
                sourceUrls {
                  sourceUrl
                  type
                  sourceName
                  priority
                }
              }
            }
        """
    }
}
