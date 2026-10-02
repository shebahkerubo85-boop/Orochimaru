package ani.sanin.connections.anilist

import ani.sanin.util.Logger
import ani.sanin.connections.anilist.api.MediaTitle
import ani.sanin.connections.anilist.api.Query
import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.FranchiseType
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * AniList's rankings, used to sort the anime Franchise row.
 *
 * ## Why this exists
 *
 * Kitsu is the primary source for the row and it groups well: its franchise resource knows that
 * a seed belongs to a sequel, a prequel and a spin-off. But Kitsu carries no popularity or
 * trending figure on any of that, so it cannot answer "order these by trending" or "order these
 * by most liked". AniList can, for the same titles. So the row keeps Kitsu's grouping and gets
 * its ordering from here.
 *
 * ## Matching by title, not by id
 *
 * Kitsu entries have Kitsu ids. Turning those into AniList ids means one `/mappings` request per
 * entry, which is the per-card N+1 this whole design exists to avoid: a hundred cards would pay
 * a hundred extra requests to learn a ranking the page already had.
 *
 * So titles are matched instead. That is approximate, and deliberately so:
 *
 * - Matching on the *franchise* name first means "Naruto" matches the Naruto entry's position,
 *   which is the one a reader would expect the card to sort by anyway.
 * - A Kitsu entry's AniList title is often romanised differently ("Hagane no Renkinjutsushi"
 *   vs "Fullmetal Alchemist"). [normalize] folds case and punctuation so those still match,
 *   but a series with no English title can miss, and that is the accepted cost.
 * - A miss is not a wrong answer. It leaves the card unranked, and the sorter sorts unranked
 *   cards last rather than guessing a position.
 *
 * Where an entry *does* carry an AniList id, that is used first, because it is exact.
 *
 * ## Cost
 *
 * Two queries total, both cached for the process. Neither is walked per card.
 */
object AnilistFranchiseRanks {

    /**
     * Normalised title to its 1-based position in AniList's ranking.
     *
     * Keyed on the normalised form rather than the raw title so casing and punctuation do not
     * have to match exactly.
     */
    class Ranks(private val positions: Map<String, Int>) {

        /**
         * Where a card sits, best entry first.
         *
         * The best is the lowest position: a franchise is as ranked as its most ranked member,
         * which is how a reader thinks about it — they know the famous season, not the run.
         */
        fun rankOf(card: Franchise): Int? =
            // Franchise name first: it is the name the reader sorts by, so it is the entry whose
            // position best represents the card.
            positions[normalize(card.name)]
                ?: card.entries.mapNotNull { positionOf(it) }.minOrNull()

        private fun positionOf(entry: FranchiseEntry): Int? =
            entry.anilistId?.let { positions["id:$it"] } ?: positions[normalize(entry.title)]

        /** How many titles this ranking covers, used to turn a position into a count. */
        val size: Int get() = positions.size

        /** Only for tests: the raw map, so a match can be asserted rather than inferred. */
        internal fun raw(): Map<String, Int> = positions
    }

    private const val PAGE_SIZE = 50

    private var popularity: Ranks? = null
    private var trending: Ranks? = null

    /** All-time most popular, matching what the "Most Popular" option promises. */
    suspend fun popular(): Ranks = synchronized(this) {
        popularity ?: query("POPULARITY_DESC").also { popularity = it }
    }

    /** This week's most watched, matching what "Trending" promises. */
    suspend fun trending(): Ranks = synchronized(this) {
        trending ?: query("TRENDING_DESC").also { trending = it }
    }

    /**
     * One page of `sort: <order>` titles, mapped to their position.
     *
     * The response is already ordered, so the index *is* the rank. Ids are included because a
     * match by id is exact; titles are the fallback for entries that never had one.
     */
    private suspend fun query(sort: String): Ranks = try {
        val media = Injekt.get<Anilist>()
            .executeQuery<Query.Page>(
                """{ Page(perPage: $PAGE_SIZE){media(sort: $sort, type: ANIME, isAdult: false) {id title{romaji english userPreferred} } } }""",
                useToken = false,
            )
            ?.data?.page?.media

        if (media.isNullOrEmpty()) Ranks(emptyMap()) else Ranks(
            buildMap {
                media.forEachIndexed { index, m ->
                    val position = index + 1
                    // First write wins: AniList returns this page already ranked, so a title
                    // appearing twice must keep its better position.
                    putIfAbsent("id:${m.id}", position)
                    titlesOf(m.title)?.forEach { putIfAbsent(normalize(it), position) }
                }
            }
        )
    } catch (e: Exception) {
        Logger.log(e)
        // No ranking beats a wrong ranking: unranked cards sort last, so a failed query
        // degrades the row to "unordered" rather than to noise.
        Ranks(emptyMap())
    }

    /** Every spelling of a title AniList knows for it, so one of them is likely to match. */
    private fun titlesOf(title: MediaTitle?): List<String> =
        listOfNotNull(title?.romaji, title?.english, title?.userPreferred)

    /**
     * Folds a title down to something two sources are likely to agree on.
     *
     * Lowercased, with punctuation and whitespace removed, so "Hagane no Renkinjutsushi" and
     * "Hagane no Renkinjutsushi!" collide as intended. This is intentionally lossy: two
     * genuinely different titles that fold together would take the better position, which is a
     * far smaller problem than every title failing to match.
     */
    private fun normalize(title: String): String =
        title.lowercase()
            .filter { it.isLetterOrDigit() }
            .trim()

