package ani.sanin.home

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.sanin.connections.anilist.Anilist
import ani.sanin.media.anime.Anime
import ani.sanin.media.Media
import ani.sanin.media.Selected
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.DynamicAnimeParser
import ani.sanin.parsers.NativeAnimeParser
import ani.sanin.parsers.ShowResponse
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import ani.sanin.Lazier
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One card in a catalogue-driven Anime home section.
 *
 * [media] is set when the item already carries an AniList title (native providers
 * resolve through AniList), so a click can open the normal detail screen without
 * another lookup. Extension items leave it null and resolve lazily on click.
 */
data class CatalogueItem(
    val response: ShowResponse,
    val media: Media? = null,
)

data class CatalogueRow(val title: String, val items: List<CatalogueItem>)

/**
 * Builds the Anime home directly from the catalogue source picked in the tracker
 * sheet. Tracker ids ("anilist"/"mal"/blank) return null so the caller keeps the
 * existing AniList/MAL home.
 */
object AnimeCatalogueHome {

    private const val PER_SECTION = 20
    private val SECTION_ORDER = listOf(
        "Ongoing",
        "Latest Episodes",
        "Trending",
        "Popular",
        "Upcoming",
        "Recommended",
    )

    /**
     * True when the saved id names a usable extension/native source rather than a
     * tracker. A native provider that is no longer enabled no longer counts, so the
     * home falls back to the tracker page instead of showing a source the watch tab
     * cannot open.
     */
    fun isCatalogueSource(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (id.equals("anilist", true) || id.equals("mal", true)) return false
        if (id.startsWith("native:", true)) {
            val saveName = id.substringAfter(':')
            return AnimeSources.nativeParsers.any { it.saveName == saveName }
        }
        return true
    }

    fun savedSourceId(): String = PrefManager.getVal(PrefName.AnimeCatalogueSource)

    /**
     * Loads every section for [sourceId], preserving the fixed display order.
     * Empty/failed sections are dropped rather than rendered blank.
     */
    suspend fun loadRows(sourceId: String): List<CatalogueRow> {
        if (!isCatalogueSource(sourceId)) return emptyList()
        if (sourceId.startsWith("ext:", true)) {
            val name = sourceId.substringAfter(':')
            val parser = resolveExtension(name)
            if (parser == null) {
                Logger.log("Catalogue: extension '$name' not found")
                return emptyList()
            }
            return ordered(loadExtensionRows(parser))
        }
        if (sourceId.startsWith("native:", true)) {
            val saveName = sourceId.substringAfter(':')
            val parser = AnimeSources.allNativeParsers.firstOrNull { it.saveName == saveName }
            if (parser == null) {
                Logger.log("Catalogue: native provider '$saveName' not found")
                return emptyList()
            }
            return ordered(loadNativeRows(parser))
        }
        return emptyList()
    }

    private fun ordered(rows: List<CatalogueRow>): List<CatalogueRow> {
        val byTitle = rows.filter { it.items.isNotEmpty() }.associateBy { it.title }
        return SECTION_ORDER.mapNotNull { byTitle[it] }.ifEmpty { rows.filter { it.items.isNotEmpty() } }
    }

    private fun resolveExtension(name: String): DynamicAnimeParser? {
        val parser = runCatching {
            AnimeSources.list.firstOrNull { it.name == name }?.get?.value
        }.getOrNull()
        return parser as? DynamicAnimeParser
    }

    private fun catalogueSourceOf(parser: DynamicAnimeParser): AnimeCatalogueSource? {
        val sources = runCatching { parser.extension.sources }.getOrNull() ?: return null
        return sources.getOrNull(parser.sourceLanguage) as? AnimeCatalogueSource
            ?: sources.filterIsInstance<AnimeCatalogueSource>().firstOrNull()
    }

    // ---------------------------------------------------------------- extensions

    private suspend fun loadExtensionRows(parser: DynamicAnimeParser): List<CatalogueRow> =
        withContext(Dispatchers.IO) {
            val source = catalogueSourceOf(parser) ?: return@withContext emptyList()
            Logger.log("Catalogue: loading extension '${parser.name}'")

            val popular1 = page(source) { it.getPopularAnime(1) }
            val popular2 = page(source) { it.getPopularAnime(2) }
            val latest = if (source.supportsLatest) {
                page(source) { it.getLatestUpdates(1) }
            } else {
                emptyList()
            }

            val ongoingByStatus = (popular1 + latest)
                .filter { it.status == SAnime.ONGOING }
                .distinctBy { it.url }
            val ongoing = ongoingByStatus.ifEmpty {
                searchWithStatus(
                    source,
                    listOf("airing", "ongoing", "releasing", "emision", "en emisión", "en emision"),
                )
            }
            val upcoming = searchWithStatus(
                source,
                listOf("upcoming", "not yet aired", "unreleased", "próximamente", "proximamente", "not yet"),
            )

            val trending = popular2.ifEmpty { popular1.reversed() }
            val basePool = (popular1 + popular2 + latest + ongoing + upcoming)
                .distinctBy { it.url }
            val recommended = basePool.shuffled().take(PER_SECTION)

            listOf(
                CatalogueRow("Ongoing", ongoing.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Latest Episodes", latest.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Trending", trending.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Popular", popular1.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Upcoming", upcoming.take(PER_SECTION).map { it.toItem() }),
                CatalogueRow("Recommended", recommended.map { it.toItem() }),
            )
        }

