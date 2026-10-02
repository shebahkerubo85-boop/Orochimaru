package ani.sanin.connections.kitsu

import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
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
        val kitsuId = kitsuAnimeId(anilistId) ?: return null

        // Any entry of the franchise identifies the franchise, so the installment chain is
        // the same for all of them.
        val franchiseId = franchiseIdOf(kitsuId) ?: return null
        val allInstallments = installmentsOf(franchiseId)
        if (allInstallments.isEmpty()) return null

        val installmentIds = allInstallments.take(MAX_ENTRIES)
        // Pair each installment with its anime before losing track of which is which:
        // ordering needs the installment id, the card needs the anime id.
        val pairs = installmentIds.mapNotNull { installment ->
            mediaIdOf(installment)?.let { installment to it }
        }
        if (pairs.isEmpty()) return null

        val positions = curatedPositions(installmentIds)
        val animes = animeByIds(pairs.map { it.second }).ifEmpty { return null }

        val entries = animes
            .mapNotNull { anime ->
                val mapped = anime.toEntry() ?: return@mapNotNull null
                // Media ids are unique per anime, so one position per entry is enough.
                val position = pairs.firstOrNull { it.second == anime.id() }?.first
                    ?.let { positions[it] }
                // The seed's own AniList id is already known and is the one the sorter can match
                // exactly; the other entries are matched by title instead, since resolving each
                // of those back to AniList would cost a request per entry.
                val entry =
                    if (anime.id() == kitsuId) mapped.copy(anilistId = anilistId) else mapped
                entry to position
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

        return Franchise(
            // The franchise's own title, not the seed's: a Shippuden seed still yields "Naruto".
            name = franchiseName(franchiseId)
                ?: animes.firstNotNullOfOrNull { it.animeAttributes()?.displayTitle() }
                ?: return null,
            // Per spec the banner is the latest entry's artwork.
            bannerUrl = entries.last().posterUrl,
            entries = entries,
        )
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
 */
    private suspend fun animeByIds(ids: List<String>): List<KitsuResource> {
        val missing = ids.distinct().filter { !animeCache.containsKey(it) }
        for (batch in missing.chunked(PAGE_LIMIT)) {
            val doc = get("/anime?filter[id]=${batch.joinToString(",")}&page[limit]=$PAGE_LIMIT")
                ?: continue
            for (anime in doc.dataList()) {
                val id = anime.id ?: continue
                animeCache[id] = anime
            }
        }
        // Read back through the cache so a partially failed batch still contributes the
        // entries that did resolve.
        return ids.distinct().mapNotNull { animeCache[it] }
    }

    /** This resource's id, for pairing an anime back to its installment. */
    private fun KitsuResource.id() = id

    private fun KitsuResource.toEntry() = animeAttributes()?.toEntry()

    private fun KitsuAnimeAttributes.displayTitle() =
        titles?.en?.takeIf { title -> title.isNotBlank() }
            ?: canonicalTitle?.takeIf { title -> title.isNotBlank() }

    private fun KitsuAnimeAttributes.toEntry(): FranchiseEntry? {
        val title = displayTitle() ?: return null
        val year = startDate?.take(4)?.toIntOrNull() ?: seasonYear
        return FranchiseEntry(
            year = year?.toString().orEmpty(),
            sortYear = year,
            posterUrl = posterImage?.medium ?: posterImage?.small ?: posterImage?.large,
            title = title,
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
                null
            } else {
                response.body?.string()?.let { kitsuJson.decodeFromString<KitsuDocument>(it) }
            }
        }
    }
}
