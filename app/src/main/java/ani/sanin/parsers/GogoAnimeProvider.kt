package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import me.xdrop.fuzzywuzzy.FuzzySearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gogoanime (gogoanime.by) — long-running, stable HTML site.
 * Search → category slug → episode list → player embed URLs.
 */
class GogoAnimeProvider : NativeAnimeParser() {

    override val name = "Gogoanime"
    override val saveName = "gogoanime"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://gogoanime.by"

    override val knownServers: List<String> = listOf("Gogo")

    private val html get() = get("$baseUrl/", emptyMap())

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "utf-8")
                val body = get("$baseUrl/?s=$encoded", "$baseUrl/")
                parseSearchResults(body)
            } catch (e: Exception) {
                Logger.log("Gogoanime search error: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Extracts anime entries from the search results page.
     * Each result is a link to /category/{slug} with a title attribute or inner text.
     */
    private fun parseSearchResults(html: String): List<ShowResponse> {
        val results = mutableListOf<ShowResponse>()
        // Match series entries; episode pages and sidebar category links are not show records.
        val regex = Regex(
            """<a\s+href="(?:https?://[^"]*)?/series/([^"/"]+)/?"([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val seen = mutableSetOf<String>()

        regex.findAll(html).forEach { match ->
            val slug = match.groupValues[1].trim()
            if (slug.isBlank() || !seen.add(slug)) return@forEach

            val attributes = match.groupValues[2]
            val innerText = match.groupValues[3].replace(Regex("<[^>]+>"), " ")
            val title = Regex("""title=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: decodeEntities(innerText).trim().takeIf { it.isNotEmpty() }
                ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

            results.add(ShowResponse(
                name = title,
                link = slug,
                coverUrl = FileUrl(defaultImage),
            ))
        }
        return results.take(20)
    }

    // ── Auto search ──────────────────────────────────────────────────────────

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) {
            val savedEpisodes = loadEpisodes(
                saved.link,
                saved.extra,
                saved.sAnime ?: SAnime.create().apply { url = saved.link }
            )
            if (savedEpisodes.isNotEmpty()) return saved
            Logger.log("Gogoanime discarding invalid saved link: ${saved.link}")
        }

        setUserText("Searching Gogoanime: ${mediaObj.mainName()}")

        val directSlug = mediaObj.mainName().trim().lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "-")
        if (directSlug.isNotBlank()) {
            try {
                val body = withContext(Dispatchers.IO) { get("$baseUrl/series/$directSlug") }
                if (parseEpisodes(body, directSlug).isNotEmpty()) {
                    val resp = ShowResponse(
                        name = mediaObj.mainName(),
                        link = directSlug,
                        coverUrl = FileUrl(defaultImage),
                    )
                    saveShowResponse(mediaObj.id, resp)
                    return resp
                }
            } catch (_: Exception) {}
        }

        val candidates = searchWithFallback(mediaObj.mainName()) +
            searchWithFallback(mediaObj.nameRomaji)
        val best = candidates
            .distinctBy { it.link }
            .maxByOrNull { show ->
                FuzzySearch.ratio(
                    show.name.lowercase(),
                    mediaObj.mainName().lowercase()
                )
            }
        if (best != null) saveShowResponse(mediaObj.id, best)
        return best
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        if (animeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                parseEpisodes(get("$baseUrl/series/$animeLink"), animeLink, extra)
            } catch (e: Exception) {
                Logger.log("Gogoanime loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun parseEpisodes(
        body: String,
        animeLink: String,
        extra: Map<String, String>? = null
    ): List<Episode> {
        val epRegex = Regex(
            """href="(?:https?://[^"]*?)?$animeLink-episode-(\d+)(?:-([^"]*))?/?"""",
            RegexOption.IGNORE_CASE
        )
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<Int>()
        epRegex.findAll(body).forEach { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (!seen.add(number)) return@forEach
            val suffix = match.groupValues[2].orEmpty()
            val isDub = suffix.contains("dub", ignoreCase = true) ||
                body.substring(match.range).take(200).contains("dub", ignoreCase = true)
            episodes.add(Episode(
                number = number.toString(),
                link = "$baseUrl/$animeLink-episode-$number${if (isDub) "-english-dubbed" else "-english-subbed"}/",
                title = "Episode $number${if (isDub) " (Dub)" else ""}",
                extra = extra
            ))
        }
        episodes.sortBy { it.number.toIntOrNull() ?: 0 }
        return episodes
    }

    // ── Video servers ────────────────────────────────────────────────────────

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        if (episodeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val body = get(episodeLink)
                val servers = mutableListOf<VideoServer>()
                val seen = mutableSetOf<String>()

                // Extract data-src player embed URLs
                val playerRegex = Regex("""data-src="(https?://[^"]+/player/[^"]+)"""")

                playerRegex.findAll(body).forEach { m ->
                    var url = m.groupValues[1]
                        .replace("&amp;", "&")
                    if (seen.add(url)) {
                        // Determine quality/source label from the URL params
                        val sourceType = Regex("source=(\\w+)").find(url)?.groupValues?.get(1) ?: "embed"
                        val label = when (sourceType) {
                            "embed" -> "Gogo Embed"
                            "blogger" -> "Gogo Blogger"
                            else -> "Gogo $sourceType"
                        }
                        servers.add(VideoServer(label, url))
                    }
                }

                // Also look for direct iframe sources (some pages have them inline)
                val iframeRegex = Regex("""<iframe[^>]+src="(https?://[^"]*(?:embedplus|embtaku|streamwish|filemoon|megacloud|vidhide)[^"]*)"""")
                iframeRegex.findAll(body).forEach { m ->
                    val url = m.groupValues[1]
                    if (seen.add(url)) {
                        val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "Embed"
                        servers.add(VideoServer(host.substringBefore("."), url))
                    }
                }

                servers
            } catch (e: Exception) {
                Logger.log("Gogoanime loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }
}
