package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.media.Media
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
                val body = get("$baseUrl/search.html?keyword=$encoded")
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
        // Match <a href=".../category/SLUG" ... title="TITLE" or >TITLE<
        val regex = Regex(
            """<a\s+href="(?:https?://[^"]*)?/category/([^"/"]+)/?"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val titleRegex = Regex("""title="([^"]+)" """)
        val seen = mutableSetOf<String>()

        regex.findAll(html).forEach { match ->
            val slug = match.groupValues[1].trim()
            if (slug.isBlank() || !seen.add(slug)) return@forEach

            // Try to find the title in the surrounding context (look back ~200 chars)
            val ctxStart = (match.range.first - 200).coerceAtLeast(0)
            val ctx = html.substring(ctxStart, match.range.last + 200)
            val titleMatch = titleRegex.find(ctx) ?: titleRegex.find(ctx.replace("\n", " "))
            val title = titleMatch?.groupValues[1]?.trim()
                ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

            results.add(ShowResponse(
                name = title,
                link = slug,
                coverUrl = FileUrl(""),
            ))
        }
        return results.take(20)
    }

    // ── Auto search ──────────────────────────────────────────────────────────

    override suspend fun autoSearch(mediaObj: Media): ShowResponse? {
        val saved = loadSavedShowResponse(mediaObj.id)
        if (saved != null) return saved
        setUserText("Searching Gogoanime: ${mediaObj.mainName()}")

        // Try the direct slug first — gogoanime uses lowercase-hyphenated titles.
        val directSlug = mediaObj.mainName().trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), "-")
        if (directSlug.isNotBlank()) {
            try {
                val catBody = withContext(Dispatchers.IO) { get("$baseUrl/category/$directSlug") }
                if (catBody.contains("episode", ignoreCase = true)) {
                    val resp = ShowResponse(
                        name = mediaObj.mainName(),
                        link = directSlug,
                        coverUrl = FileUrl(""),
                    )
                    saveShowResponse(mediaObj.id, resp)
                    return resp
                }
            } catch (_: Exception) {}
        }

        return searchWithFallback(mediaObj.mainName()).firstOrNull()
            ?: searchWithFallback(mediaObj.nameRomaji).firstOrNull()
    }

    // ── Episodes ─────────────────────────────────────────────────────────────

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        if (animeLink.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val body = get("$baseUrl/category/$animeLink")
                val epRegex = Regex(
                    """href="(?:https?://[^"]*?)?$animeLink-episode-(\d+)(?:-([^"]*))?/?"""",
                    RegexOption.IGNORE_CASE
                )
                val episodes = mutableListOf<Episode>()
                val seen = mutableSetOf<Int>()
                epRegex.findAll(body).forEach { m ->
                    val num = m.groupValues[1].toIntOrNull() ?: return@forEach
                    if (!seen.add(num)) return@forEach
                    val suffix = m.groupValues[2].orEmpty()
                    val isDub = suffix.contains("dub", ignoreCase = true) ||
                        body.substring(m.range).take(200).contains("dub", ignoreCase = true)
                    episodes.add(Episode(
                        number = num.toString(),
                        link = "$baseUrl/$animeLink-episode-$num${if (isDub) "-english-dubbed" else "-english-subbed"}/",
                        title = "Episode $num${if (isDub) " (Dub)" else ""}",
                        extra = extra
                    ))
                }
                episodes.sortBy { it.number.toIntOrNull() ?: 0 }
                episodes
            } catch (e: Exception) {
                Logger.log("Gogoanime loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
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