    private suspend fun page(
        source: AnimeCatalogueSource,
        block: suspend (AnimeCatalogueSource) -> AnimesPage,
    ): List<SAnime> = runCatching { block(source).animes }.getOrElse {
        Logger.log("Catalogue: section fetch failed on '${source.name}': ${it.message}")
        emptyList()
    }

    /**
     * Runs an empty search with any status-shaped filter set to a matching value.
     * Extensions vary wildly, so this both looks for a status filter by name and
     * matches the keywords against the filter's own values.
     */
    private suspend fun searchWithStatus(
        source: AnimeCatalogueSource,
        keywords: List<String>,
    ): List<SAnime> {
        val filters = runCatching { source.getFilterList() }.getOrNull() ?: return emptyList()
        var applied = false
        for (filter in filters) {
            if (filter !is AnimeFilter.Select<*>) continue
            val filterName = filter.name.lowercase()
            if (!(filterName.contains("status") || filterName.contains("estado") || filterName.contains("state"))) continue
            val index = filter.values.indexOfFirst { value ->
                val text = value.toString().lowercase()
                keywords.any { text.contains(it) }
            }
            if (index >= 0) {
                filter.state = index
                applied = true
            }
        }
        if (!applied) return emptyList()
        return runCatching {
            source.getSearchAnime(1, "", AnimeFilterList(filters.toList())).animes
        }.getOrElse {
            Logger.log("Catalogue: status search failed on '${source.name}': ${it.message}")
            emptyList()
        }
    }

    private fun SAnime.toItem(): CatalogueItem = CatalogueItem(
        response = ShowResponse(
            name = title,
            link = url,
            coverUrl = thumbnail_url ?: "",
            sAnime = this,
        )
    )

    // ------------------------------------------------------------------- natives

    /**
     * Native providers are search-only, so their sections come from AniList lists
     * resolved through the provider's own search. Only titles the provider can
     * actually serve survive.
     */
    private suspend fun loadNativeRows(parser: NativeAnimeParser): List<CatalogueRow> =
        withContext(Dispatchers.IO) {
            Logger.log("Catalogue: loading native '${parser.name}'")
            val adultOnly = PrefManager.getVal<Boolean>(PrefName.AdultOnly)
            val lists = coroutineScope {
                val ongoing = async {
                    query(Anilist.sortBy[1], status = "RELEASING", adultOnly = adultOnly)
                }
                val latest = async {
                    query(Anilist.sortBy[3], status = "RELEASING", adultOnly = adultOnly)
                }
                val trending = async {
                    query(Anilist.sortBy[2], adultOnly = adultOnly)
                }
                val popular = async {
                    query(Anilist.sortBy[1], adultOnly = adultOnly)
                }
                val upcoming = async {
                    query(Anilist.sortBy[1], status = "NOT_YET_RELEASED", adultOnly = adultOnly)
                }
                val recommended = async {
                    query(Anilist.sortBy[1], page = 2, adultOnly = adultOnly)
                }
                listOf(
                    "Ongoing" to ongoing.await(),
                    "Latest Episodes" to latest.await(),
                    "Trending" to trending.await(),
                    "Popular" to popular.await(),
                    "Upcoming" to upcoming.await(),
                    "Recommended" to recommended.await(),
                )
            }

            lists.map { (title, media) ->
                CatalogueRow(title, resolveThrough(parser, media))
            }
        }

    private suspend fun query(
        sort: String,
        status: String? = null,
        page: Int? = null,
        adultOnly: Boolean,
    ): List<Media> = runCatching {
        Anilist.query.searchAniManga(
            type = "ANIME",
            page = page,
            perPage = PER_SECTION,
            sort = sort,
            status = status,
            adultOnly = adultOnly,
        )?.results ?: emptyList()
    }.getOrElse {
        Logger.log("Catalogue: AniList section failed: ${it.message}")
        emptyList()
    }

