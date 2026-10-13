package ani.sanin.reels

import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Builds the anime trailer "reels" feed: the YouTube videos TMDB holds for popular anime,
 * mixed so no show plays twice in a row.
 *
 * Videos only exist on TMDB, while anime mode keys everything on AniList, and TMDB keys
 * movies and shows in separate id namespaces, so a title is resolved in three hops —
 * AniList id → AniZip mapping (which carries both the TMDB id and its kind) → the matching
 * TMDB `/videos` endpoint. Everything is cached in memory so a feed the user scrolls back
 * through, or a rail that opens it, does not refetch the same show.
 */
object ReelFeed {

    /** A show in the pool, before its videos are known. */
    data class AnimeRef(
        val anilistId: Int,
        val title: String,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,
    )

    /** One playable video, tagged with the show it belongs to and that show's display metadata. */
    data class ReelItem(
        val key: String,
        val anilistId: Int,
        val animeTitle: String,
        val videoName: String,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,
        // A TMDB title instead of an AniList one. Only one of [anilistId] / [tmdbId] is positive;
        // the player uses whichever is set to decide which details screen the show pill opens.
        val tmdbId: Int = 0,
        val mediaType: String? = null,
    )

    private const val MAX_CACHE = 160

    // How deep a random draw may reach. Roughly the top ~1500 of any ranking: far enough down
    // that mid-popularity shows surface, shallow enough that the tail is not all obscure entries.
    private const val MAX_PAGE = 50

    // Half of all draws come from this shallower band so popular shows still appear often; the
    // other half range across the full depth to bring in the middle of the catalog.
    private const val POPULAR_BAND_PAGE = 12

    // Each draw takes one page under one of these orders; mixing them keeps the feed from
    // settling into "always the most popular".
    private val SORTS = listOf("POPULARITY_DESC", "SCORE_DESC", "TRENDING_DESC", "FAVOURITES_DESC")

