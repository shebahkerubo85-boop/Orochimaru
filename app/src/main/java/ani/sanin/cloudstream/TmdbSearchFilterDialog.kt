package ani.sanin.cloudstream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import ani.sanin.BottomSheetDialogFragment
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.databinding.BottomSheetTmdbFilterBinding
import ani.sanin.media.SheetSourceSelector
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

data class TmdbFilterResult(
    val mediaType: String,
    val genres: List<Int>,
    val keywords: List<Int>,
    val year: Int?,
    val sort: String?
)

class TmdbSearchFilterDialog : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTmdbFilterBinding? = null
    private val binding get() = _binding!!
    var onApply: ((TmdbFilterResult) -> Unit)? = null

    private val selectedGenres = mutableListOf<Int>()
    private val selectedKeywords = mutableListOf<Int>()
    private val sortOptions = listOf("Popularity", "Release Date", "Rating", "A-Z")
    private var sortIndex = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTmdbFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FocusEffectUtil.applyFocusListener(
            binding.tmdbFilterReset,
            binding.tmdbFilterSort,
            binding.tmdbFilterYear,
            binding.tmdbFilterKeywordInput,
            binding.tmdbFilterKeywordAdd,
            binding.tmdbFilterApply
        )

        binding.tmdbFilterSort.setOnClickListener {
            sortIndex = (sortIndex + 1) % sortOptions.size
            binding.tmdbFilterSort.text = "Sort: ${sortOptions[sortIndex]}"
        }

        binding.tmdbFilterReset.setOnClickListener {
            selectedGenres.clear()
            selectedKeywords.clear()
            sortIndex = 0
            binding.tmdbFilterSort.text = "Sort: ${sortOptions[0]}"
            binding.tmdbFilterYear.setText("")
            binding.tmdbFilterMovieChip.isChecked = true
            binding.tmdbFilterTvChip.isChecked = false
            for (i in 0 until binding.tmdbFilterGenres.childCount) {
                (binding.tmdbFilterGenres.getChildAt(i) as? Chip)?.isChecked = false
            }
            binding.tmdbFilterKeywordChips.removeAllViews()
            snackString("Filter reset")
        }

        binding.tmdbFilterKeywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeyword()
                true
            } else false
        }
        binding.tmdbFilterKeywordAdd.setOnClickListener { addKeyword() }

        binding.tmdbFilterApply.setOnClickListener {
            val mediaType = if (binding.tmdbFilterTvChip.isChecked) "tv" else "movie"
            val sort = when (sortOptions[sortIndex]) {
                "Popularity" -> "popularity.desc"
                "Release Date" -> if (mediaType == "tv") "first_air_date.desc" else "primary_release_date.desc"
                "Rating" -> "vote_average.desc"
                else -> if (mediaType == "tv") "name.asc" else "original_title.asc"
            }
            onApply?.invoke(
                TmdbFilterResult(
                    mediaType = mediaType,
                    genres = selectedGenres.toList(),
                    keywords = selectedKeywords.toList(),
                    year = binding.tmdbFilterYear.text.toString().toIntOrNull(),
                    sort = sort
                )
            )
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val genres = Tmdb.genres()
            genres.forEach { genre -> addGenreChip(genre) }
        }
    }

    private fun addGenreChip(genre: TmdbGenre) {
        val chip = Chip(requireContext()).apply {
            text = genre.name
            isCheckable = true
            isFocusable = true
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedGenres.add(genre.id)
                else selectedGenres.remove(genre.id)
            }
        }
        FocusEffectUtil.applyFocusListener(chip)
        binding.tmdbFilterGenres.addView(chip)
    }

    private fun addKeyword() {
        val query = binding.tmdbFilterKeywordInput.text.toString().trim()
        if (query.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val results = Tmdb.searchKeywords(query)
            if (results.isEmpty()) {
                snackString("No keyword found for '$query'")
                return@launch
            }
            if (results.size == 1) {
                addKeywordChip(results[0].id, results[0].name)
                binding.tmdbFilterKeywordInput.setText("")
                return@launch
            }
            val sheet = SheetSourceSelector.newInstance(
                ArrayList(results.map { it.name }),
                onSelect = { idx ->
                    val kw = results[idx]
                    addKeywordChip(kw.id, kw.name)
                    binding.tmdbFilterKeywordInput.setText("")
                }
            )
            sheet.show(childFragmentManager, "keywordPicker")
        }
    }

    private fun addKeywordChip(id: Int, name: String) {
        if (selectedKeywords.contains(id)) return
        selectedKeywords.add(id)
        val chip = Chip(requireContext()).apply {
            text = name
            isCloseIconVisible = true
            isFocusable = true
            setOnCloseIconClickListener {
                selectedKeywords.remove(id)
                binding.tmdbFilterKeywordChips.removeView(this)
            }
        }
        FocusEffectUtil.applyFocusListener(chip)
        binding.tmdbFilterKeywordChips.addView(chip)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(onApply: ((TmdbFilterResult) -> Unit)?): TmdbSearchFilterDialog {
            return TmdbSearchFilterDialog().apply { this.onApply = onApply }
        }
    }
}
