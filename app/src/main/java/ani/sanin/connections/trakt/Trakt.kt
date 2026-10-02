package ani.sanin.connections.trakt

import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.FranchiseType
import kotlin.math.roundToInt
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Trakt client, backing the movie-mode Franchise row.
 *
 * ## Why Trakt
 *
 * TMDB cannot do this. Its `belongs_to_collection` field only appears on a movie *detail*
 * response, never on a list response, so seeding from `discover`/`trending`/`popular` yields no
 * collections at all — verified across four sort orders, all returning zero. And
 * `/collection/list`, the one endpoint that would enumerate them, is gone from v3: it answers
 * `status_code: 6, "Invalid id"`. Membership therefore costs one detail request per film, which
 * a 250-item list cannot absorb.
 *
 * Trakt's curated lists are already grouped and already ranked, so one list is one card.
 *
 * ## The client id is public
 *
 * [PrefName.TraktClientId] ships in the APK and is extractable, which the user accepted. It is
 * app-only and read-only, and Trakt requires some id on every request regardless: with none,
 * every endpoint answers 403.
 *
 * ## Limits found while wiring this up
 *
 * - `GET /lists?sort_by=...` is **405**. Plain list browsing does not work with a client id, so
 *   the ranked sources are the curated `/lists/trending` and `/lists/popular` only.
 * - `/lists/box_office` returns 204, empty.
 * - `/franchises` is 405: Trakt has no franchise resource, so a card cannot be grouped by a
 *   shared franchise id. Cards are grouped by list identity instead.
 */
object Trakt {

    private const val BASE = "https://api.trakt.tv"

    /** One page of the ranked list endpoints. */
    private const val LIST_LIMIT = 20

    /**
     * Entries per card.
     *
     * A list can hold a thousand items, and each one costs nothing extra here because a single
     * `/items` call returns them all with `extended=full`. The cap is only about the row, which
     * shows a handful of posters and sends the rest to the dedicated franchise screen.
     */
    private const val MAX_ENTRIES = 24

    private val client
        get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    private val itemsCache = ConcurrentHashMap<String, List<TraktListItem>>()

    /**
     * Cards from Trakt's curated ranked lists, trending first then popular.
     *
     * The two rankings are fetched as separate passes rather than merged, because
     * [Franchise.trendingRank] is only meaningful for a card that came from the trending pass. A
     * popular-only card keeps a null rank and so never competes on that field.
     */
    suspend fun franchiseCards(): List<Franchise> = withContext(Dispatchers.IO) {
        val trending = rankCards(cardsFrom(lists("/lists/trending")))
        val popular = rankCards(cardsFrom(lists("/lists/popular")))

        // Trending first so the row opens on what is moving; a card already present from the
        // trending pass keeps its rank rather than being restated by a popular position.
        val merged = LinkedHashMap<String, Franchise>()
        (trending + popular).forEach { card ->
            merged.putIfAbsent(card.name, card)
        }
        merged.values.toList()
    }

    /** Attaches each card's 1-based position in the ranking it was fetched from. */
    private fun rankCards(cards: List<Franchise>): List<Franchise> =
        cards.mapIndexed { index, card -> card.copy(trendingRank = index + 1) }

    /**
     * The wrappers as the index returns them, list object still nested.
     *
     * Kept wrapped rather than unwrapped because the index puts a second like count on the
     * wrapper, alongside the one on the list, and the wrapper's is the one the ranking is
     * built from. Unwrapping here would lose it and leave every card's like count null.
     */
    private suspend fun lists(path: String): List<TraktListWrapper> =
        getList<TraktListWrapper>("$path?limit=$LIST_LIMIT") ?: emptyList()

    /**
     * One card per list.
     *
     * A Trakt list is a single curated grouping, so the list's name is the card's name and its
     * ranked items are the card's entries. A list holding a single item is kept, as the user
     * specified: a one-entry franchise is still a franchise.
     */
    private suspend fun cardsFrom(lists: List<TraktListWrapper>): List<Franchise> =
        lists.mapNotNull { wrapper ->
            val list = wrapper.list ?: return@mapNotNull null
            val slug = list.ids?.slug ?: return@mapNotNull null
            val name = list.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val username = list.user?.username ?: list.user?.ids?.slug ?: return@mapNotNull null

            val resolved = entriesOf(username, slug)
            if (resolved.entries.isEmpty()) return@mapNotNull null

            Franchise(
                name = name,
                // Per spec the banner comes from the latest entry's artwork, and the logo art
                // comes from the same entry so the header reads as one title's branding.
                bannerUrl = resolved.entries.last().backdropUrl
                    ?: resolved.entries.last().posterUrl,
                logoUrl = resolved.logoUrl,
                entries = resolved.entries,
                likeCount = wrapper.likeCount ?: list.likes,
            )
        }