    /**
     * Copies [ranks] onto each card's trending position.
     *
     * Returns new cards rather than mutating: [Franchise] is shared with the adapter's list,
     * which is also read off the main thread while AniList pages are still landing.
     */
    fun applyTrending(cards: List<Franchise>, ranks: Ranks): List<Franchise> =
        cards.map { card ->
            card.copy(trendingRank = ranks.rankOf(card) ?: card.trendingRank)
        }

    /**
     * Copies [ranks] onto each card's popularity figure.
     *
     * The sorter's Popular branch compares a *magnitude*, so a position cannot be used directly:
     * lower is better for a rank but higher is better for a count. The position is converted to
     * a count-like value so the existing comparison stays correct, and only among the titles
     * this ranking actually covers. That is sound because a title missing from a top-50 page is
     * by definition less popular than one that is present, which is all this ordering asserts.
     */
    fun applyPopular(cards: List<Franchise>, ranks: Ranks): List<Franchise> =
        cards.map { card ->
            val position = ranks.rankOf(card) ?: return@map card
            card.copy(likeCount = ranks.size - position + 1)
        }

    /**
     * Fills in the per-entry detail the Franchise screen shows, for cards built by Kitsu.
     *
     * Kitsu supplies the grouping and the artwork but no synopsis, runtime, score, air date or
     * format, all of which the screen's card shows. One `id_in` query covers every entry of
     * every card, so this is one request for the screen rather than one per entry.
     *
     * Entries already carrying an AniList id are matched exactly. The rest are matched by
     * normalised title, which can miss — a miss leaves that entry's rows blank rather than
     * filling them with another show's details.
     *
     * @return the same cards with their entries enriched where a match was found.
     */
    suspend fun enrich(cards: List<Franchise>): List<Franchise> {
        val wanted = cards.flatMap { it.entries }.mapNotNull { it.anilistId }.distinct()
        if (wanted.isEmpty()) return cards

        val details = try {
            fetchDetails(wanted)
        } catch (e: Exception) {
            Logger.log(e)
            return cards
        }
        if (details.isEmpty()) return cards

        val byId = details.associateBy { it.id }
        val byTitle = buildMap<String, Query.Media?> {
            details.forEach { m ->
                titlesOf(m.title).forEach { putIfAbsent(normalize(it), m) }
            }
        }

        return cards.map { card ->
            card.copy(entries = card.entries.map { entry ->
                val detail = entry.anilistId?.let { byId[it] }
                    ?: byTitle[normalize(entry.title)]
                if (detail == null) entry else entry.withDetail(detail)
            })
        }
    }

    /** AniList caps `id_in` per query, so the request is chunked rather than truncated. */
    private suspend fun fetchDetails(ids: List<Int>): List<Query.Media> =
        ids.chunked(MAX_IDS_PER_QUERY).flatMap { chunk ->
            Injekt.get<Anilist>()
                .executeQuery<Query.Page>(
                    """{ Page(page:1, perPage:${chunk.size}){media(id_in:[${chunk.joinToString(",")}], type:ANIME, isAdult:false){id format averageScore duration description startDate{year month day} title{romaji english userPreferred} mediaListEntry{status progress score(format:POINT_100)} } } }""",
                    useToken = false,
                )
                ?.data?.page?.media.orEmpty()
        }

    /** Copies an AniList detail object onto an entry. */
    private fun FranchiseEntry.withDetail(detail: Query.Media): FranchiseEntry {
        val date = detail.startDate
        val dateKey = when {
            date?.year == null -> null
            // A year-only date is deliberately not promoted to yyyy0101: an entry that only
            // knows its year must sort after anything with a real date, not before it.
            date.month == null -> null
            else -> date.year * 10000 + date.month * 100 + (date.day ?: 1)
        }
        return copy(
            airDate = airDate ?: date?.let { it.toStringOrEmpty() }.orEmpty().ifBlank { null },
            airDateSortKey = airDateSortKey ?: dateKey,
            durationMinutes = durationMinutes ?: detail.duration,
            score = score ?: detail.averageScore,
            // Format only, not relation type: AniList reports a relation on the *edge* between
            // two titles, and its direction is the other title's, so reading one here would
            // classify a prequel as its sequel. The relation is applied where both ends are
            // known, in FranchiseStub, and otherwise format is the honest signal.
            type = type ?: FranchiseType.fromFormat(detail.format?.toString()),
            synopsis = synopsis ?: stripHtml(detail.description),
            // The user's own status, so the card's chip can show it without its own request.
            // AniList returns a null mediaListEntry for anything not on the list, which is the
            // same case as "we have not loaded it yet" from the chip's point of view.
            listStatus = listStatus ?: detail.mediaListEntry?.status?.toString(),
        )
    }

    /**
     * AniList descriptions arrive as HTML fragments with `<br>` and the occasional `<i>`.
     *
     * Stripped to plain text here rather than parsed by each view, so the card, the info tab and
     * anything downstream all read the same string and none of them has to know it was markup.
     */
    fun stripHtml(html: String?): String? = html
        ?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        ?.replace(Regex("<[^>]+>"), "")
        ?.replace("&quot;", "\"")
        ?.replace("&#039;", "'")
        ?.replace("&amp;", "&")
        ?.replace("&lt;", "<")
        ?.replace("&gt;", ">")
        ?.replace(Regex("[ \\t]+"), " ")
        ?.replace(Regex("\\n{3,}"), "\n\n")
        ?.trim()
        ?.ifBlank { null }

    /**
     * Drops both cached rankings, for a sign-out or an AniList reset.
     *
     * The next sort press refetches. Cheap enough that invalidating eagerly is not worth the
     * extra branch.
     */
    fun clear() = synchronized(this) {
        popularity = null
        trending = null
    }

    /** AniList's documented ceiling for `id_in` in one query. */
    private const val MAX_IDS_PER_QUERY = 50
}