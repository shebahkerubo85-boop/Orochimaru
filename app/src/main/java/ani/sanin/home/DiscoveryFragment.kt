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
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
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
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil

class DiscoverViewModel : ViewModel() {
    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    private val _selectedSeason = MutableStateFlow(DiscoverSeason.current())
    val selectedSeason: StateFlow<DiscoverSeason> = _selectedSeason.asStateFlow()

    private val _results = MutableStateFlow<List<Media>>(emptyList())
    val results: StateFlow<List<Media>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var fetchJob: Job? = null

    fun selectGenre(genre: String?) {
        _selectedGenre.value = genre
        fetch()
    }

    fun selectSeason(season: DiscoverSeason) {
        _selectedSeason.value = season
        fetch()
    }

    fun fetch() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val genre = _selectedGenre.value
                val season = _selectedSeason.value
                val mediaList = Anilist.query.discoverAnime(
                    genre = genre,
                    season = season.apiValue,
                    seasonYear = season.year,
                )
                _results.value = mediaList ?: emptyList()
            } catch (_: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }
}

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

    private val animeGenres = listOf(
        "All", "Action", "Adventure", "Comedy", "Drama", "Ecchi",
        "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life",
        "Sports", "Supernatural", "Thriller",
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSearchBar()
        setupAvatar()
        setupGenreChips()
        setupSeasonChips()
        setupResultsGrid()
        observeViewModel()
        binding.genreChipGroup.isFocusable = true
        binding.seasonChipGroup.isFocusable = true
        // Fix dpad chain: search bar → genre chips → season chips → grid
        // XML had searchBar→avatar→genre which trapped focus; unify to
        // linear vertical chain so DPAD_DOWN from search lands on chips, not avatar.
        binding.discoverSearchBar.nextFocusDownId = R.id.genreChipGroup
        binding.discoverSearchBar.nextFocusRightId = R.id.discoverAvatar
        binding.discoverAvatar.nextFocusDownId = R.id.genreChipGroup
        binding.discoverAvatar.nextFocusLeftId = R.id.discoverSearchBar
        binding.genreChipGroup.nextFocusUpId = R.id.discoverSearchBar
        binding.genreChipGroup.nextFocusDownId = R.id.seasonChipGroup
        binding.seasonChipGroup.nextFocusUpId = R.id.genreChipGroup
        discoverViewModel.fetch()
        loadKitsuEditorial()
        loadTmdbAnimationEditorial()
        loadKitsuCategories()
        renderTrailerCarousel()
        // Steal initial focus so a TV has somewhere to start. On a touch device the search
        // bar is focusableInTouchMode, so this also lit its focus border on open — a phone
        // has no d-pad to seed and should not paint a focus ring nobody asked for.
        if (isTvDevice(requireContext())) {
            binding.discoverSearchBar.post { if (isAdded) binding.discoverSearchBar.requestFocus() }
        }
    }

    private fun loadKitsuEditorial() {
        if (kitsuLoaded) return
        kitsuLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            kitsuPicks = runCatching {
                ani.sanin.connections.kitsu.Kitsu.discoverEditorial(limit = 20)
            }.getOrDefault(emptyList())
            if (_binding != null) renderEditorialShelves(discoverViewModel.results.value)
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
                renderEditorialShelves(discoverViewModel.results.value)
                loadTrailerCarousel()
            }
        }
    }

    private fun loadKitsuCategories() {
        if (kitsuCategories.isNotEmpty()) {
            renderCategoryGrid()
            return
        }
        if (categoriesLoaded) return
        categoriesLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            kitsuCategories = runCatching {
                ani.sanin.connections.kitsu.Kitsu.categories(limit = 60).take(12)
            }.getOrDefault(emptyList())
            if (_binding == null) return@launch
            renderCategoryGrid()
            if (kitsuCategories.isEmpty()) return@launch

            categoryArtwork = kitsuCategories.map { category ->
                async {
                    category.slug to runCatching {
                        ani.sanin.connections.kitsu.Kitsu.categoryAnime(
                            categorySlug = category.slug, offset = 0, limit = 1
                        ).items.firstOrNull()?.posterUrl
                    }.getOrNull()
                }
            }.awaitAll().toMap()
            if (_binding != null) renderCategoryGrid()
        }
    }

    private fun renderCategoryGrid() {
        val host = _binding?.discoverCategoryGrid ?: return
        host.removeAllViews()
        val context = host.context
        host.addView(TextView(context).apply {
            text = "FIND YOUR NEXT OBSESSION"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(14), dp(12), dp(8))
        })
        if (kitsuCategories.isEmpty()) {
            host.addView(TextView(context).apply {
                text = if (categoriesLoaded) "Kitsu categories are unavailable right now."
                    else "Loading Kitsu categories…"
                textSize = 13f
                alpha = 0.78f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(12), dp(4), dp(12), dp(12))
                if (categoriesLoaded) {
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        categoriesLoaded = false
                        loadKitsuCategories()
                    }
                    FocusEffectUtil.applyFocusListener(this)
                }
            })
            return
        }

        kitsuCategories.chunked(2).forEachIndexed { rowIndex, rowCategories ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            rowCategories.forEachIndexed { columnIndex, category ->
                val index = rowIndex * 2 + columnIndex
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                    setOnClickListener {
                        startActivity(Intent(context, KitsuCategoryResultsActivity::class.java)
                            .putExtra(KitsuCategoryResultsActivity.EXTRA_CATEGORY_SLUG, category.slug)
                            .putExtra(KitsuCategoryResultsActivity.EXTRA_CATEGORY_TITLE, category.title))
                    }
                }
                val art = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    rotation = if (index % 2 == 0) -3f else 3f
                    contentDescription = "${category.title} artwork"
                    layoutParams = LinearLayout.LayoutParams(dp(78), dp(62)).apply {
                        marginEnd = dp(7)
                        marginStart = dp(2)
                    }
                }
                categoryArtwork[category.slug]?.takeIf { it.isNotBlank() }?.let { art.loadImage(it) }
                val title = TextView(context).apply {
                    text = category.title
                    textSize = 13f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                if (index % 2 == 0) {
                    card.addView(art)
                    card.addView(title)
                } else {
                    card.addView(title)
                    card.addView(art)
                }
                FocusEffectUtil.applyFocusListener(card)
                row.addView(card)
            }
            if (rowCategories.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
            host.addView(row)
        }
        host.setPadding(0, 0, 0, dp(8))
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
        val onSurface = requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        animeGenres.forEach { genre ->
            val chip = Chip(requireContext()).apply {
                text = genre
                isCheckable = true
                isChecked = (genre == "All" && discoverViewModel.selectedGenre.value == null)
                isFocusable = true
                setTextColor(onSurface)
                setOnClickListener {
                    binding.genreChipGroup.clearCheck()
                    isChecked = true
                    discoverViewModel.selectGenre(if (genre == "All") null else genre)
                }
            }
            FocusEffectUtil.applyFocusListener(chip)
            binding.genreChipGroup.addView(chip)
        }
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
                }
            }
            FocusEffectUtil.applyFocusListener(chip)
            binding.seasonChipGroup.addView(chip)
        }
    }

    private fun setupResultsGrid() {
        binding.discoverRecyclerView.apply {
            val dm = resources.displayMetrics
            layoutManager = GridLayoutManager(requireContext(), TmdbCards.gridSpan(dm.widthPixels / dm.density))
            // matchParent: the card takes the column width, so the count can follow the
            // screen instead of a hardcoded 3 that is right on a phone and wrong on a TV.
            adapter = MediaAdaptor(0, mutableListOf(), requireActivity(), true)
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
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.isLoading.collect { loading ->
                binding.discoverProgressBar.isVisible = loading
                binding.discoverRecyclerView.isVisible = !loading
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            discoverViewModel.results.collect { media ->
                binding.discoverRecyclerView.swapAdapter(
                    MediaAdaptor(0, media.toMutableList(), requireActivity(), true),
                    false,
                )
                renderEditorialShelves(media)
                if (media.isEmpty() && !discoverViewModel.isLoading.value) {
                    binding.discoverEmptyText.isVisible = true
                } else {
                    binding.discoverEmptyText.isVisible = false
                }
            }
        }
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
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges, so this fragment's view survives the
        // rotation and the span built in setupResultsGrid is still the portrait one.
        _binding?.discoverRecyclerView?.let { TmdbCards.applyGridSpan(it) }
    }
}
