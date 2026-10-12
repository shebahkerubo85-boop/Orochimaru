package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.kitsu.Kitsu
import ani.sanin.connections.kitsu.KitsuCategory
import ani.sanin.databinding.FragmentDiscoverBinding
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.SearchActivity
import ani.sanin.home.SearchBottomSheet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import ani.sanin.cloudstream.TmdbCards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

class DiscoverViewModel : ViewModel() {
    private val _categories = MutableStateFlow<List<KitsuCategory>>(emptyList())
    val categories: StateFlow<List<KitsuCategory>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow<KitsuCategory?>(null)
    val selectedCategory: StateFlow<KitsuCategory?> = _selectedCategory.asStateFlow()

    private val _selectedSeason = MutableStateFlow(DiscoverSeason.current())
    val selectedSeason: StateFlow<DiscoverSeason> = _selectedSeason.asStateFlow()

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private var pageJob: Job? = null
    private var categoriesJob: Job? = null
    private var loaded = false

    /**
     * Monotonic id for the latest query. Only a result carrying the current id may publish;
     * see [refresh].
     */
    private val fetchGeneration = AtomicInteger(0)

    /**
     * Kitsu categories that carry no anime.
     *
     * Every other empty category is a grouping node with a zero `totalMediaCount` and is
     * dropped by [loadCategories]. Ahegao is the exception: Kitsu counts 252 media against it
     * but every one of them is manga, so it is a real category that filters to an empty anime
     * grid. It is the only such row in the catalogue, so it is excluded by its stable slug
     * rather than probing all 218 categories with 218 extra requests.
     */
    private val nonAnimeCategorySlugs = setOf("ahegao")

    /**
     * Loads Kitsu's category catalogue once.
     *
     * The chips are built from this, so it runs alongside the first grid fetch rather than
     * before it: the grid has a default "All" query to show and should not wait on the
     * category list. Cached in the ViewModel, so a rotation does not refetch it.
     */
    fun loadCategories() {
        if (_categories.value.isNotEmpty() || categoriesJob?.isActive == true) return
        categoriesJob = viewModelScope.launch(Dispatchers.IO) {
            // `totalMediaCount` is Kitsu's count across every medium, so a zero means the row
            // is a grouping node (Elements, Setting, Themes, ...) that no anime is tagged with
            // and which would only ever filter to an empty grid. nsfw rows are dropped to match
            // the adult filter the AniList-backed "All" browse already applies, and the one
            // media-bearing but anime-less row is dropped too; see [nonAnimeCategorySlugs].
            val categories = Kitsu.allCategories()
                .filterNot { it.nsfw || it.mediaCount <= 0 || it.slug in nonAnimeCategorySlugs }
            if (categories.isNotEmpty()) _categories.value = categories
        }
    }

    /** Runs the first load once per ViewModel, so a returning view does not refetch. */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun selectCategory(category: KitsuCategory?) {
        _selectedCategory.value = category
        refresh()
    }

    fun selectSeason(season: DiscoverSeason) {
        _selectedSeason.value = season
        // Season belongs to the AniList "All" browse; choosing one leaves the Kitsu category
        // so the two filters never fight over which source feeds the grid.
        _selectedCategory.value = null
        refresh()
    }

    /** Re-runs the current selection from its first page; used by the error-state retry. */
    fun retry() = refresh()

