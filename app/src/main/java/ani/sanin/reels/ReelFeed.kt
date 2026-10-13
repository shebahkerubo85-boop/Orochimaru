package ani.sanin.reels

import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anizip.AniZip
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    data class AnimeRef(val anilistId: Int, val title: String)

    /** One playable video, tagged with the show it belongs to. */
    data class ReelItem(
        val key: String,
        val animeTitle: String,
        val videoName: String,
    )

    private const val MAX_CACHE = 160

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

    /** A page of popular anime, in AniList popularity order. */
    suspend fun pool(page: Int, perPage: Int = 30): List<AnimeRef> {
        val media = runCatching { Anilist.query.discoverAnime(page = page, perPage = perPage) }
            .getOrNull()
            ?: return emptyList()
        return media.mapNotNull { item ->
            val title = (item.name ?: item.userPreferredName)
                .takeIf { it.isNotBlank() }
                ?: item.nameRomaji.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            AnimeRef(item.id, title)
        }
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
                    animeTitle = ref.title,
                    videoName = video.name?.takeIf { it.isNotBlank() } ?: ref.title,
                )
            }
            .toList()
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