    // Last few pages drawn, so back-to-back draws do not land on the same slice.
    private const val RECENT_PAGE_MEMORY = 12
    private val recentPages = ArrayDeque<Int>()

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Int, List<ReelItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<ReelItem>>?): Boolean =
            size > MAX_CACHE
    }

    // A page of anime fans out into one AniZip call and one or two TMDB calls each; four at a
    // time keeps a whole page from stampeding two third-party APIs at once.
    private val gate = Semaphore(4)

    /**
     * `official` first, then by kind, then newest — the order a viewer expects a show's
     * videos to be tried in.
     */
    private val videoOrder = compareByDescending<TmdbVideo> { it.official }
        .thenBy { typeRank(it.type) }
        .thenByDescending { it.publishedAt ?: "" }

    /** A page of anime from AniList under a given sort order. */
    suspend fun pool(page: Int, perPage: Int = 30, sort: String = "POPULARITY_DESC"): List<AnimeRef> {
        val media = runCatching {
            Anilist.query.discoverAnime(page = page, perPage = perPage, sort = sort)
        }
            .getOrNull()
            ?: return emptyList()
        return media.mapNotNull { item ->
            val title = (item.name ?: item.userPreferredName)
                .takeIf { it.isNotBlank() }
                ?: item.nameRomaji.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            AnimeRef(
                anilistId = item.id,
                title = title,
                synopsis = cleanSynopsis(item.description),
                genres = item.genres.toList(),
                cover = item.cover,
            )
        }
    }

    /**
     * One page of shows drawn from a random slice of the catalog under a random rank order.
     *
     * Pulling page 1 of popularity every time is what made the rail and the player feel like the
     * same handful of top shows. Choosing a random page in [1, MAX_PAGE] mixes the household names
     * at the head of the list with mid-popularity shows further down, and rotating the sort keeps
     * any one ordering (popular / top-rated / trending) from dominating. Recently used pages are
     * skipped so consecutive draws do not collide.
     */
    suspend fun draw(perPage: Int = 30): List<AnimeRef> =
        pool(page = nextPage(), perPage = perPage, sort = SORTS.random())

    /**
     * A rail-sized pool taken from several independent draws and shuffled.
     *
     * Two draws are merged (so the strip spans more than one rank slice) and de-duplicated by
     * AniList id, then shuffled and trimmed to [cardCount] before videos are resolved.
     */
    suspend fun railPool(cardCount: Int): List<AnimeRef> {
        val merged = LinkedHashMap<Int, AnimeRef>()
        repeat(2) { draw().forEach { merged.putIfAbsent(it.anilistId, it) } }
        return merged.values.shuffled().take(cardCount)
    }

    private fun nextPage(): Int = synchronized(cacheLock) {
        var page: Int
        var guard = 0
        do {
            page = if (Random.nextBoolean()) {
                (1..POPULAR_BAND_PAGE).random()
            } else {
                (1..MAX_PAGE).random()
            }
            guard++
        } while (page in recentPages && guard < 8)
        recentPages.addLast(page)
        while (recentPages.size > RECENT_PAGE_MEMORY) recentPages.removeFirst()
        page
    }

    /**
     * A card's artwork, straight from the video's key.
     *
     * Deliberately the 16:9 master, not `hqdefault`: that one is a 4:3 frame with the 16:9 video
     * letterboxed inside, so any crop of it carries the baked-in black bars top and bottom.
     * `maxresdefault` is missing for some videos, so callers fall back to [thumbnailFallbackUrl].
     */
    fun thumbnailUrl(key: String): String = "https://img.youtube.com/vi/$key/maxresdefault.jpg"

    /** The always-available 16:9 thumbnail used when [thumbnailUrl] has no master. */
    fun thumbnailFallbackUrl(key: String): String = "https://img.youtube.com/vi/$key/mqdefault.jpg"

    /**
     * Every video for [refs], interleaved round-robin and shuffled.
     *
     * One video is taken from each show per round, in a freshly shuffled show order, so a
     * show with five videos is spread across five different neighbours instead of clumping.
     * A show whose id maps to nothing (no TMDB entry, or no videos) simply drops out.
     */
    suspend fun buildInterleaved(refs: List<AnimeRef>): List<ReelItem> {
        val distinct = refs.distinctBy { it.anilistId }
        if (distinct.isEmpty()) return emptyList()

        val pools = coroutineScope {
            distinct.map { ref -> async { videosFor(ref).toMutableList() } }.map { it.await() }
        }.filter { it.isNotEmpty() }
        if (pools.isEmpty()) return emptyList()

        val out = ArrayList<ReelItem>(pools.sumOf { it.size })
        while (true) {
            val active = pools.filter { it.isNotEmpty() }
            if (active.isEmpty()) break
            // A fresh show order each round keeps the mix from settling into a repeatable
            // rotation, while taking only one video per show per round is what guarantees no
            // two neighbours belong to the same show.
            for (pool in active.shuffled()) {
                out += pool.removeAt(pool.indices.random())
            }
        }
        return out
    }

    /** The cached videos for one show, fetched and mapped on first use. */
    suspend fun videosFor(ref: AnimeRef): List<ReelItem> {
        synchronized(cacheLock) { cache[ref.anilistId] }?.let { return it }
        val items = gate.withPermit { fetchVideos(ref) }
        synchronized(cacheLock) { cache[ref.anilistId] = items }
        return items
    }

    private suspend fun fetchVideos(ref: AnimeRef): List<ReelItem> {
        if (Tmdb.apiKey.isBlank()) return emptyList()

        val mappings = AniZip.getMappings(ref.anilistId) ?: return emptyList()
        val tmdbId = mappings.tmdbId?.toIntOrNull() ?: return emptyList()

        // AniZip's own kind decides the endpoint, since the bare id is ambiguous across
        // TMDB's movie/tv namespaces (an OVA/ONA sits under tv). If the hinted namespace has
        // nothing, the other one is still worth a look.
        val movie = mappings.type?.equals("MOVIE", ignoreCase = true) == true
        val primary = if (movie) "movie" else "tv"
        val secondary = if (movie) "tv" else "movie"
        val videos = Tmdb.videos(primary, tmdbId).ifEmpty { Tmdb.videos(secondary, tmdbId) }

        val seen = HashSet<String>()
        return videos
            .asSequence()
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .sortedWith(videoOrder)
            .mapNotNull { video ->
                val key = video.key ?: return@mapNotNull null
                if (!seen.add(key)) return@mapNotNull null
                ReelItem(
                    key = key,
                    anilistId = ref.anilistId,
                    animeTitle = ref.title,
                    videoName = video.name?.takeIf { it.isNotBlank() } ?: ref.title,
                    synopsis = ref.synopsis,
                    genres = ref.genres,
                    cover = ref.cover,
                )
            }
            .toList()
    }

    /**
     * AniList returns descriptions as light markup even with `asHtml:false`, so it is flattened to
     * a single readable line here and cached that way.
     */
    private fun cleanSynopsis(html: String?): String? = html
        ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace("&quot;", "\"")
        ?.replace("&#039;", "'")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.ifBlank { null }

    private fun typeRank(type: String?): Int = when (type?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        "clip" -> 2
        "featurette" -> 3
        "behind the scenes" -> 4
        "bloopers" -> 5
        "opening credits" -> 6
        else -> 7
    }
}
