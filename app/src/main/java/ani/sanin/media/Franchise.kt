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
 * These are the categories a reader actually distinguishes, not the source's raw enum. AniList
 * reports a dozen [ani.sanin.connections.anilist.api.MediaFormat] and
 * [ani.sanin.connections.anilist.api.MediaRelation] values; mapping them down to five keeps the
 * pill from becoming a taxonomy.
 *
 * [SEQUENCE] is the main line. [SIDE_STORY] covers OVAs, specials and shorts — things that
 * exist alongside the main line rather than continuing it. [SPIN_OFF] is separate because it
 * reads as "different show, same world", which is how people describe a Spider-Man film versus
 * a Naruto OVA.
 */
enum class FranchiseType {
    SEQUENCE,
    MOVIE,
    OVA,
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
        MOVIE -> R.string.franchise_filter_movie
        OVA -> R.string.franchise_filter_ova
        SIDE_STORY -> R.string.franchise_filter_side_story
        SPIN_OFF -> R.string.franchise_filter_spin_off
    }

    companion object {
        /**
         * Folds AniList's format into a category.
         *
         * Null when the format is one of MANGA, NOVEL or MUSIC, which cannot appear in an
         * anime franchise and would only mean the source sent something unexpected.
         */
        fun fromFormat(format: String?): FranchiseType? = when (format) {
            "TV", "TV_SHORT", "ONA" -> SEQUENCE
            "MOVIE" -> MOVIE
            "OVA", "SPECIAL" -> SIDE_STORY
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
            "SPIN_OFF", "SIDE_STORY" -> SPIN_OFF
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
