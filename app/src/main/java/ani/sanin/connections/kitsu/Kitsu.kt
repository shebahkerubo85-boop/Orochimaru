package ani.sanin.connections.kitsu

import ani.sanin.connections.anilist.api.FuzzyDate
import ani.sanin.media.Franchise
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseEntry
import ani.sanin.media.Media
import ani.sanin.media.anime.Anime
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWithSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One page of a Kitsu category listing.
 *
 * [failed] is what separates "the request failed" from "the category has no more cards":
 * both come back with no items, but the Discovery grid offers a retry for the former only.
 */
data class CategoryPage(
    val media: List<Media>,
    val hasMore: Boolean,
    val failed: Boolean = false,
)

/**
 * Kitsu client, used to build the anime Franchise cards on the Explore page.
 *
 * Needs no API key: every endpoint here is public. That is the main reason anime goes
 * through Kitsu rather than AniList. AniList is currently documented as degraded and
 * capped at 30 requests per minute with a further undocumented burst limiter, and it does
 * not model franchises at all, only unordered relations.
 *
 * ## How a card is assembled
 *
 * Kitsu models this as anime -> installment -> franchise -> installments, and the ids line
 * up with AniList through the `mappings` resource:
 *
 * ```
 * /mappings?filter[externalSite]=anilist/anime&filter[externalId]=20&include=item
 * /anime/:id/relationships/installments
 * /installments/:id/relationships/franchise
 * /franchises/:id/relationships/installments
 * /installments/:id/relationships/media
 * /anime?filter[id]=...
 * ```
 *
 * The ids are not the same number on both sides. Naruto is Kitsu 11 but AniList 20, and
 * AniList 11 has no mapping at all, so the numbers must never be assumed to match.
 *
 * The MAL id cannot be used for the bridge instead: Kitsu returns `idMal: null` for many
 * titles, Naruto included.
 *
 * ## Ordering
 *
 * Curated order comes from `position` on each installment, and it is the correct story
 * order, but it is currently unreachable: every route that serializes an installment
 * resource returns HTTP 500 while the relationship routes return 200. [curatedOrder] is
 * gated behind [PrefName.KitsuCuratedOrder], off by default, and re-probes every process
 * start so the switch flips over on its own once Kitsu fixes it.
 *
 * Until then entries are ordered by `startDate`, which is release order rather than story
 * order: right for straight-line franchises, wrong for a prequel released after its
 * sequel.
 */
object Kitsu {

    private const val BASE = "https://kitsu.io/api/edge"
    private const val ANILIST_SITE = "anilist/anime"

    /** Kitsu's own API rejects a larger `page[limit]`. */
    private const val PAGE_LIMIT = 20

    private val okHttpClient
        get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    /**
     * Entries per card.
     *
     * Each entry costs a request to resolve its media id and another to read its attributes,
     * so a card is O(entries) requests. 24 covers the franchises actually seen to be large
     * while keeping a card's cost around 30 requests.
     */
    private const val MAX_ENTRIES = 24

    private val franchiseCache = ConcurrentHashMap<Int, Franchise>()

    /**
     * AniList ids known to have no Kitsu franchise, or that Kitsu could not answer for.
     *
     * Separate from [franchiseCache] because [ConcurrentHashMap] holds no nulls, and not
     * caching the misses would re-spend the whole request chain on every rebuild for every
     * title that is not in Kitsu's database, which is a lot of them.
     */
    private val missingCache = ConcurrentHashMap<Int, Boolean>()

    /**
     * Every Kitsu category, fetched once per process.
     *
     * The category catalogue is a global list that changes rarely, so re-walking its eleven
     * pages every time Discovery opens would be pure waste. Null until the first successful
     * fetch; a failed fetch leaves it null so the next open retries.
     */
    @Volatile
    private var categoryCache: List<KitsuCategory>? = null

    /** Successful poster lookups, sourced from the category's own anime listing. */
    private val categoryThumbnailCache = ConcurrentHashMap<String, String>()

    /**
     * Franchise id per Kitsu anime id.
     *
     * Without this every seed re-walks anime -> installment -> franchise, which is the only
     * part of the chain that is not already cached at the id it ends on.
     */
    private val franchiseIdCache = ConcurrentHashMap<String, String>()

    private val kitsuIdCache = ConcurrentHashMap<Int, String>()
    private val franchiseNameCache = ConcurrentHashMap<String, String>()