    private suspend fun resolveThrough(
        parser: NativeAnimeParser,
        media: List<Media>,
    ): List<CatalogueItem> = coroutineScope {
        val gate = Semaphore(3)
        media.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val name = item.mainName() ?: return@withPermit null
                    val match = runCatching { parser.search(name).firstOrNull() }.getOrNull()
                        ?: return@withPermit null
                    CatalogueItem(response = match, media = item)
                }
            }
        }.awaitAll().filterNotNull()
    }

    // ------------------------------------------------------------------- routing

    /**
     * Makes sure the chosen source is addressable from the watch tab's source
     * list. A native provider chosen as a catalogue source may not be enabled,
     * so it is enabled (and therefore listed) instead of silently falling back.
     */
    fun watchSourceIndex(sourceId: String): Int {
        val name = when {
            sourceId.startsWith("native:", true) ->
                AnimeSources.allNativeParsers
                    .firstOrNull { it.saveName == sourceId.substringAfter(':') }?.name
            sourceId.startsWith("ext:", true) -> sourceId.substringAfter(':')
            else -> null
        } ?: return -1

        val existing = AnimeSources.list.indexOfFirst { it.name == name }
        if (existing >= 0) return existing

        val parser = AnimeSources.allNativeParsers.firstOrNull { it.name == name } ?: return -1
        // A provider picked as the catalogue source has to be watchable, so enable it. That
        // also keeps the index stable: a later list rebuild would otherwise drop a
        // session-only entry and silently point this index at another source.
        val enabled = PrefManager.getVal<Set<String>>(PrefName.EnabledProviders).toMutableSet()
        if (enabled.add(parser.saveName)) {
            PrefManager.setVal(PrefName.EnabledProviders, enabled)
            AnimeSources.rebuildNativeParsers()
            val rebuilt = AnimeSources.list.indexOfFirst { it.name == name }
            if (rebuilt >= 0) return rebuilt
        }
        val list = AnimeSources.list.toMutableList()
        val localIndex = list.indexOfFirst { it.name == "Local" }
        val insertion = if (localIndex >= 0) localIndex else list.size
        list.add(insertion, Lazier<ani.sanin.parsers.BaseParser>({ parser }, parser.name))
        AnimeSources.list = list
        return insertion
    }

    /** Media a section card binds: the resolved AniList title, or a synthetic stand-in. */
    fun displayMedia(sourceId: String, item: CatalogueItem): Media =
        item.media ?: item.response.toSyntheticMedia(sourceId)

    /** Opens the normal anime detail flow with the catalogue source pre-selected. */
    fun openItem(
        activity: AppCompatActivity,
        sourceId: String,
        item: CatalogueItem,
        onFailed: () -> Unit = {},
        onResolved: (Media) -> Unit,
    ) {
        val index = watchSourceIndex(sourceId)
        if (index < 0) {
            Logger.log("Catalogue: no watch source for '$sourceId'")
            onFailed()
            return
        }
        activity.lifecycleScope.launch {
            val media = item.media ?: withContext(Dispatchers.IO) {
                runCatching {
                    Anilist.query.searchAniManga(
                        type = "ANIME",
                        perPage = 1,
                        search = item.response.name,
                        adultOnly = PrefManager.getVal(PrefName.AdultOnly),
                    )?.results?.firstOrNull()
                }.getOrNull()
            } ?: withContext(Dispatchers.IO) { item.response.toSyntheticMedia(sourceId) }

            withContext(Dispatchers.IO) {
                runCatching {
                    AnimeSources.saveResponse(index, media.id, item.response)
                    val selected = Selected(
                        sourceIndex = index,
                        preferDub = PrefManager.getVal(PrefName.PreferDub),
                    )
                    media.selected = selected
                    PrefManager.setCustomVal("Selected-${media.id}", selected)
                }.onFailure { Logger.log("Catalogue: failed saving source for '${media.name}': ${it.message}") }
            }
            onResolved(media)
        }
    }

    /** Fallback Media when AniList knows nothing about the title. */
    private fun ShowResponse.toSyntheticMedia(sourceId: String): Media {
        val id = syntheticId(sourceId, link)
        return Media(
            anime = Anime(totalEpisodes = total),
            id = id,
            name = name,
            nameRomaji = name,
            userPreferredName = name,
            isAdult = false,
            cover = coverUrl.url.ifBlank { null },
            format = "TV",
        )
    }

    private fun syntheticId(sourceId: String, link: String): Int {
        val hash = "$sourceId|$link".hashCode()
        return if (hash == Int.MIN_VALUE) -1 else -abs(hash) - 1
    }
}
