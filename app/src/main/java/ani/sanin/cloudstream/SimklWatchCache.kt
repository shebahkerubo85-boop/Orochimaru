package ani.sanin.cloudstream

import ani.sanin.connections.simkl.Simkl

/**
 * Tiny in-memory cache of the user's Simkl movie/show libraries, so card
 * grids can show watched counts without one API call per card.
 * Lazily fetched once per session per type.
 */
object SimklWatchCache {

    @Volatile private var movies: Map<Int, Int>? = null
    @Volatile private var shows: Map<Int, Int>? = null

    /** Watched count for a TMDB id of [type] ("movie"|"tv"), or null if unknown. */
    suspend fun watched(type: String, tmdbId: Int): Int? {
        val current = if (type == "movie") movies else shows
        if (current != null) return current[tmdbId]
        val fetched = try {
            val items = if (type == "movie") Simkl.getMovieLibrary() else Simkl.getShowLibrary()
            items.mapNotNull { item ->
                val id = item.ids?.tmdb ?: return@mapNotNull null
                id to item.totalWatched
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
        if (type == "movie") movies = fetched else shows = fetched
        return fetched[tmdbId]
    }
}