    /** Installment ids per franchise, shared by every seed that resolves to it. */
    private val installmentsCache = ConcurrentHashMap<String, List<String>>()

    /** Anime id per installment. Every entry of every card walks these same pairs. */
    private val mediaIdCache = ConcurrentHashMap<String, String>()

    /** Kitsu anime resources by id, shared across cards and across a process lifetime. */
    private val animeCache = ConcurrentHashMap<String, KitsuResource>()

    /**
     * AniList id per Kitsu anime id, read off the `mappings` include.
     *
     * Every entry of a card needs one so its poster can open that entry's own page: a card's
     * seed arrives with an AniList id already known, but its siblings only exist on Kitsu, and
     * without this they were built with no id at all and could not be opened.
     */
    private val anilistIdCache = ConcurrentHashMap<String, Int>()

    /**
     * Whether Kitsu currently serves curated installment positions, probed at most once per
     * process. Null until probed. See [curatedOrder].
     */
    @Volatile
    private var positionsAvailable: Boolean? = null

    /**
     * Whether to try Kitsu's curated installment order for this franchise.
     *
     * Kitsu has a curated `position` on each installment and that is the correct story
     * order, but it is unreachable right now: every route that serializes an installment
     * resource returns HTTP 500, consistently, for every id tried, while the relationship
     * routes return 200. `/installments/:id`, `/anime/:id/installments`,
     * `/franchises/:id/installments` and `?include=installments` are all affected, so this
     * is Kitsu's installment serializer failing, not a bad request or a rate limit.
     *
     * Only reachable when [PrefName.KitsuCuratedOrder] is on, so the default install spends
     * no requests here and silently degrades to release-date order. That order is right for
     * straight-line franchises and wrong for a prequel released after its sequel.
     *
     * The probe is the point: it re-runs every process start, so the first launch after
     * Kitsu fixes the endpoint picks curated order up on its own, with no app update. Only
     * the outcome is remembered per process, never a permanent "unavailable".
     */
    private suspend fun curatedOrder(installmentIds: List<String>): Boolean {
        if (!PrefManager.getVal<Boolean>(PrefName.KitsuCuratedOrder)) return false

        positionsAvailable?.let { return it }
        // A single known-good installment is enough to tell whether the route works.
        val probeId = installmentIds.firstOrNull() ?: return false
        val usable = get("/installments/$probeId")?.dataOne()
            ?.installmentAttributes()?.position != null
        positionsAvailable = usable
        return usable
    }

    /** Curated position per installment, or an empty map when the route is unusable. */
    private suspend fun curatedPositions(installmentIds: List<String>): Map<String, Int> {
        if (!curatedOrder(installmentIds)) return emptyMap()
        return installmentIds.mapNotNull { id ->
            get("/installments/$id")?.dataOne()
                ?.installmentAttributes()?.position?.let { id to it }
        }.toMap()
    }

