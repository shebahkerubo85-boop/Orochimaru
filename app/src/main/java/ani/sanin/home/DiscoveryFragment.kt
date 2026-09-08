package ani.sanin.home

import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
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
        discoverViewModel.fetch()
    }

    private fun setupSearchBar() {
        binding.discoverSearchBar.hint = getString(R.string.search)
        binding.discoverSearchText.setOnClickListener {
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
    }

    private fun setupAvatar() {
        val avatarUrl = if (ani.sanin.connections.anilist.Anilist.avatar != null)
            ani.sanin.connections.anilist.Anilist.avatar else null
        if (avatarUrl != null) {
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
        animeGenres.forEach { genre ->
            val chip = Chip(requireContext()).apply {
                text = genre
                isCheckable = true
                isChecked = (genre == "All" && discoverViewModel.selectedGenre.value == null)
                setOnClickListener {
                    binding.genreChipGroup.clearCheck()
                    isChecked = true
                    discoverViewModel.selectGenre(if (genre == "All") null else genre)
                }
            }
            binding.genreChipGroup.addView(chip)
        }
    }

    private fun setupSeasonChips() {
        val seasons = DiscoverSeason.seasons()
        val currentSeason = discoverViewModel.selectedSeason.value
        seasons.forEach { season ->
            val chip = Chip(requireContext()).apply {
                text = season.label
                isCheckable = true
                isChecked = (season.apiValue == currentSeason.apiValue && season.year == currentSeason.year)
                setOnClickListener {
                    binding.seasonChipGroup.clearCheck()
                    isChecked = true
                    discoverViewModel.selectSeason(season)
                }
            }
            binding.seasonChipGroup.addView(chip)
        }
    }

    private fun setupResultsGrid() {
        binding.discoverRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = MediaAdaptor(0, mutableListOf(), requireActivity())
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
                    MediaAdaptor(0, media.toMutableList(), requireActivity()),
                    false,
                )
                if (media.isEmpty() && !discoverViewModel.isLoading.value) {
                    binding.discoverEmptyText.isVisible = true
                } else {
                    binding.discoverEmptyText.isVisible = false
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
