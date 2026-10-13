package ani.sanin.reels

import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbVideo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The TMDB half of the reels feed: the same shuffled, endlessly-appending trailer runway as the
 * anime rail, but seeded from TMDB's own catalog instead of AniList.
 *
 * The only thing that really differs from [ReelFeed] is where the titles come from — a TMDB id is
 * already in hand (no AniZip mapping step), and one draw can mix movies and shows. Everything
 * downstream (interleave, thumbnail, player) is shared, so a TMDB reel and an anime reel are the
 * same [ReelFeed.ReelItem] with a different id set.
 */
object TmdbReelFeed {

    /** A title from TMDB that can be turned into a set of playable trailer videos. */
    data class TmdbRef(
        val id: Int,
        val mediaType: String,
        val title: String,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val cover: String? = null,
    ) {
        val showKey: String get() = "t:$mediaType:$id"
    }

    // How deep a random draw may reach (TMDB serves 20 a page, up to 500 pages).
    private const val MAX_PAGE = 25

    // Half of all draws come from this shallower band so the household names still appear often.
    private const val POPULAR_BAND_PAGE = 8

    // Rotating the ordering keeps any one of them (most popular / best rated / most voted) from
    // dominating the feed the way "always page 1 of popularity" did.
    private val SORTS = listOf("popularity.desc", "vote_average.desc", "vote_count.desc")

    private const val RECENT_PAGE_MEMORY = 10
    private val recentPages = ArrayDeque<Int>()

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, List<ReelFeed.ReelItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReelFeed.ReelItem>>?): Boolean =
            size > 160
    }

    private val gate = Semaphore(4)

    private var cachedGenreNames: Map<Int, String>? = null

    /** `official` first, then by kind, then newest — the order a viewer expects a title's videos. */
    private val videoOrder = compareByDescending<TmdbVideo> { it.official }
        .thenBy { typeRank(it.type) }
        .thenByDescending { it.publishedAt ?: "" }

    /** One page of movies or shows from TMDB under a given sort order. */
    private suspend fun pool(page: Int, mediaType: String, sort: String): List<TmdbRef> {
        if (Tmdb.apiKey.isBlank()) return emptyList()
        val extra = mutableListOf("include_adult" to "false")
        // Ranking by average with no floor returns festival shorts with a single 10/10 vote.
        if (sort == "vote_average.desc") extra.add("vote_count.gte" to "300")
        val names = genreNames()
        val media = runCatching {
            Tmdb.discover(mediaType = mediaType, sort = sort, page = page, extra = extra)
        }.getOrDefault(emptyList())
        return media.mapNotNull { toRef(it, mediaType, names) }
    }

    /** One page drawn from a random slice of the catalog, under a random order, movies or shows. */
    suspend fun draw(): List<TmdbRef> =
        pool(
            page = nextPage(),
            mediaType = if ((1..2).random() == 1) "movie" else "tv",
            sort = SORTS.random(),
        )

    /** A rail-sized pool taken from several independent draws and shuffled, de-duped by title. */
    suspend fun railPool(cardCount: Int): List<TmdbRef> {
        val merged = LinkedHashMap<String, TmdbRef>()
        repeat(2) { draw().forEach { merged.putIfAbsent(it.showKey, it) } }
        return merged.values.shuffled().take(cardCount)
    }

    private fun nextPage(): Int = synchronized(cacheLock) {
        var page: Int
        var guard = 0
        do {
            page = if ((1..2).random() == 1) (1..POPULAR_BAND_PAGE).random() else (1..MAX_PAGE).random()
            guard++
        } while (page in recentPages && guard < 8)
        recentPages.addLast(page)
        while (recentPages.size > RECENT_PAGE_MEMORY) recentPages.removeFirst()
        page
    }

    private fun toRef(item: TmdbMedia, mediaType: String, names: Map<Int, String>): TmdbRef? {
        val title = item.displayTitle.takeIf { it.isNotBlank() } ?: return null
        return TmdbRef(
            id = item.id,
            mediaType = item.type.ifBlank { mediaType },
            title = title,
            synopsis = item.overview?.trim()?.ifBlank { null },
            genres = item.genreIds.mapNotNull { names[it] },
            cover = Tmdb.imageUrl(item.posterPath, 342),
        )
    }

    /**
     * Every video for [refs], interleaved round-robin and shuffled — the TMDB analogue of
     * [ReelFeed.buildInterleaved], producing the same [ReelFeed.ReelItem] the player already knows.
     */
    suspend fun buildInterleaved(refs: List<TmdbRef>): List<ReelFeed.ReelItem> {
        val distinct = refs.distinctBy { it.showKey }
        if (distinct.isEmpty()) return emptyList()

        val pools = coroutineScope {
            distinct.map { ref -> async { videosFor(ref).toMutableList() } }.map { it.await() }
        }.filter { it.isNotEmpty() }
        if (pools.isEmpty()) return emptyList()

        val out = ArrayList<ReelFeed.ReelItem>(pools.sumOf { it.size })
        while (true) {
            val active = pools.filter { it.isNotEmpty() }
            if (active.isEmpty()) break
            for (pool in active.shuffled()) {
                out += pool.removeAt(pool.indices.random())
            }
        }
        return out
    }

    /** The cached videos for one title, fetched and mapped on first use. */
    suspend fun videosFor(ref: TmdbRef): List<ReelFeed.ReelItem> {
        synchronized(cacheLock) { cache[ref.showKey] }?.let { return it }
        val items = gate.withPermit { fetchVideos(ref) }
        synchronized(cacheLock) { cache[ref.showKey] = items }
        return items
    }

    private suspend fun fetchVideos(ref: TmdbRef): List<ReelFeed.ReelItem> {
        if (Tmdb.apiKey.isBlank()) return emptyList()
        val videos = Tmdb.videos(ref.mediaType, ref.id)
        val seen = HashSet<String>()
        return videos
            .asSequence()
            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
            .sortedWith(videoOrder)
            .mapNotNull { video ->
                val key = video.key ?: return@mapNotNull null
                if (!seen.add(key)) return@mapNotNull null
                ReelFeed.ReelItem(
                    key = key,
                    anilistId = 0,
                    animeTitle = ref.title,
                    videoName = video.name?.takeIf { it.isNotBlank() } ?: ref.title,
                    synopsis = ref.synopsis,
                    genres = ref.genres,
                    cover = ref.cover,
                    tmdbId = ref.id,
                    mediaType = ref.mediaType,
                )
            }
            .toList()
    }

    /** Genre id -> name, fetched once. TMDB's movie and TV lists share most ids and names. */
    private suspend fun genreNames(): Map<Int, String> {
        cachedGenreNames?.let { return it }
        val map = runCatching { Tmdb.genres() }.getOrDefault(emptyList())
            .associate { it.id to it.name }
        cachedGenreNames = map
        return map
    }

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