    /**
     * Builds the Franchise card for an AniList anime, or null when it has no Kitsu
     * franchise. Null is also the "could not reach Kitsu" answer; the caller treats a card as
     * simply absent rather than surfacing an error.
     */
    /**
     * Read real Kitsu categories in capped pages, then rank locally by the catalogue's own
     * media counts. This avoids relying on a sort field the API may not expose.
     */
    suspend fun categories(limit: Int = 60): List<KitsuCategory> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 100)
        val found = ArrayList<KitsuCategory>()
        var offset = 0
        while (offset < safeLimit) {
            val pageSize = minOf(PAGE_LIMIT, safeLimit - offset)
            val document = get("/categories?page[limit]=$pageSize&page[offset]=$offset") ?: break
            val resources = document.dataList()
            if (resources.isEmpty()) break
            resources.forEach { resource ->
                val id = resource.id ?: return@forEach
                val attributes = resource.categoryAttributes() ?: return@forEach
                val slug = attributes.slug?.takeIf { it.isNotBlank() } ?: return@forEach
                val title = attributes.title?.takeIf { it.isNotBlank() } ?: slug
                found += KitsuCategory(id, slug, title, attributes.description, attributes.totalMediaCount)
            }
            offset += resources.size
            if (resources.size < pageSize) break
        }
        found.distinctBy { it.slug }.sortedByDescending { it.totalMediaCount }.take(safeLimit)
    }

    /**
     * Fetch one Kitsu category page. AniList IDs are read only from Kitsu's included mappings;
     * a Kitsu ID is never passed through as an AniList ID.
     */
    suspend fun categoryAnime(
        categorySlug: String,
        offset: Int = 0,
        limit: Int = PAGE_LIMIT,
    ): KitsuCategoryPage = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val document = get(
            "/anime?filter[categories]=$categorySlug&sort=-userCount" +
                "&page[limit]=$safeLimit&page[offset]=$safeOffset&include=mappings"
        ) ?: return@withContext KitsuCategoryPage(emptyList(), 0, false, false)

        val includedMappings = document.included.orEmpty().associateBy { it.id }
        val resources = document.dataList()
        val items = resources.mapNotNull { resource ->
            val id = resource.id ?: return@mapNotNull null
            val attributes = resource.animeAttributes() ?: return@mapNotNull null
            val title = attributes.titles?.en?.takeIf { it.isNotBlank() }
                ?: attributes.titles?.enJp?.takeIf { it.isNotBlank() }
                ?: attributes.titles?.jaJp?.takeIf { it.isNotBlank() }
                ?: attributes.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings")
                .asSequence()
                .mapNotNull { includedMappings[it]?.mappingAttributes() }
                .firstOrNull { it.externalSite == ANILIST_SITE }
                ?.externalId?.toIntOrNull()
            KitsuDiscoveryItem(
                id = id,
                title = title,
                synopsis = attributes.synopsis ?: attributes.description,
                year = attributes.startDate?.take(4)?.toIntOrNull() ?: attributes.seasonYear,
                userCount = attributes.userCount,
                score = attributes.averageRating?.toDoubleOrNull()?.div(10.0),
                posterUrl = attributes.posterImage?.large
                    ?: attributes.posterImage?.medium ?: attributes.posterImage?.small,
                anilistId = anilistId,
            )
        }
        KitsuCategoryPage(items, resources.size, resources.size >= safeLimit, true)
    }

    /**
     * Popular anime directly from Kitsu, independent of AniList's catalogue and ranking.
     * This is a small discovery feed, not a per-title lookup loop.
     */
    suspend fun discoverEditorial(limit: Int = 20): List<KitsuDiscoveryItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PAGE_LIMIT)
        val document = get("/anime?sort=-userCount&page[limit]=$safeLimit&include=mappings")
            ?: return@withContext emptyList()
        val includedMappings = document.included.orEmpty().associateBy { it.id }
        document.dataList().mapNotNull { resource ->
            val id = resource.id ?: return@mapNotNull null
            val attributes = resource.animeAttributes() ?: return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings")
                .asSequence()
                .mapNotNull { includedMappings[it]?.mappingAttributes() }
                .firstOrNull { it.externalSite == ANILIST_SITE }
                ?.externalId?.toIntOrNull()
            val title = attributes.titles?.en?.takeIf { it.isNotBlank() }
                ?: attributes.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            KitsuDiscoveryItem(
                id = id,
                title = title,
                synopsis = attributes.synopsis ?: attributes.description,
                year = attributes.startDate?.take(4)?.toIntOrNull() ?: attributes.seasonYear,
                userCount = attributes.userCount,
                score = attributes.averageRating?.toDoubleOrNull()?.div(10.0),
                posterUrl = attributes.posterImage?.medium ?: attributes.posterImage?.small,
                anilistId = anilistId,
            )
        }
    }

    suspend fun franchiseCard(anilistId: Int): Franchise? = withContext(Dispatchers.IO) {
        if (missingCache.containsKey(anilistId)) return@withContext null
        franchiseCache[anilistId]?.let { return@withContext it }
        val card = tryWithSuspend(snackbar = false) {
            buildCard(anilistId)
        }
        if (card == null) missingCache[anilistId] = true else franchiseCache[anilistId] = card
        card
    }

    private suspend fun buildCard(anilistId: Int): Franchise? {
        val kitsuId = kitsuAnimeId(anilistId) ?: run {
            Logger.log("Kitsu: anilist $anilistId has no kitsu mapping")
            return null
        }

        // Any entry of the franchise identifies the franchise, so the installment chain is
        // the same for all of them.
        val franchiseId = franchiseIdOf(kitsuId) ?: run {
            Logger.log("Kitsu: kitsu $kitsuId belongs to no franchise")
            return null
        }
        val allInstallments = installmentsOf(franchiseId)
        if (allInstallments.isEmpty()) {
            Logger.log("Kitsu: franchise $franchiseId returned no installments")
            return null
        }

        val installmentIds = allInstallments.take(MAX_ENTRIES)
        // Pair each installment with its anime before losing track of which is which:
        // ordering needs the installment id, the card needs the anime id.
        val pairs = installmentIds.mapNotNull { installment ->
            mediaIdOf(installment)?.let { installment to it }
        }
        if (pairs.isEmpty()) {
            Logger.log("Kitsu: franchise $franchiseId had ${installmentIds.size} installments, none resolved to a media id")
            return null
        }

        val positions = curatedPositions(installmentIds)
        val animes = animeByIds(pairs.map { it.second }).ifEmpty {
            Logger.log("Kitsu: ${pairs.size} media ids of franchise $franchiseId all failed to load")
            return null
        }

        val entries = animes
            .mapNotNull { anime ->
                // The mapping carries the AniList id for every sibling, so the seed is only
                // a fallback: it is the one entry whose id was already known, and trusting it
                // costs nothing if the mapping agrees.
                val resolvedId = anilistIdOf(anime.id())
                    ?: if (anime.id() == kitsuId) anilistId else null
                val mapped = anime.toEntry(resolvedId) ?: return@mapNotNull null
                // Media ids are unique per anime, so one position per entry is enough.
                val position = pairs.firstOrNull { it.second == anime.id() }?.first
                    ?.let { positions[it] }
                mapped to position
            }
            .sortedWith(
                // Curated position first where available, release date as the tiebreak so a
                // missing position never jumps an entry to the front.
                compareByDescending<Pair<FranchiseEntry, Int?>> { it.second != null }
                    .thenBy { it.second ?: Int.MAX_VALUE }
                    .thenBy { it.first.sortYear }
            )
            .map { it.first }
            .ifEmpty { return null }

        // A one-installment franchise is built here even though it is a lone entry, because
        // whether the row shows it is the user's choice and not a fact about the data. The
        // sort dialog's single-entry switch is what filters it, and it filters for the movie
        // row the same way, so deciding it here would make the switch work on one row only.
        // The requests spent reaching this point are the same either way, since this used to be
        // the check that ended the build.

        val name = franchiseName(franchiseId)
            ?: animes.firstNotNullOfOrNull { it.animeAttributes()?.displayTitle() }
            ?: run {
                Logger.log("Kitsu: franchise $franchiseId had no name and no titled anime")
                return null
            }
        val card = Franchise(
            // The franchise's own title, not the seed's: a Shippuden seed still yields "Naruto".
            name = name,
            // Per spec the banner is the latest entry's artwork.
            bannerUrl = entries.last().posterUrl,
            entries = entries,
        )
        Logger.log("Kitsu: built '$name' for anilist $anilistId with ${entries.size} entries")
        return card
    }

    /**
     * The franchise's own title.
     *
     * One request, cached per franchise because several seeds can share one franchise and
     * would otherwise each pay for it.
     */
    private suspend fun franchiseName(franchiseId: String): String? {
        franchiseNameCache[franchiseId]?.let { return it }
        val title = get("/franchises/$franchiseId")?.dataOne()?.franchiseAttributes()
            ?.let { attrs ->
                attrs.titles?.en?.takeIf { it.isNotBlank() }
                    ?: attrs.canonicalTitle?.takeIf { it.isNotBlank() }
            } ?: return null
        return title.also { franchiseNameCache[franchiseId] = it }
    }

    /**
     * AniList id to Kitsu anime id.
     *
     * The `mappings` resource is the only bridge that works: `filter[externalId]` takes a
     * comma-separated list and `include=item` inlines the anime, so one request resolves a
     * whole batch.
     */
    suspend fun kitsuAnimeId(anilistId: Int): String? {
        kitsuIdCache[anilistId]?.let { return it }

        val ids = animeIdsForAnilist(listOf(anilistId))
        return ids[anilistId]?.also { kitsuIdCache[anilistId] = it }
    }

    /**
     * AniList ids to Kitsu anime ids, batched because [PAGE_LIMIT] caps a single request.
     *
     * Kitsu returns the mapping rows unordered, so the pairing is read from each row's own
     * `externalId` rather than from position in the response.
     */
    private suspend fun animeIdsForAnilist(anilistIds: List<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (batch in anilistIds.distinct().chunked(PAGE_LIMIT)) {
            val ids = batch.joinToString(",")
            val doc = get(
                "/mappings?filter[externalSite]=$ANILIST_SITE&filter[externalId]=$ids" +
                    "&page[limit]=$PAGE_LIMIT&include=item"
            ) ?: continue

            // `include=item` inlines the anime, but the mapping's own `item` relationship
            // carries links only, with no `data` block to join on. The two arrays are
            // positionally aligned instead, so index is the only available pairing.
            val rows = doc.dataList()
            val items = doc.included.orEmpty()
            if (rows.size != items.size) continue

            for (i in rows.indices) {
                val externalId = rows[i].mappingAttributes()?.externalId
                    ?.toIntOrNull() ?: continue
                val animeId = items[i].id ?: continue
                out[externalId] = animeId
            }
        }
        return out
    }

    /** The franchise this anime belongs to. */
    private suspend fun franchiseIdOf(animeId: String): String? {
        franchiseIdCache[animeId]?.let { return it }
        val installment = get("/anime/$animeId/relationships/installments")
            ?.dataOne()?.id ?: return null
        return get("/installments/$installment/relationships/franchise")
            ?.dataOne()?.id
            ?.also { franchiseIdCache[animeId] = it }
    }

    /**
     * Every installment in a franchise.
     *
     * The listing is in id order, which is not story order, so the result is only ever used
     * as a set to be ordered later.
     */
    private suspend fun installmentsOf(franchiseId: String): List<String> {
        installmentsCache[franchiseId]?.let { return it }
        val doc = get("/franchises/$franchiseId/relationships/installments") ?: return emptyList()
        return doc.dataList().mapNotNull { it.id }
            .also { installmentsCache[franchiseId] = it }
    }

    /** The anime behind an installment. Null for installments with no media. */
    private suspend fun mediaIdOf(installmentId: String): String? {
        mediaIdCache[installmentId]?.let { return it }
        return get("/installments/$installmentId/relationships/media")?.dataOne()?.id
            ?.also { mediaIdCache[installmentId] = it }
    }

    /**
     * Anime resources for a batch of Kitsu ids.
     *
     * Returns whole resources rather than bare attributes because callers still need each
     * anime's id, to pair it back to its installment.
     *
     * `include=mappings` rides along on this request rather than costing one mapping lookup per
     * entry: Kitsu already sends the relationship, and the included mapping rows carry the
     * AniList id that each entry needs to be openable. See [anilistIdOf].
     */
    private suspend fun animeByIds(ids: List<String>): List<KitsuResource> {
        val missing = ids.distinct().filter { !animeCache.containsKey(it) }
        for (batch in missing.chunked(PAGE_LIMIT)) {
            val doc = get(
                "/anime?filter[id]=${batch.joinToString(",")}&page[limit]=$PAGE_LIMIT" +
                    "&include=mappings"
            ) ?: continue
            val mappingsById = (doc.included.orEmpty())
                .associateBy { it.id }
                .mapNotNull { (id, resource) ->
                    val anilistId = resource.mappingAttributes()
                        ?.takeIf { it.externalSite == ANILIST_SITE }
                        ?.externalId?.toIntOrNull()
                    anilistId?.let { id to it }
                }
                .toMap()
            for (anime in doc.dataList()) {
                val id = anime.id ?: continue
                animeCache[id] = anime
                // Only ids this anime's own relationship points at are taken, because a batch
                // inlines the mappings of every anime in it and they are otherwise unlabelled.
                anime.relationshipIds("mappings").forEach { mappingId ->
                    mappingsById[mappingId]?.let { anilistIdCache[id] = it }
                }
            }
        }
        // Read back through the cache so a partially failed batch still contributes the
        // entries that did resolve.
        return ids.distinct().mapNotNull { animeCache[it] }
    }

    /**
     * The AniList id of a Kitsu anime, or null when Kitsu carries no mapping for it.
     *
     * Null is a real outcome, not a gap to retry: a few Kitsu titles carry no AniList
     * mapping at all, and those entries have nothing to open. Popular titles all map, so this
     * is rare, but it is not zero.
     */
    private fun anilistIdOf(kitsuId: String): Int? = anilistIdCache[kitsuId]

    /**
     * This resource's id, for pairing an anime back to its installment.
     *
     * Empty rather than null when the server omits it: every use either looks the id up in a
     * cache or compares it for equality, and a blank id misses both, which is the same
     * outcome as having no id at all.
     */
    private fun KitsuResource.id() = id.orEmpty()

    private fun KitsuResource.toEntry(anilistId: Int?) = animeAttributes()?.toEntry(anilistId)

    private fun KitsuAnimeAttributes.displayTitle() =
        titles?.en?.takeIf { title -> title.isNotBlank() }
            ?: canonicalTitle?.takeIf { title -> title.isNotBlank() }

    /**
     * This anime as an entry.
     *
     * [anilistId] is passed in rather than read from the attributes because Kitsu carries it on
     * the anime's mapping relationship, not on the anime; see [anilistIdOf].
     */
    private fun KitsuAnimeAttributes.toEntry(anilistId: Int?): FranchiseEntry? {
        val title = displayTitle() ?: return null
        val year = startDate?.take(4)?.toIntOrNull() ?: seasonYear
        return FranchiseEntry(
            year = year?.toString().orEmpty(),
            sortYear = year,
            posterUrl = posterImage?.medium ?: posterImage?.small ?: posterImage?.large,
            title = title,
            synopsis = synopsis ?: description,
            anilistId = anilistId,
        )
    }

    /**
     * Every category Kitsu carries, in title order.
     *
     * `/categories` is paginated at [PAGE_LIMIT] like every other Kitsu route, and the
     * catalogue is 218 rows, so this walks all eleven pages rather than stopping at the
     * first one. `include=parent` inlines each row's parent resource, so the parent list
     * can be resolved from that same response instead of one extra request per category.
     *
     * Only ids, slugs and titles that are actually present are kept: a row missing any of
     * them cannot be filtered on, so it is dropped rather than carried as a broken chip.
     */
    suspend fun allCategories(): List<KitsuCategory> {
        categoryCache?.let { return it }
        val out = LinkedHashMap<String, KitsuCategory>()
        var offset = 0
        var complete = false
        while (true) {
            val doc = get(
                "/categories?page[limit]=$PAGE_LIMIT&page[offset]=$offset" +
                    "&sort=title&include=parent"
            ) ?: break
            val rows = doc.dataList()
            if (rows.isEmpty()) {
                complete = true
                break
            }
            val parentsById = doc.included.orEmpty()
                .associate { resource -> resource.id to resource.categoryAttributes()?.title }
            for (resource in rows) {
                val attrs = resource.categoryAttributes() ?: continue
                val id = resource.id ?: continue
                val title = attrs.title?.takeIf { it.isNotBlank() } ?: continue
                val slug = attrs.slug?.takeIf { it.isNotBlank() } ?: continue
                val parentId = resource.relationshipIds("parent").firstOrNull()
                out[id] = KitsuCategory(
                    id = id,
                    title = title,
                    slug = slug,
                    description = attrs.description,
                    nsfw = attrs.nsfw,
                    childCount = attrs.childCount,
                    mediaCount = attrs.totalMediaCount,
                    parentId = parentId,
                    parentTitle = parentId?.let { parentsById[it] },
                )
            }
            offset += rows.size
            val total = doc.meta?.count ?: 0
            if (rows.size < PAGE_LIMIT || (total > 0 && offset >= total)) {
                complete = true
                break
            }
        }
        val categories = out.values.toList()
        // Never present a partial catalogue as complete: a failed page must be retried on the
        // next attempt, otherwise categories beyond the failed offset silently disappear.
        if (!complete) {
            Logger.log("Kitsu: category catalogue fetch incomplete at offset=$offset; collected=${categories.size}")
            return emptyList()
        }
        if (categories.isNotEmpty()) categoryCache = categories
        return categories
    }

    /**
     * Poster artwork from an anime that is actually listed inside this Kitsu category.
     *
     * Unlike the general AniList discovery pool, this never borrows an unrelated popular
     * title just because its tags look similar. Mapping to AniList is not required for artwork:
     * the category can still have a usable Kitsu poster even when its anime lacks an AniList
     * mapping. Explicitly NSFW-tagged artwork remains filtered.
     */
    suspend fun categoryThumbnail(slug: String): String? {
        if (slug.isBlank()) return null
        // Choose from this category's own result page on every Discovery visit instead of
        // pinning the first poster forever. Random selection prevents the top result from
        // dominating every category card.
        val doc = get(
            "/anime?filter[categories]=$slug&sort=-averageRating&page[limit]=$PAGE_LIMIT" +
                "&page[offset]=0"
        ) ?: return null
        val posters = doc.dataList().mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            if (attrs.nsfw) return@mapNotNull null
            (attrs.posterImage?.large
                ?: attrs.posterImage?.medium
                ?: attrs.posterImage?.original
                ?: attrs.posterImage?.small)?.takeIf { it.isNotBlank() }
        }.distinct()
        return posters.randomOrNull()
    }

    /**
     * Anime in one category, most highly rated first.
     *
     * `filter[categories]` takes the category *slug*, which is the value Kitsu's own
     * category rows carry and the one stable across a category rename. `include=mappings`
     * inlines each anime's AniList mapping, which is the only way to open an entry: the
     * Sanin detail screen is keyed by AniList id and Kitsu ids do not line up with it (see
     * the class comment). An entry with no AniList mapping has nothing to open, so it is
     * skipped rather than shown as a dead card. Explicitly NSFW-tagged titles remain filtered.
     */
    suspend fun animeByCategory(slug: String, page: Int = 1): CategoryPage {
        val offset = ((page - 1).coerceAtLeast(0)) * PAGE_LIMIT
        val doc = get(
            "/anime?filter[categories]=$slug&sort=-averageRating" +
                "&page[limit]=$PAGE_LIMIT&page[offset]=$offset&include=mappings"
        ) ?: return CategoryPage(emptyList(), hasMore = false, failed = true)
        val rows = doc.dataList()
        val mappingsById = doc.included.orEmpty().associateBy { it.id }
        val media = rows.mapNotNull { resource ->
            val attrs = resource.animeAttributes() ?: return@mapNotNull null
            if (attrs.nsfw) return@mapNotNull null
            val anilistId = resource.relationshipIds("mappings").firstNotNullOfOrNull { mappingId ->
                mappingsById[mappingId]?.mappingAttributes()
                    ?.takeIf { it.externalSite == ANILIST_SITE }
                    ?.externalId?.toIntOrNull()
            } ?: return@mapNotNull null
            resource.toMedia(anilistId, attrs)
        }
        // `meta.count` is the size of the whole filtered listing, so the walk is done once the
        // rows consumed so far cover it. It is absent on the relationship routes, so a short
        // page is the fallback signal.
        val consumed = offset + rows.size
        val total = doc.meta?.count ?: 0
        val hasMore = if (total > 0) consumed < total else rows.size >= PAGE_LIMIT
        return CategoryPage(media = media, hasMore = hasMore)
    }

    /** A category listing entry as a Discovery card. Null when it has no usable title. */
    private fun KitsuResource.toMedia(anilistId: Int, attrs: KitsuAnimeAttributes): Media? {
        val title = attrs.displayTitle() ?: return null
        return Media(
            id = anilistId,
            name = title,
            nameRomaji = attrs.titles?.enJp?.takeIf { it.isNotBlank() } ?: title,
            userPreferredName = title,
            cover = attrs.posterImage?.large
                ?: attrs.posterImage?.medium
                ?: attrs.posterImage?.original,
            banner = attrs.coverImage?.large ?: attrs.coverImage?.original,
            isAdult = attrs.nsfw,
            description = attrs.synopsis,
            format = attrs.subtype?.uppercase(Locale.ROOT)
                ?: attrs.showType?.uppercase(Locale.ROOT),
            startDate = attrs.startDate?.take(4)?.toIntOrNull()?.let { FuzzyDate(year = it) },
            anime = Anime(
                totalEpisodes = attrs.episodeCount,
                season = attrs.season,
                seasonYear = attrs.seasonYear,
            ),
            idKitsu = id,
        )
    }

    /** One JSON:API GET. Returns null on any failure rather than throwing. */
    private suspend fun get(path: String): KitsuDocument? = tryWithSuspend(snackbar = false) {
        val request = Request.Builder()
            .url(BASE + path)
            .get()
            .addHeader("Accept", "application/vnd.api+json")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.log("Kitsu HTTP ${response.code} for $path: ${response.message}")
                null
            } else {
                val body = response.body?.string()
                if (body == null) {
                    Logger.log("Kitsu empty body for $path")
                    null
                } else {
                    val decoded = kitsuJson.decodeFromString<KitsuDocument>(body)
                    Logger.log("Kitsu ${response.code} $path -> ${decoded.dataList().size} resources, ${body.length} bytes")
                    decoded
                }
            }
        }
    }
}
