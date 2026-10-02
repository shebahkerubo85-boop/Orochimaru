package ani.sanin.connections.trakt

import ani.sanin.media.Franchise
import ani.sanin.util.Logger
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

    /**
     * Cards resolved per batch, and so per page of the row.
     *
     * Sized against the two indexes this source has: 20 trending lists plus 20 popular is 40
     * cards, and the first page of the old Popular row was 30, so 29 leaves the row opening on
     * about the same number of posters the list it replaced showed while cutting the time to
     * first card by nearly a third. The rest arrives as the user scrolls.
     */
    const val BATCH_CARDS = 29

    private val client
        get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    private val itemsCache = ConcurrentHashMap<String, List<TraktListItem>>()

    /**
     * One window of the Franchise source, and whether anything is left behind it.
     *
     * @param cards the resolved cards, unsorted. The row sorts locally, so the window is just
     *   however much of the source has been turned into cards so far.
     * @param hasMore whether [franchiseCards] has lists left after this batch.
     */
    data class TraktBatch(val cards: List<Franchise>, val hasMore: Boolean)

    /**
     * One batch of cards from Trakt's curated ranked lists, trending first then popular.
     *
     * The row pages like every other row in the app, and a card costs one `/items` request
     * because only that endpoint knows a list's membership. Resolving all forty lists up front
     * was forty sequential requests before the row showed anything at all; this takes a window
     * of [take] of them and leaves the rest for when the user scrolls.
     *
     * Both indexes are fetched whole and are cheap (two requests, twenty rows each), so the
     * whole source is known before the first card is and a batch is genuinely a slice of one
     * fixed sequence rather than a guess at a page boundary.
     *
     * @param skip how many lists of that sequence have already been resolved by earlier batches.
     *   Skipping past a list whose card was dropped still counts it, so the batches together
     *   cover the source exactly once.
     * @param take how many lists to resolve in this call. See [BATCH_CARDS].
     */
    suspend fun franchiseCards(skip: Int = 0, take: Int = BATCH_CARDS): TraktBatch =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            // Trending first so the row opens on what is moving. A popular-only card carries a
            // null rank so it never competes on the trending field with one that has a real
            // position in the trending list.
            val trending = lists("/lists/trending")
            val popular = lists("/lists/popular")
            val source = trending.mapIndexed { index, wrapper -> Ranked(wrapper, index + 1) } +
                popular.map { Ranked(it, null) }
            val window = source.drop(skip.coerceAtLeast(0)).take(take)
            Logger.log(
                "Trakt franchiseCards: resolving ${window.size} lists at offset $skip " +
                    "of ${source.size} available, started at ${System.currentTimeMillis() - started}ms"
            )
            val cards = cardsFrom(window)
            Logger.log(
                "Trakt franchiseCards: ${cards.size} cards from ${window.size} lists in " +
                    "${System.currentTimeMillis() - started}ms"
            )
            TraktBatch(cards, skip + window.size < source.size)
        }

    /**
     * A list and its 1-based position in the trending index, or null when it is popular-only.
     *
     * Carried through the batch rather than assigned by position afterwards, because a batch
     * that starts partway down the sequence would otherwise restart the ranking at 1 and put
     * the row's tenth card ahead of its first.
     */
    private data class Ranked(val wrapper: TraktListWrapper, val trendingRank: Int?)

    /**
     * The wrappers as the index returns them, list object still nested.
     *
     * Kept wrapped rather than unwrapped because the index puts a second like count on the
     * wrapper, alongside the one on the list, and the wrapper's is the one the ranking is
     * built from. Unwrapping here would lose it and leave every card's like count null.
     */
    private suspend fun lists(path: String): List<TraktListWrapper> {
        val wrappers = getList<TraktListWrapper>("$path?limit=$LIST_LIMIT") ?: emptyList()
        val usable = wrappers.count { it.list != null }
        Logger.log("Trakt index $path -> $usable/${wrappers.size} wrappers carried a list object")
        return wrappers
    }

    /**
     * One card per list.
     *
     * A Trakt list is a single curated grouping, so the list's name is the card's name and its
     * ranked items are the card's entries. A list holding a single item is kept, as the user
     * specified: a one-entry franchise is still a franchise.
     */
    private suspend fun cardsFrom(lists: List<Ranked>): List<Franchise> {
        var droppedNoList = 0
        var droppedNoSlug = 0
        var droppedNoName = 0
        var droppedNoUser = 0
        var droppedNoEntries = 0
        val cards = lists.mapNotNull { ranked ->
            val wrapper = ranked.wrapper
            val list = wrapper.list ?: run { droppedNoList++; return@mapNotNull null }
            val slug = list.ids?.slug ?: run { droppedNoSlug++; return@mapNotNull null }
            val name = list.name?.takeIf { it.isNotBlank() } ?: run {
                droppedNoName++; return@mapNotNull null
            }
            val username = list.user?.username ?: list.user?.ids?.slug ?: run {
                droppedNoUser++; return@mapNotNull null
            }

            val resolved = entriesOf(username, slug)
            if (resolved.entries.isEmpty()) {
                droppedNoEntries++
                Logger.log("Trakt list $username/$slug (\"$name\") resolved to 0 entries")
                return@mapNotNull null
            }

            Franchise(
                name = name,
                // Per spec the banner comes from the latest entry's artwork, and the logo art
                // comes from the same entry so the header reads as one title's branding.
                bannerUrl = resolved.entries.last().backdropUrl
                    ?: resolved.entries.last().posterUrl,
                logoUrl = resolved.logoUrl,
                entries = resolved.entries,
                likeCount = wrapper.likeCount ?: list.likes,
                trendingRank = ranked.trendingRank,
            )
        }
        // Two seeds of one franchise resolve to the same card, and the second would undo the
        // first's rank, so the first appearance wins and keeps its position.
        val unique = cards.distinctBy { it.name.lowercase() }
        Logger.log(
            "Trakt cardsFrom -> ${unique.size} cards from ${lists.size} lists " +
                "(dropped: noList=$droppedNoList noSlug=$droppedNoSlug noName=$droppedNoName " +
                "noUser=$droppedNoUser noEntries=$droppedNoEntries dupes=${cards.size - unique.size})"
        )
        return unique
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
        val cached = itemsCache[key] != null
        val items = itemsCache[key] ?: getList<TraktListItem>(
            // `extended=full` is what adds `images`; without it every poster would be missing.
            "/users/$username/lists/$slug/items?limit=$MAX_ENTRIES&extended=full"
        )?.also { itemsCache[key] = it } ?: run {
            Logger.log("Trakt entriesOf $key: items request returned nothing")
            return ResolvedList(emptyList(), null)
        }
        Logger.log("Trakt entriesOf $key: ${items.size} items${if (cached) " (cached)" else ""}")

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

    /**
     * One JSON GET, decoded as a list of `T`. Returns null on any failure.
     *
     * `T` has to be reified, and that is not a style preference. `decodeFromString` needs a
     * serializer for `List<T>` at runtime, which it can only build if `T` is known at the call
     * site. Left as a plain generic, the compiler accepts the call, and it then throws
     * `IllegalArgumentException: Captured type parameter T ... from generic non-reified
     * function` the first time it runs. That exception is swallowed by [tryWithSuspend], so the
     * row renders as an empty row with a plausible-looking log line and no error anywhere.
     *
     * Private so that inlining may reach this object's own private members.
     *
     * Every outcome is logged, because this returns null rather than throwing: without a log
     * a rejected key, a 429 and a decode error all look the same from the row, which is
     * exactly an empty row with nothing to explain it.
     */
    private suspend inline fun <reified T> getList(path: String): List<T>? {
        // Annotated because getVal's type parameter is on its return type alone: hoisting this
        // into a local drops the expected type that addHeader used to supply.
        val key: String = PrefManager.getVal(PrefName.TraktClientId)
        Logger.log("Trakt GET $path (key ${if (key.isBlank()) "MISSING" else "present"})")
        val result = tryWithSuspend(snackbar = false) {
            val request = Request.Builder()
                .url("$BASE$path")
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("trakt-api-version", "2")
                .addHeader("trakt-api-key", key)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("Trakt HTTP ${response.code} for $path: ${response.message}")
                    null
                } else {
                    val body = response.body?.string()
                    if (body == null) {
                        Logger.log("Trakt empty body for $path")
                        null
                    } else {
                        val decoded = traktJson.decodeFromString<List<T>>(body)
                        Logger.log("Trakt ${response.code} $path -> ${decoded.size} items, ${body.length} bytes")
                        decoded
                    }
                }
            }
        }
        if (result == null) Logger.log("Trakt FAILED (null) $path")
        return result
    }
}