    /** Starts a fresh query for the current selection and clears all previous page state. */
    fun refresh() {
        // Cancelling an in-flight OkHttp call does not interrupt it (`get` is a blocking
        // request and tryWithSuspend swallows cancellation), so an older request can still
        // finish after a newer one and would append stale cards. Each query carries a
        // generation and only the newest is allowed to publish.
        val generation = fetchGeneration.incrementAndGet()
        val category = _selectedCategory.value
        val season = _selectedSeason.value
        pageJob?.cancel()
        _uiState.value = DiscoverUiState(queryId = generation, isInitialLoading = true)
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            if (category == null) {
                loadAnilist(generation, season)
            } else {
                appendCategoryPages(generation, category, baseItems = emptyList(), basePage = 0)
            }
        }
    }

    /** Pulls the next Kitsu page when the grid approaches its end. */
    fun loadMore() {
        val state = _uiState.value
        if (state.queryId != fetchGeneration.get()) return
        if (state.isInitialLoading || state.isLoadingMore || !state.hasMore ||
            state.error || state.loadMoreError
        ) {
            return
        }
        val category = _selectedCategory.value ?: return
        _uiState.value = state.copy(isLoadingMore = true, loadMoreError = false)
        pageJob = viewModelScope.launch(Dispatchers.IO) {
            appendCategoryPages(state.queryId, category, baseItems = state.items, basePage = state.page)
        }
    }

    /** Clears a failed "load more" and retries the same page. */
    fun retryLoadMore() {
        val state = _uiState.value
        if (state.isLoadingMore) return
        _uiState.value = state.copy(loadMoreError = false)
        loadMore()
    }

    private suspend fun loadAnilist(generation: Int, season: DiscoverSeason) {
        // discoverAnime answers with null on failure, so a null here is the error case.
        val list = runCatching {
            Anilist.query.discoverAnime(
                season = season.apiValue,
                seasonYear = season.year,
            )
        }.getOrNull()
        if (generation != fetchGeneration.get()) return
        _uiState.value = if (list == null) {
            DiscoverUiState(queryId = generation, error = true)
        } else {
            DiscoverUiState(queryId = generation, items = list.distinctBy { it.id })
        }
    }

    /**
     * Appends consecutive Kitsu pages to [baseItems] until a full batch of new cards has
     * arrived, the listing is exhausted, or the per-trigger cap is hit.
     *
     * A page can lose a third of its rows to entries with no AniList mapping, so a single page
     * often leaves the grid short; the loop keeps going while it is underfilled so the user is
     * not left with a half-empty screen and nothing to scroll. Cards are deduplicated by
     * AniList id because that is the key the detail screen opens on.
     */
    private suspend fun appendCategoryPages(
        generation: Int,
        category: KitsuCategory,
        baseItems: List<Media>,
        basePage: Int,
    ) {
        val items = ArrayList(baseItems)
        val seenIds = HashSet<Int>(baseItems.size * 2)
        baseItems.forEach { seenIds.add(it.id) }
        var page = basePage
        var fetched = 0
        while (generation == fetchGeneration.get() && fetched < MAX_PAGES_PER_TRIGGER) {
            val result = Kitsu.animeByCategory(category.slug, page + 1)
            if (generation != fetchGeneration.get()) return
            if (result.failed) {
                // Keep whatever arrived; a failure with nothing to show is the full (centred)
                // error state, otherwise it is a retryable footer under the grid.
                val nothingYet = items.isEmpty()
                _uiState.value = DiscoverUiState(
                    queryId = generation,
                    items = items,
                    page = page,
                    hasMore = !nothingYet,
                    error = nothingYet,
                    loadMoreError = !nothingYet,
                )
                return
            }
            page += 1
            fetched += 1
            var added = 0
            for (media in result.media) {
                if (seenIds.add(media.id)) {
                    items.add(media)
                    added++
                }
            }
            if (!result.hasMore) {
                _uiState.value = DiscoverUiState(
                    queryId = generation,
                    items = items,
                    page = page,
                    hasMore = false,
                )
                return
            }
            if (added >= PAGE_FILL_TARGET) break
        }
        _uiState.value = DiscoverUiState(
            queryId = generation,
            items = items,
            page = page,
            hasMore = true,
        )
    }

    private companion object {
        /** New cards wanted per load before paging may pause. */
        const val PAGE_FILL_TARGET = 20

        /** Upper bound on requests a single load may spend filling the grid. */
        const val MAX_PAGES_PER_TRIGGER = 5
    }
}

/** The Discovery grid's single source of truth, so loading, empty and error never overlap. */
data class DiscoverUiState(
    val queryId: Int = 0,
    val items: List<Media> = emptyList(),
    val page: Int = 0,
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
)

data class DiscoverSeason(
    val label: String,
    val apiValue: String,
    val year: Int,
) {
    companion object {
        private fun seasonFor(month: Int): String = when (month) {
            in 1..3 -> "WINTER"
            in 4..6 -> "SPRING"
            in 7..9 -> "SUMMER"
            else -> "FALL"
        }

        fun current(): DiscoverSeason {
            val cal = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) + 1
            val year = cal.get(Calendar.YEAR)
            val season = seasonFor(month)
            return DiscoverSeason(
                label = "${season.lowercase().replaceFirstChar { it.uppercase() }} $year",
                apiValue = season,
                year = year,
            )
        }

        fun seasons(): List<DiscoverSeason> {
            val cal = Calendar.getInstance()
            val curMonth = cal.get(Calendar.MONTH) + 1
            val curYear = cal.get(Calendar.YEAR)
            val result = mutableListOf<DiscoverSeason>()
            val nextIdx = SEASON_ORDER.indexOf(seasonFor(curMonth)) + 1
            val nextSeason = SEASON_ORDER[nextIdx % 4]
            val nextYear = if (nextIdx >= 4) curYear + 1 else curYear
            result.add(DiscoverSeason("${nextSeason.lowercase().replaceFirstChar { it.uppercase() }} $nextYear", nextSeason, nextYear))
            var idx = SEASON_ORDER.indexOf(seasonFor(curMonth))
            var yr = curYear
            repeat(5) {
                result.add(DiscoverSeason("${SEASON_ORDER[idx].lowercase().replaceFirstChar { it.uppercase() }} $yr", SEASON_ORDER[idx], yr))
                idx = (idx - 1 + 4) % 4
                if (idx == 3) yr--
            }
            return result
        }

        private val SEASON_ORDER = listOf("WINTER", "SPRING", "SUMMER", "FALL")
    }
}

