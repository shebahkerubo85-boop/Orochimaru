package ani.sanin.media


/**
 * Builds [Franchise] cards from the app's own [Media] and its AniList relations.
 *
 * This is the fallback path, used only when Kitsu cannot answer for a seed: Kitsu is down,
 * has no mapping for the title, or the seed has no Kitsu franchise. Prefer it and the cards
 * come out with a real franchise name and full membership; fall back to this and they are
 * approximate, which is the honest trade for not showing nothing at all.
 *
 * The limitations are worth being explicit about:
 *
 * - AniList relations have no ordinal, so order here is release year, not story order.
 *   "Naruto" before "Naruto: Shippuden" happens to be correct, but a prequel released
 *   after its sequel will sort wrong. Kitsu's curated `position` is what fixes this.
 * - Only one hop from the seed is collected. A seed's own sequel links to the one after
 *   it, but those grandchildren are not walked, so cards stay short.
 * - [Franchise.name] is the seed's title, which is the best available name but is not
 *   necessarily the franchise's name.
 *
 * Takes the app's [Media] rather than the API's, because that is what the query layer has
 * already normalized relations into.
 */
object FranchiseStub {

    /**
     * Relations that make an entry part of the same ordered story.
     *
     * PREQUEL, SEQUEL and PARENT are the three that order a story. ADAPTATION and SOURCE are
     * left out because they point at a different medium, and CHARACTER and SUMMARY at a
     * different story entirely.
     *
     * These are the API enum spellings, matched against [Media.relation]'s first line, which
     * carries the enum name rather than its localized display string. See [isOrderedRelation].
     */
    private val ORDERED_RELATIONS = setOf("PREQUEL", "SEQUEL", "PARENT")

    /**
     * Whether this related entry is one of the story-order relations.
     *
     * [Media.relation] is only matched on its first line: the query layer writes
     * `"PREQUEL\nTV"`, a relation plus the related media's format.
     */
    private fun Media.isOrderedRelation() =
        relation?.substringBefore('\n')?.trim() in ORDERED_RELATIONS

    /**
     * The most entries a card will ever hold, counting the seed. The layout shows a clipped
     * row and sends the rest to the franchise screen, but a franchise this long in one hop
     * means the seed was picked badly, so it is capped rather than left to grow.
     */
    private const val MAX_ENTRIES = 24

    /**
     * Builds a card for [seed], or returns null when it has no ordered relations at all. A
     * card holding a single poster is not a franchise, it is just a smaller version of the
     * Popular row this replaced.
     */
    fun fromMedia(seed: Media): Franchise? {
        val name = seed.userPreferredName.takeIf { it.isNotBlank() }
            ?: seed.name?.takeIf { it.isNotBlank() }
            ?: return null

        val related = seed.relations.orEmpty()
            .filter { it.isOrderedRelation() }
            .filter { it.id != seed.id }
        if (related.isEmpty()) return null

        // The seed sorts along with the rest by year rather than being pinned to the front:
        // it is part of the franchise, so its own place in the order is the correct one.
        val entries = (listOf(seed) + related)
            .distinctBy { it.id }
            .sortedBy { it.startDate?.year }
            .map { it.toEntry() }
            .take(MAX_ENTRIES)

        return Franchise(
            name = name,
            // The banner is the latest entry's artwork, per spec.
            bannerUrl = entries.last().posterUrl,
            entries = entries,
        )
    }

    private fun Media.toEntry() = FranchiseEntry(
        year = startDate?.year?.toString().orEmpty(),
        sortYear = startDate?.year,
        posterUrl = cover,
        title = userPreferredName.ifBlank { name.orEmpty() },
    )
}