    /**
     * A list's items, plus the franchise-level artwork that is not derivable from the entries.
     *
     * Trakt has no concept of a franchise, so the grouping is a user's list and the logo has to
     * be lifted off one of its titles. Kept beside the entries rather than squeezed onto
     * [FranchiseEntry] for that reason: a logo belongs to the grouping, not to any one film.
     */
    private data class ResolvedList(
        val entries: List<FranchiseEntry>,
        val logoUrl: String?,
    )

    private suspend fun entriesOf(username: String, slug: String): ResolvedList {
        val key = "$username/$slug"
        val items = itemsCache[key] ?: getList<TraktListItem>(
            // `extended=full` is what adds `images`; without it every poster would be missing.
            "/users/$username/lists/$slug/items?limit=$MAX_ENTRIES&extended=full"
        )?.also { itemsCache[key] = it } ?: return ResolvedList(emptyList(), null)

        // Ranked lists carry an explicit `rank`, which is the all-time ordering. A list sorted
        // by release date leaves it null, so fall back to the response order in that case
        // rather than collapsing the card to a single arbitrary entry.
        val ordered = if (items.all { it.rank != null }) items.sortedBy { it.rank } else items

        // The logo of the last entry, matching the banner. Read from the raw items rather than
        // the mapped entries because the logo is franchise-level: it is the studio's wordmark for
        // the series, not artwork belonging to any one entry.
        val lastImages = ordered.lastOrNull()?.let { it.movie?.images ?: it.show?.images }
        val logo = lastImages?.urlOf(lastImages.logo, "medium", "full", "thumb")

        return ResolvedList(ordered.toEntries(), logo)
    }

    private fun List<TraktListItem>.toEntries() = mapNotNull { item ->
        // A list can mix movies and shows, so both are read and whichever is present is used.
        val title = item.movie?.title ?: item.show?.title ?: return@mapNotNull null
        val year = item.movie?.year ?: item.show?.year
        // A local val, not the safe-call chain written inline: `images?.urlOf(images.poster)`
        // would evaluate the argument even when images is null and throw on the dereference.
        val images = item.movie?.images ?: item.show?.images

        // Movies carry `released`, shows `first_aired`; both are the ISO date the screen wants.
        val airDate = item.movie?.released ?: item.show?.firstAired
        // Trakt types the wrapper as movie/show; the media object repeats it for endpoints that
        // omit the wrapper type. Either source is enough to tell a film from a series.
        val rawType = item.type ?: item.movie?.type ?: item.show?.type

        FranchiseEntry(
            year = year?.toString().orEmpty(),
            sortYear = year,
            posterUrl = images?.urlOf(images.poster, "medium", "full", "thumb"),
            title = title,
            // Trakt serves no backdrop per title, but a wide screenshot or banner is the
            // closest thing to one and beats stretching a portrait poster across the row.
            backdropUrl = images?.urlOf(images.screenshot, "medium", "full")
                ?: images?.urlOf(images.banner, "medium", "full"),
            airDate = airDate,
            airDateSortKey = airDate?.replace("-", "")?.toIntOrNull(),
            durationMinutes = item.movie?.runtime ?: item.show?.runtime,
            // Trakt rates out of 10, the screen reads out of 100 like AniList.
            score = (item.movie?.rating ?: item.show?.rating)?.let { (it * 10).roundToInt() },
            synopsis = item.movie?.overview ?: item.show?.overview,
            type = if (rawType == "movie") FranchiseType.MOVIE else FranchiseType.SEQUENCE,
            tmdbId = (item.movie?.ids ?: item.show?.ids)?.tmdb,
            traktId = (item.movie?.ids ?: item.show?.ids)?.trakt,
        )
    }

    /** One JSON GET, decoded as a list of `T`. Returns null on any failure. */
    private suspend fun <T> getList(path: String): List<T>? =
        tryWithSuspend(snackbar = false) {
            val request = Request.Builder()
                .url("$BASE$path")
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("trakt-api-version", "2")
                .addHeader("trakt-api-key", PrefManager.getVal(PrefName.TraktClientId))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    response.body?.string()
                        ?.let { traktJson.decodeFromString<List<T>>(it) }
                }
            }
        }
}