class DiscoveryFragment : Fragment() {
    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    private val discoverViewModel: DiscoverViewModel by activityViewModels()
    private var kitsuPicks: List<ani.sanin.connections.kitsu.KitsuDiscoveryItem> = emptyList()
    private var kitsuLoaded = false
    private var tmdbAnimationPicks: List<ani.sanin.connections.tmdb.TmdbMedia> = emptyList()
    private var tmdbAnimationLoaded = false
    private var kitsuCategories: List<ani.sanin.connections.kitsu.KitsuCategory> = emptyList()
    private var categoryArtwork: Map<String, String?> = emptyMap()
    private var categoriesLoaded = false
    private var trailerLoaded = false
    private var trailerItems: List<DiscoveryTrailer> = emptyList()

    private data class DiscoveryTrailer(
        val title: String,
        val key: String,
        val posterUrl: String?,
    )

    /** Backing list of the grid adapter; appended to as pages arrive. */
    private lateinit var gridItems: MutableList<Media>
    private lateinit var gridAdapter: MediaAdaptor

    /** QueryId currently rendered, so a new query rebuilds instead of appending. */
    private var renderedQueryId = -1

    private var isTv = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var visibleCuratedCategoryCount = 20
    private var renderedCuratedCategoryCount = 0
    private var categoryGridScrollListenerInstalled = false

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        isTv = isTvDevice(requireContext())
        setupSearchBar()
        setupAvatar()

        // Discovery is now the curated category landing page. The old genre/season chips,
        // AniList results grid, editorial shelves and trailer carousel are intentionally not
        // initialized here, so they cannot issue unrelated network requests or reappear.
        binding.genreChipGroup.visibility = View.GONE
        binding.seasonChipGroup.visibility = View.GONE
        binding.discoverRecyclerView.visibility = View.GONE
        binding.discoverProgressBar.visibility = View.GONE
        binding.discoverEmptyText.visibility = View.GONE
        binding.discoverErrorGroup.visibility = View.GONE
        binding.discoverLoadingMore.visibility = View.GONE
        binding.discoverLoadMoreErrorGroup.visibility = View.GONE
        binding.discoverTrailerCarousel.visibility = View.GONE
        binding.discoverEditorialShelves.visibility = View.GONE

        binding.discoverSearchBar.nextFocusDownId = R.id.discoverCategoryGrid
        binding.discoverAvatar.nextFocusDownId = R.id.discoverCategoryGrid
        binding.discoverAvatar.nextFocusLeftId = R.id.discoverSearchBar

