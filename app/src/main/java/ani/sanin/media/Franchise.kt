package ani.sanin.media

import ani.sanin.R

/**
 * One entry in a franchise, as shown on the franchise card.
 *
 * The card only needs a poster and a year, so this is deliberately not the app's [Media]
 * type: a franchise may draw its entries from AniList, TMDB, or a generated map, and the
 * card should not care which.
 */
/**
 * What kind of entry this is, for the screen's filter pill.
 *
 * Each format the sources actually report gets its own category, so the pill can offer
 * "TV", "ONA", "OVA", "Special", "Movie" and the reader can filter to exactly the kind
 * they mean. AniList reports a dozen [ani.sanin.connections.anilist.api.MediaFormat] and
 * [ani.sanin.connections.anilist.api.MediaRelation] values; the ones that are not an anime
 * format (MANGA/NOVEL/MUSIC) fold away, and everything else keeps its own chip.
 *
 * [SEQUENCE] is a legacy bucket kept for the Trakt/TMDB side, which does not know a format
 * beyond "movie or show"; it reads as the plain series line. [SPIN_OFF] and [SIDE_STORY]
 * come from relation type rather than format: a spin-off film is a movie by format but a
 * spin-off by relation, and the relation is the distinction the pill exists to draw.
 */
enum class FranchiseType {
    /** A TV series (AniList `TV`), or a Trakt/TMDB show with no finer format. */
    SEQUENCE,

    TV,
    TV_SHORT,
    ONA,
    OVA,
    SPECIAL,
    MOVIE,
    SIDE_STORY,
    SPIN_OFF,
    ;

    /**
     * The pill's label for this category.
     *
     * A string resource rather than a literal so it translates with the rest of the screen.
     */
    fun labelRes() = when (this) {
        SEQUENCE -> R.string.franchise_filter_series
        TV -> R.string.franchise_filter_tv
        TV_SHORT -> R.string.franchise_filter_tv_short
        ONA -> R.string.franchise_filter_ona
        OVA -> R.string.franchise_filter_ova
        SPECIAL -> R.string.franchise_filter_special
        MOVIE -> R.string.franchise_filter_movie
        SIDE_STORY -> R.string.franchise_filter_side_story
        SPIN_OFF -> R.string.franchise_filter_spin_off
    }

    /**
     * The breakdown plural for this category, e.g. "3 OVAs".
     *
     * Null for [SEQUENCE], which is reported through [R.plurals.franchise_count_series] by
     * the same label it shows, so the two never drift.
     */
    fun countRes() = when (this) {
        SEQUENCE -> R.plurals.franchise_count_series
        TV -> R.plurals.franchise_count_tv
        TV_SHORT -> R.plurals.franchise_count_tv_short
        ONA -> R.plurals.franchise_count_ona
        OVA -> R.plurals.franchise_count_ova
        SPECIAL -> R.plurals.franchise_count_special
        MOVIE -> R.plurals.franchise_count_movies
        SIDE_STORY -> R.plurals.franchise_count_side_story
        SPIN_OFF -> R.plurals.franchise_count_spin_off
    }

    companion object {
        /**
         * Folds AniList's format into a category.
         *
         * Null when the format is one of MANGA, NOVEL or MUSIC, which cannot appear in an
         * anime franchise and would only mean the source sent something unexpected.
         */
        fun fromFormat(format: String?): FranchiseType? = when (format) {
            "TV" -> TV
            "TV_SHORT" -> TV_SHORT
            "ONA" -> ONA
            "OVA" -> OVA
            "SPECIAL" -> SPECIAL
            "MOVIE" -> MOVIE
            "ONE_SHOT" -> SIDE_STORY
            else -> null
        }

        /**
         * Folds AniList's relation type into a category.
         *
         * A relation is about *how* this entry connects, so it can override the format: an entry
         * whose format is MOVIE but whose relation is SPIN_OFF is a spin-off film, which is the
         * distinction the pill exists to draw.
         */
        fun fromRelation(relation: String?): FranchiseType? = when (relation) {
            "SPIN_OFF" -> SPIN_OFF
            "SIDE_STORY" -> SIDE_STORY
            "PREQUEL", "SEQUEL", "PARENT", "SUMMARY", "COMPILATION", "ALTERNATIVE" -> SEQUENCE
            else -> null
        }
    }
}

data class FranchiseEntry(
    /**
     * Release year as a plain four-digit string, already formatted for display. Empty when
     * the source had no date. Compare with [sortYear], not this: an empty string sorts
     * ahead of every real year as text.
     */
    val year: String,
    /** The same year as a number, for ordering. Null when unknown, so those sort last. */
    val sortYear: Int? = year.toIntOrNull(),
    /** Poster URL, or null when the source had no artwork. */
    val posterUrl: String?,
    /** Title used when this entry is opened. */
    val title: String,
    /**
     * Wide artwork for the card's banner, when the source has it.
     *
     * Optional on purpose: Kitsu serves only a poster per anime, so there is no wide variant
     * to give, while TMDB has one for most titles. When null the banner falls back to
     * [posterUrl] cropped.
     */
    val backdropUrl: String? = null,
    /**
     * This entry's AniList title id, when it came from AniList.
     *
     * Null for entries that did not, which is what lets [Franchise.trendingRank] and
     * [Franchise.likeCount] be filled in afterwards: AniList ranks titles by id, so a card
     * grouped by Kitsu can still be ordered by AniList without refetching anything per entry.
     */
    val anilistId: Int? = null,
    /**
     * Air date as a display string, more precise than [year] where the source has it.
     *
     * The screen sorts on [airDateSortKey] rather than on this, since this is already formatted
     * for people and formatted dates do not sort.
     */
    val airDate: String? = null,
    /**
     * Air date as a comparable number, yyyyMMdd.
     *
     * Null when the date is unknown or only a year is known, which is a deliberate loss: a
     * year-only entry sorts after anything with a real date rather than pretending to be in
     * January. [Franchise.sortOrder] falls back to [sortYear] for these.
     */
    val airDateSortKey: Int? = null,
    /** Runtime in minutes. Null when unknown, so the card omits the row rather than guessing. */
    val durationMinutes: Int? = null,
    /** Average score 0-100, or null when the source did not score the title. */
    val score: Int? = null,
    /**
     * This entry's category for the filter pill.
     *
     * Null when the source said nothing usable, in which case the entry shows under "All" only
     * and gets no chip of its own — the pill is built from what is actually present.
     */
    val type: FranchiseType? = null,
    /**
     * Synopsis, as plain text with HTML already stripped.
     *
     * A teaser: the screen clamps this to the card's height and ellipsises, and the full text
     * lives on the info tab. Stored stripped rather than as markup so no view has to parse it.
     */
    val synopsis: String? = null,
    /**
     * TMDB id, when the source is Trakt and the title maps to one.
     *
     * Lets the info affordance open a real page for a movie franchise. Null for anime, which
     * has no TMDB equivalent worth sending the user to.
     */
    val tmdbId: Int? = null,
    /**
     * Trakt id, for the same reason on the Trakt side.
     *
     * Kept next to [tmdbId] rather than instead of it: the user chooses the destination, and
     * one of the two is missing for almost every title.
     */
    val traktId: Int? = null,
    /**
     * The user's own list status for this title, e.g. "COMPLETED".
     *
     * Null both when the title is not on their list and when the screen has not loaded it yet;
     * the chip reads "Add to List" for both, which is what a reader expects in each case. Lived
     * here rather than fetched per row so the status costs nothing to display.
     */
    val listStatus: String? = null,
    /**
     * Which TMDB endpoint [tmdbId] belongs to, "movie" or "tv".
     *
     * The id alone does not say which page it addresses, so without this a TV entry would open
     * a movie URL. Null for entries that did not come from TMDB.
     */
    val tmdbType: String? = null,
    /**
     * Season number, when this entry is one season of a show.
     *
     * Lets the screen send the user to the tapped season rather than the show's front page. Null
     * for movie entries.
     */
    val seasonNumber: Int? = null,
) : java.io.Serializable

/**
 * A franchise as shown on the Explore card.
 *
 * [entries] is in franchise order. The card shows as many as fit its width; the rest are
 * reached on the dedicated franchise screen.
 */
data class Franchise(
    /**
     * The franchise's own name, e.g. "Naruto". Never an individual entry's title: the
     * per-entry titles are not shown on the card at all.
     */
    val name: String,
    /**
     * Banner image. Taken from the latest entry in [entries], since that is the one whose
     * artwork is most likely to still be fresh.
     */
    val bannerUrl: String?,
    /**
     * Wordmark for the header, as an image.
     *
     * Null when the source has none, which is the common case on the anime side: AniList has no
     * logo field, so those are filled in from AniZip's clearart at display time. Trakt serves a
     * logo directly. The header falls back to the name alone rather than stretching a poster into
     * a stand-in, because a cropped title card is not a logo and reads as a mistake.
     */
    val logoUrl: String? = null,
    /** Entries in order, oldest first. */
    val entries: List<FranchiseEntry>,
    /**
     * True when this card exists only because a source listed it, with nothing to group it by.
     *
     * Movie mode has no franchise resource to compare against, so a single film is still shown
     * as requested. Kept as an explicit flag rather than inferred from entry count, because the
     * row's sort must not quietly drop these while the toggle says otherwise.
     */
    val isSingleEntry: Boolean = entries.size <= 1,
    /**
     * Position in the source's trending ranking, 1-based. Null when the source did not rank it.
     * Only read by [FranchiseSort.TRENDING].
     */
    val trendingRank: Int? = null,
    /**
     * How many users liked the source list this card came from. Null when unknown. Only read by
     * [FranchiseSort.POPULAR], which is an all-time measure: it is a like total, not this week's
     * activity.
     *
     * Carries two different things by source, which the sorter does not care about because it
     * only ever compares magnitudes: a Trakt list's like total, or the AniList favourite count
     * of the card's best-ranked entry.
     */
    val likeCount: Int? = null,
) : java.io.Serializable {
    val latest: FranchiseEntry? get() = entries.lastOrNull()

    /**
     * [entries] in the order the franchise screen draws them.
     *
     * Air date is the ordering, with release year as the tiebreak for the many titles that only
     * have a year, and the original position last so that two entries with neither stay in the
     * order the source gave them. Stable, which is what keeps the connector from re-ordering
     * itself as pages land.
     */
    val sortOrder: List<FranchiseEntry>
        get() = entries.sortedWith(
            compareBy(
                { it.airDateSortKey ?: (it.sortYear?.let { year -> year * 10000 } ?: Int.MAX_VALUE) },
                { it.sortYear ?: Int.MAX_VALUE }
            )
        )
}