        binding.discoverScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = binding.discoverScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            if (scrollY + binding.discoverScroll.height >= contentHeight - dp(500)) {
                revealNextCategoryBatch()
            }
        }
        renderCategoryGrid()
        if (isTv) {
            binding.discoverSearchBar.post {
                if (isAdded) binding.discoverSearchBar.requestFocus()
            }
        }
    }

    private fun revealNextCategoryBatch() {
        if (visibleCuratedCategoryCount >= CuratedDiscoveryCategories.all.size) return
        visibleCuratedCategoryCount = (visibleCuratedCategoryCount + 20)
            .coerceAtMost(CuratedDiscoveryCategories.all.size)
        renderCategoryGrid()
    }

    private fun loadKitsuEditorial() {
        if (kitsuLoaded) return
        kitsuLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            kitsuPicks = runCatching {
                ani.sanin.connections.kitsu.Kitsu.discoverEditorial(limit = 20)
            }.getOrDefault(emptyList())
            if (_binding != null) renderEditorialShelves(discoverViewModel.uiState.value.items)
        }
    }

    private fun loadTmdbAnimationEditorial() {
        if (tmdbAnimationLoaded) return
        tmdbAnimationLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            tmdbAnimationPicks = runCatching {
                val client = ani.sanin.connections.tmdb.Tmdb
                val films = client.discover(
                    mediaType = "movie", genres = "16", sort = "popularity.desc"
                )
                val series = client.discover(
                    mediaType = "tv", genres = "16", sort = "popularity.desc"
                )
                films.zip(series).flatMap { (film, show) -> listOf(film, show) }
                    .plus(if (films.size > series.size) films.drop(series.size) else emptyList())
                    .plus(if (series.size > films.size) series.drop(films.size) else emptyList())
                    .distinctBy { it.type to it.id }
                    .take(12)
            }.getOrDefault(emptyList())
            if (_binding != null) {
                renderEditorialShelves(discoverViewModel.uiState.value.items)
                loadTrailerCarousel()
            }
        }
    }

    private fun loadKitsuCategories() {
        // Kept as a no-network compatibility entry point for any older internal caller.
        renderCategoryGrid()
    }

    /**
     * Render the curated, stable category list in two columns. The cards are hardcoded and
     * paged locally (20 + 20 + remainder), so the landing page works even if Kitsu's category
     * catalogue is slow or omits an editorial category.
     */
    private fun renderCategoryGrid() {
        val host = _binding?.discoverCategoryGrid ?: return
        val context = host.context
        if (host.childCount == 0) {
            // A recreated view gets a fresh host even when the fragment retained the reveal
            // count after rotation. Rebuild from the start without resetting how many were shown.
            renderedCuratedCategoryCount = 0
            host.orientation = LinearLayout.VERTICAL
            host.setPadding(dp(4), dp(4), dp(4), dp(16))
            host.addView(TextView(context).apply {
                text = "FIND YOUR NEXT OBSESSION"
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(12), dp(16), dp(12), dp(12))
            })
        }

        // Remove only the "more" hint, not existing cards: focus and scroll position remain
        // stable on Android TV while each new batch is appended.
        host.findViewWithTag<View>("curated-more-footer")?.let { host.removeView(it) }
        val categories = CuratedDiscoveryCategories.all
        val shownCount = visibleCuratedCategoryCount.coerceAtMost(categories.size)
        if (shownCount > renderedCuratedCategoryCount) {
            val newCategories = categories.subList(renderedCuratedCategoryCount, shownCount)
            newCategories.chunked(2).forEachIndexed { rowIndex, rowCategories ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                rowCategories.forEach { category ->
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.TOP
                        isFocusable = true
                        isClickable = true
                        background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                        ).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        }
                        contentDescription = "${category.title}. ${category.description}"
                        setOnClickListener {
                            startActivity(
                                Intent(context, KitsuCategoryResultsActivity::class.java)
                                    .putExtra(KitsuCategoryResultsActivity.EXTRA_CATEGORY_SLUG, category.kitsuSlug)
                                    .putExtra(KitsuCategoryResultsActivity.EXTRA_CATEGORY_TITLE, category.title)
                            )
                        }
                    }
                    card.addView(TextView(context).apply {
                        text = category.title.uppercase(java.util.Locale.ROOT)
                        textSize = 14f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    })
                    card.addView(TextView(context).apply {
                        text = category.description
                        textSize = 12f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        alpha = 0.82f
                        setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                        setPadding(0, dp(6), 0, 0)
                    })
                    FocusEffectUtil.applyFocusListener(card)
                    row.addView(card)
                }
                if (rowCategories.size == 1) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    })
                }
                host.addView(row)
            }
            renderedCuratedCategoryCount = shownCount
        }

        if (shownCount < categories.size) {
            host.addView(TextView(context).apply {
                tag = "curated-more-footer"
                text = "KEEP SCROLLING TO DISCOVER MORE"
                textSize = 11f
                gravity = Gravity.CENTER
                alpha = 0.65f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
    }

    private fun loadTrailerCarousel() {
        if (trailerLoaded) {
            renderTrailerCarousel()
            return
        }
        trailerLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            val picks = tmdbAnimationPicks.take(12)
            val trailers = if (ani.sanin.connections.tmdb.Tmdb.apiKey.isBlank()) {
                emptyList()
            } else {
                picks.map { pick ->
                    async {
                        val detail = runCatching {
                            ani.sanin.connections.tmdb.Tmdb.detail(pick.type, pick.id)
                        }.getOrNull()
                        val video = detail?.videos?.results.orEmpty()
                            .filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
                            .sortedBy {
                                when (it.type?.lowercase()) {
                                    "trailer" -> 0
                                    "teaser" -> 1
                                    else -> 2
                                }
                            }.firstOrNull()
                        video?.let {
                            DiscoveryTrailer(
                                title = detail?.displayTitle?.takeIf { name -> name.isNotBlank() } ?: pick.displayTitle,
                                key = it.key.orEmpty(),
                                posterUrl = detail?.posterPath?.let { path -> "https://image.tmdb.org/t/p/w500$path" }
                                    ?: pick.posterPath?.let { path -> "https://image.tmdb.org/t/p/w500$path" }
                            )
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.key }.shuffled().take(10)
            }
            trailerItems = trailers
            if (_binding != null) renderTrailerCarousel()
        }
    }

    private fun renderTrailerCarousel() {
        val host = _binding?.discoverTrailerCarousel ?: return
        host.removeAllViews()
        val context = host.context
        host.addView(TextView(context).apply {
            text = "TRAILERS TO DROP INTO"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(12), dp(12), dp(8))
        })
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(2), dp(8), dp(10))
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (trailerItems.isEmpty()) {
            repeat(3) { index ->
                val placeholder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    layoutParams = LinearLayout.LayoutParams(dp(112), dp(178)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                placeholder.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
                    contentDescription = "Trailer artwork unavailable"
                })
                placeholder.addView(TextView(context).apply {
                    text = if (ani.sanin.connections.tmdb.Tmdb.apiKey.isBlank())
                        "Add TMDB API key" else "Trailer slot ${index + 1}"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, dp(8), 0, 0)
                })
                row.addView(placeholder)
            }
        } else {
            trailerItems.forEach { trailer ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(dp(116), dp(190)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener {
                        startActivity(Intent(context, KitsuTrailerPlayerActivity::class.java)
                            .putExtra(KitsuTrailerPlayerActivity.EXTRA_VIDEO_KEY, trailer.key)
                            .putExtra(KitsuTrailerPlayerActivity.EXTRA_TITLE, trailer.title))
                    }
                }
                val poster = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = trailer.title
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(145)
                    )
                }
                trailer.posterUrl?.let { poster.loadImage(it) }
                card.addView(poster)
                card.addView(TextView(context).apply {
                    text = trailer.title
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(dp(3), dp(5), dp(3), 0)
                })
                FocusEffectUtil.applyFocusListener(card)
                row.addView(card)
            }
        }
        scroll.addView(row)
        host.addView(scroll)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setupSearchBar() {
        binding.discoverSearchBar.hint = getString(R.string.search)
        val openSearch = {
            val ctx = requireContext()
            if (Anilist.token != null) {
                ContextCompat.startActivity(
                    ctx,
                    Intent(ctx, SearchActivity::class.java).putExtra("type", "ANIME"),
                    null
                )
            } else {
                SearchBottomSheet.newInstance().show(
                    (requireActivity() as AppCompatActivity).supportFragmentManager,
                    "search"
                )
            }
        }
        binding.discoverSearchText.setOnClickListener { openSearch() }
        // D-pad focus lands on the pill (TextInputLayout) — make the whole
        // pill behave like one button so OK/Enter opens search, not just touch.
        binding.discoverSearchBar.isClickable = true
        binding.discoverSearchBar.isFocusableInTouchMode = true
        binding.discoverSearchBar.setOnClickListener { openSearch() }
        FocusEffectUtil.applyFocusListener(binding.discoverSearchBar)
    }

    private fun setupAvatar() {
        val avatarUrl = ani.sanin.connections.anilist.Anilist.avatar
            ?: ani.sanin.connections.simkl.Simkl.avatar
        if (!avatarUrl.isNullOrBlank()) {
            binding.discoverAvatar.loadImage(avatarUrl)
        }
        binding.discoverAvatar.setOnClickListener {
            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(android.view.Gravity.END)) {
                    val popMethod = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                    popMethod.isAccessible = true
                    popMethod.invoke(act)
                    drawer.openDrawer(android.view.Gravity.END)
                }
            }
        }
        FocusEffectUtil.applyFocusListener(binding.discoverAvatar)
    }

    private fun setupGenreChips() {
        renderCategoryChips(emptyList())
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.categories.collect { renderCategoryChips(it) }
        }
        discoverViewModel.loadCategories()
    }

    /**
     * Rebuilds the category row.
     *
     * On a phone that is a fixed "All" chip followed by every Kitsu category. On a TV the full
     * row would be a long sideways scroll for a d-pad, so it collapses to "All" plus a single
     * browse chip that opens a picker. The catalogue arrives asynchronously, so this runs first
     * with just "All" and again once it lands; the current selection is read on each pass so
     * the checked chip survives the rebuild rather than snapping back to the first one.
     */
    private fun renderCategoryChips(categories: List<KitsuCategory>) {
        if (!isAdded || _binding == null) return
        val group = binding.genreChipGroup
        val textColor = requireContext().getThemeColor(
            com.google.android.material.R.attr.colorOnSurface
        )
        val selected = discoverViewModel.selectedCategory.value
        group.removeAllViews()
        addCategoryChip(group, textColor, getString(R.string.all), selected == null) {
            applyCategorySelection(null)
        }
        if (isTv) {
            if (categories.isEmpty()) return
            // Opens the picker without toggling itself: the check reflects the actual selection
            // and is only rebuilt when one is made, so cancelling the dialog leaves it alone.
            val browse = Chip(group.context).apply {
                text = selected?.title ?: getString(R.string.categories)
                isCheckable = true
                isChecked = selected != null
                isFocusable = true
                setTextColor(textColor)
                setOnClickListener { showCategoryPicker() }
            }
            FocusEffectUtil.applyFocusListener(browse)
            group.addView(browse)
        } else {
            categories.forEach { category ->
                addCategoryChip(group, textColor, category.title, selected?.id == category.id) {
                    applyCategorySelection(category)
                }
            }
        }
    }

    /** One checkable category chip, styled and wired like the rest of the Discovery row. */
    private fun addCategoryChip(
        group: ChipGroup,
        textColor: Int,
        label: String,
        checked: Boolean,
        onSelect: () -> Unit,
    ) {
        val chip = Chip(group.context).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isFocusable = true
            setTextColor(textColor)
            setOnClickListener {
                group.clearCheck()
                isChecked = true
                onSelect()
            }
        }
        FocusEffectUtil.applyFocusListener(chip)
        group.addView(chip)
    }

    /** Applies a category choice and keeps the season row in sync: seasons only browse "All". */
    private fun applyCategorySelection(category: KitsuCategory?) {
        val hadCategory = discoverViewModel.selectedCategory.value != null
        discoverViewModel.selectCategory(category)
        setSeasonRowEnabled(category == null)
        // Re-checking in place (rather than re-inflating) keeps the season row's D-pad focus.
        if (category != null) {
            binding.seasonChipGroup.clearCheck()
        } else if (hadCategory) {
            checkSeasonChip(discoverViewModel.selectedSeason.value)
        }
        // TV shows one browse chip instead of the full row, so its label and check are the only
        // thing reflecting the choice; update them without rebuilding the chip under the focus.
        if (isTv) {
            (binding.genreChipGroup.getChildAt(1) as? Chip)?.apply {
                text = category?.title ?: getString(R.string.categories)
                isChecked = category != null
            }
        }
    }

    /**
     * Seasons belong to the AniList "All" browse, so a Kitsu category dims and disables the row
     * instead of leaving a lit chip that no longer applies to the grid.
     */
    private fun setSeasonRowEnabled(enabled: Boolean) {
        val group = binding.seasonChipGroup
        group.isEnabled = enabled
        group.alpha = if (enabled) 1f else 0.4f
        for (index in 0 until group.childCount) {
            group.getChildAt(index).isEnabled = enabled
        }
    }

    /** Re-checks the stored season chip after the row is re-enabled, without re-fetching. */
    private fun checkSeasonChip(season: DiscoverSeason) {
        val group = binding.seasonChipGroup
        group.clearCheck()
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? Chip ?: continue
            if (chip.text == season.label) {
                chip.isChecked = true
                break
            }
        }
    }

    /** TV-only picker: the full category list in a dialog so a d-pad is not stuck scrolling. */
    private fun showCategoryPicker() {
        if (!isAdded) return
        val categories = discoverViewModel.categories.value
        if (categories.isEmpty()) return
        val ctx = requireContext()
        val textColor = ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val pad = (ctx.resources.displayMetrics.density * 16).toInt()
        val group = ChipGroup(ctx).apply {
            isSingleSelection = true
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.categories)
            .setView(group)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val selected = discoverViewModel.selectedCategory.value
        addCategoryChip(group, textColor, getString(R.string.all), selected == null) {
            dialog.dismiss()
            applyCategorySelection(null)
        }
        categories.forEach { category ->
            addCategoryChip(group, textColor, category.title, selected?.id == category.id) {
                dialog.dismiss()
                applyCategorySelection(category)
            }
        }
        dialog.show()
    }

    private fun setupSeasonChips() {
        val onSurface = requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val seasons = DiscoverSeason.seasons()
        val currentSeason = discoverViewModel.selectedSeason.value
        seasons.forEach { season ->
            val chip = Chip(requireContext()).apply {
                text = season.label
                isCheckable = true
                isChecked = (season.apiValue == currentSeason.apiValue && season.year == currentSeason.year)
                isFocusable = true
                setTextColor(onSurface)
                setOnClickListener {
                    binding.seasonChipGroup.clearCheck()
                    isChecked = true
                    discoverViewModel.selectSeason(season)
                    // A season leaves the Kitsu category, so the row has to show "All" again;
                    // it is updated in place so the focused season chip keeps D-pad focus.
                    updateCategoryRowToAll()
                }
            }
            FocusEffectUtil.applyFocusListener(chip)
            binding.seasonChipGroup.addView(chip)
        }
        // A rotated view rebuilds the chips but the ViewModel kept its category selection, so an
        // active category must re-dim the row on restore rather than looking tappable.
        setSeasonRowEnabled(discoverViewModel.selectedCategory.value == null)
    }

    /** Puts the category row back on "All" after a season pick, without re-inflating it. */
    private fun updateCategoryRowToAll() {
        val group = binding.genreChipGroup
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? Chip)?.isChecked = false
        }
        (group.getChildAt(0) as? Chip)?.isChecked = true
        if (isTv) {
            (group.getChildAt(1) as? Chip)?.text = getString(R.string.categories)
        }
    }

    private fun setupResultsGrid() {
        gridItems = mutableListOf()
        gridAdapter = MediaAdaptor(0, gridItems, requireActivity(), true)
        binding.discoverRecyclerView.apply {
            val dm = resources.displayMetrics
            layoutManager = GridLayoutManager(requireContext(), TmdbCards.gridSpan(dm.widthPixels / dm.density))
            // matchParent: the card takes the column width, so the count can follow the
            // screen instead of a hardcoded 3 that is right on a phone and wrong on a TV.
            adapter = gridAdapter
            isNestedScrollingEnabled = false
            isFocusable = true
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    true
                } else false
            }
        }
        // The grid is wrap_content inside a NestedScrollView, so it never scrolls itself; the
        // next page is pulled once the surrounding scroll closes to within a screen of its end.
        binding.discoverScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val content = binding.discoverScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            val remaining = content - binding.discoverScroll.scrollY - binding.discoverScroll.height
            if (remaining <= resources.displayMetrics.heightPixels) {
                discoverViewModel.loadMore()
            }
        }
        binding.discoverLoadMoreRetry.setOnClickListener { discoverViewModel.retryLoadMore() }
        FocusEffectUtil.applyFocusListener(binding.discoverRetryChip, binding.discoverLoadMoreRetry)
        binding.discoverRetryChip.setOnClickListener { discoverViewModel.retry() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.uiState.collect { render(it) }
        }
    }

    /**
     * Renders a state without ever swapping the adapter, so scroll position and D-pad focus
     * survive paging. A new queryId rebuilds the list and jumps to the top; the same query
     * only ever appends, so later pages animate in below the fold.
     */
    private fun render(state: DiscoverUiState) {
        val newQuery = state.queryId != renderedQueryId
        if (newQuery) renderedQueryId = state.queryId

        if (newQuery) {
            gridItems.clear()
            gridItems.addAll(state.items)
            gridAdapter.notifyDataSetChanged()
            binding.discoverRecyclerView.scrollToPosition(0)
            binding.discoverScroll.scrollTo(0, 0)
        } else if (state.items.size > gridItems.size) {
            val start = gridItems.size
            gridItems.addAll(state.items.subList(start, state.items.size))
            gridAdapter.notifyItemRangeInserted(start, gridItems.size - start)
        }

        renderEditorialShelves(state.items)

        val hasItems = state.items.isNotEmpty()
        binding.discoverProgressBar.isVisible = state.isInitialLoading && !hasItems
        binding.discoverRecyclerView.isVisible = hasItems
        binding.discoverEmptyText.isVisible = !state.isInitialLoading && !state.error && !hasItems
        binding.discoverErrorGroup.isVisible = state.error && !hasItems
        // The footer lives inside the scrolling content, so hide it whenever there is no grid to
        // sit under rather than leaving a stray spinner/retry on an empty screen.
        binding.discoverLoadingMore.isVisible = hasItems && state.isLoadingMore
        binding.discoverLoadMoreErrorGroup.isVisible = hasItems && state.loadMoreError
    }

    private fun renderEditorialShelves(media: List<Media>) {
        fun searchable(item: Media): String = buildString {
            append(item.userPreferredName).append(' ')
            append(item.nameRomaji).append(' ')
            append(item.description.orEmpty()).append(' ')
            append(item.genres.joinToString(" ")).append(' ')
            append(item.tags.joinToString(" "))
        }.lowercase()

        fun shelf(
            title: String,
            note: String,
            predicate: (Media, String) -> Boolean
        ): EditorialShelfData {
            val cards = media.asSequence()
                .map { it to searchable(it) }
                .filter { (item, text) -> predicate(item, text) }
                .distinctBy { it.first.id }
                .take(10)
                .map { (item, _) ->
                    EditorialCardData(
                        title = item.userPreferredName,
                        meta = listOfNotNull(
                            item.startDate?.year?.takeIf { it > 0 }?.toString(),
                            item.meanScore?.takeIf { it > 0 }?.let { "★ $it" }
                        ).joinToString(" · "),
                        imageUrl = item.cover,
                        externalUrl = item.idIMDB?.takeIf { it.startsWith("tt") }
                            ?.let { "https://www.imdb.com/title/$it/" }
                    )
                }.toList()
            return EditorialShelfData(title, note, cards, placeholderCount = 0)
        }

        val shelves = listOf(
            shelf("THE SLOW BURN CLUB", "The tension is the point. The payoff is earned.") { item, text ->
                ("romance" in item.genres.map { it.lowercase() } || "drama" in item.genres.map { it.lowercase() } ||
                    "slice of life" in item.genres.map { it.lowercase() }) &&
                    listOf("slow burn", "gradually", "slowly develops", "will they", "relationship develops",
                        "longing", "romantic tension", "unspoken feelings", "friends to lovers").any(text::contains)
            },
            shelf("FIVE STEPS AHEAD", "Master plans, mind games and people reading the room.") { item, text ->
                listOf("mastermind", "strateg", "manipulat", "mind game", "psychological", "deduction",
                    "outsmart", "schem", "detective", "tactical genius").any(text::contains)
            },
            shelf("THE VILLAIN HAS A POINT", "Messy motives. Questionable methods. A real argument.") { item, text ->
                listOf("anti-hero", "antihero", "morally grey", "morally gray", "revenge", "villain protagonist",
                    "tragic antagonist", "corruption", "justice", "vengeance").any(text::contains)
            },
            shelf("BEAUTIFULLY UNWELL", "Unstable minds, complicated feelings and emotional wreckage.") { item, text ->
                ("psychological" in item.genres.map { it.lowercase() } ||
                    "drama" in item.genres.map { it.lowercase() } ||
                    "thriller" in item.genres.map { it.lowercase() }) &&
                    listOf("obsession", "trauma", "mental", "descent", "unravel", "loneliness",
                        "grief", "identity", "paranoia", "inner turmoil").any(text::contains)
            },
            shelf("ONE MORE EPISODE", "The dangerously easy-to-binge queue.") { item, text ->
                item.popularity != null && item.popularity!! > 0 &&
                    (item.status == "RELEASING" || item.meanScore?.let { it >= 75 } == true)
            },
            EditorialShelfData(
                "TMDB ANIMATION RADAR", "Animation from TMDB adds another discovery lens; this shelf can include non-Japanese animation too.",
                tmdbAnimationPicks.map { item ->
                    EditorialCardData(
                        title = item.displayTitle,
                        meta = listOfNotNull(
                            item.year.takeIf { it.isNotBlank() },
                            item.type.uppercase(),
                            item.voteAverage.takeIf { it > 0.0 }?.let { "TMDB ★ ${"%.1f".format(java.util.Locale.US, it)}" }
                        ).joinToString(" · "),
                        imageUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                        externalUrl = "https://www.themoviedb.org/${item.type}/${item.id}"
                    )
                },
                placeholderCount = 0
            ),
            EditorialShelfData(
                "KITSU AFTER HOURS", "A second anime catalogue, ranked by Kitsu's own community activity.",
                kitsuPicks.map { pick ->
                    EditorialCardData(
                        title = pick.title,
                        meta = listOfNotNull(
                            pick.year?.toString(),
                            pick.score?.takeIf { it > 0 }?.let { "Kitsu ★ ${"%.1f".format(java.util.Locale.US, it)}" },
                            pick.userCount?.takeIf { it > 0 }?.let { "${it} members" }
                        ).joinToString(" · "),
                        imageUrl = pick.posterUrl,
                        externalUrl = "https://kitsu.io/anime/${pick.id}"
                    )
                },
                placeholderCount = 0
            ),
        )
        EditorialShelfRenderer.render(requireContext(), binding.discoverEditorialShelves, shelves)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderedQueryId = -1
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges, so this fragment's view survives the
        // rotation and the span built in setupResultsGrid is still the portrait one.
        _binding?.discoverRecyclerView?.let { TmdbCards.applyGridSpan(it) }
    }
}
