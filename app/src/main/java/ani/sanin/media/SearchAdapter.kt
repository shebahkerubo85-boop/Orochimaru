package ani.sanin.media

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.App.Companion.context
import ani.sanin.R
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.anilist.AnilistSearch.SearchType
import ani.sanin.databinding.ItemSearchHeaderBinding
import ani.sanin.getThemeColor
import ani.sanin.openLinkInBrowser
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.ui.LensButtonBackground
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.TvKeyboardUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class SearchAdapter(
    activity: SearchActivity,
    private val type: SearchType,
    binding: ItemSearchHeaderBinding
) : HeaderInterface(activity, binding) {

    private var suggestionJob: Job? = null
    private var suggestionDialog: AlertDialog? = null
    private var suggestionRequest = 0
    private var choosingSuggestion = false

    private fun updateFilterTextViewDrawable() {
        val filterDrawable = when (activity.aniMangaResult.sort) {
            Anilist.sortBy[0] -> R.drawable.ic_round_area_chart_24
            Anilist.sortBy[1] -> R.drawable.ic_round_filter_peak_24
            Anilist.sortBy[2] -> R.drawable.ic_round_star_graph_24
            Anilist.sortBy[3] -> R.drawable.ic_round_new_releases_24
            Anilist.sortBy[4] -> R.drawable.ic_round_filter_list_24
            Anilist.sortBy[5] -> R.drawable.ic_round_filter_list_24_reverse
            Anilist.sortBy[6] -> R.drawable.ic_round_assist_walker_24
            else -> R.drawable.ic_round_filter_alt_24
        }
        binding.searchFilter.setImageResource(filterDrawable)
        binding.searchFilter.imageTintList =
            ColorStateList.valueOf(FocusEffectUtil.getPrimaryColor(activity))
    }

    private fun hasActiveFilters(): Boolean = activity.aniMangaResult.let {
        it.sort != null || it.status != null || it.source != null || it.format != null ||
            it.countryOfOrigin != null || it.season != null || it.seasonYear != null ||
            it.startYear != null || !it.genres.isNullOrEmpty() ||
            !it.excludedGenres.isNullOrEmpty() || !it.tags.isNullOrEmpty() ||
            !it.excludedTags.isNullOrEmpty()
    }

    private fun updateFilterState() {
        // The filter is a lens button now: tint the icon instead of filling a chip.
        val tint = if (hasActiveFilters()) {
            FocusEffectUtil.getPrimaryColor(activity)
        } else {
            activity.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        }
        binding.searchFilter.imageTintList = ColorStateList.valueOf(tint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun bind() {
        searchHistoryAdapter = SearchHistoryAdapter(
            type,
            upFocusId = R.id.searchFilter
        ) {
            binding.searchBarText.setText(it)
            binding.searchBarText.setSelection(it.length)
        }
        searchHistoryAdapter.onItemCountChanged = { updateClearHistoryVisibility() }
        binding.searchHistoryList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.searchHistoryList.adapter = searchHistoryAdapter

        if (activity.searchType != SearchType.MANGA && activity.searchType != SearchType.ANIME) {
            throw IllegalArgumentException("Invalid search type (wrong adapter)")
        }

        when (activity.style) {
            0 -> {
                binding.searchResultGrid.alpha = 1f
                binding.searchResultList.alpha = 0.33f
            }

            1 -> {
                binding.searchResultList.alpha = 1f
                binding.searchResultGrid.alpha = 0.33f
            }
        }

        binding.searchBar.hint = activity.aniMangaResult.type
        if (PrefManager.getVal(PrefName.Incognito)) {
            val startIconDrawableRes = R.drawable.ic_incognito_24
            val startIconDrawable: Drawable? =
                context?.let { AppCompatResources.getDrawable(it, startIconDrawableRes) }
            binding.searchBar.startIconDrawable = startIconDrawable
        }

        binding.searchBarText.removeTextChangedListener(textWatcher)
        binding.searchBarText.setText(activity.aniMangaResult.search)

        LensButtonBackground.apply(binding.searchFilter)
        LensButtonBackground.apply(binding.clearHistory)
        LensButtonBackground.apply(binding.searchResultGrid)
        LensButtonBackground.apply(binding.searchResultList)

        FocusEffectUtil.applyFocusListener(
            binding.clearHistory,
            binding.searchFilter,
            binding.searchResultGrid,
            binding.searchResultList
        )

        binding.searchBarText.nextFocusDownId = R.id.searchFilter
        binding.searchFilter.nextFocusLeftId = R.id.searchBarText
        binding.searchFilter.nextFocusRightId = R.id.searchKeyboardToggle
        binding.searchKeyboardToggle.nextFocusLeftId = R.id.searchFilter

        binding.searchFilter.setOnClickListener {
            SearchFilterBottomDialog.newInstance().show(activity.supportFragmentManager, "dialog")
        }
        binding.searchFilter.setOnLongClickListener {
            val popupMenu = PopupMenu(activity, binding.searchFilter)
            popupMenu.menuInflater.inflate(R.menu.sortby_filter_menu, popupMenu.menu)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.sort_by_score -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[0]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_popular -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[1]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_trending -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[2]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_recent -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[3]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_a_z -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[4]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_z_a -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[5]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }

                    R.id.sort_by_pure_pain -> {
                        activity.aniMangaResult.sort = Anilist.sortBy[6]
                        activity.updateChips.invoke()
                        activity.search()
                        updateFilterTextViewDrawable()
                    }
                }
                true
            }
            popupMenu.show()
            true
        }
        updateFilterState()
        activity.updateChips = { updateFilterState() }

        binding.clearHistory.setOnClickListener {
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.SearchHeaderAnimations)) {
                it.startAnimation(fadeOutAnimation())
            } else {
                it.alpha = 0f
            }
            it.visibility = View.GONE
            searchHistoryAdapter.clearHistory()
            updateActionRowFocusTargets()
        }
        updateClearHistoryVisibility()

        fun searchTitle() {
            activity.aniMangaResult.apply {
                search =
                    if (binding.searchBarText.text.toString() != "") binding.searchBarText.text.toString() else null
            }
            if (binding.searchBarText.text.toString().equals("hentai", true)) {
                openLinkInBrowser("https://www.youtube.com/watch?v=GgJrEOo0QoA")
            }
            activity.search()
        }

        textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {}
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                suggestionJob?.cancel()
                suggestionRequest++
                if (choosingSuggestion) { choosingSuggestion = false; return }
                if (query.isBlank()) {
                    suggestionDialog?.dismiss(); suggestionDialog = null
                    activity.emptyMediaAdapter()
                    CoroutineScope(Dispatchers.IO).launch { delay(200); activity.runOnUiThread { setHistoryVisibility(true) } }
                    return
                }
                setHistoryVisibility(false)
                if (query.length < 2 || activity.searchType != SearchType.ANIME) return
                val request = suggestionRequest
                suggestionJob = activity.lifecycleScope.launch {
                    delay(350)
                    val rows = withContext(Dispatchers.IO) {
                        val tmdb = runCatching { Tmdb.searchPage(query).results.take(8) }.getOrDefault(emptyList())
                        val ani = runCatching { Anilist.query.searchAniManga(type = "ANIME", perPage = 15, search = query, isAdult = activity.aniMangaResult.isAdult)?.results.orEmpty() }.getOrDefault(emptyList())
                        val used = mutableSetOf<Int>()
                        tmdb.map { item ->
                            val title = item.displayTitle.trim(); val normalized = title.lowercase()
                            val match = ani.firstOrNull { candidate -> candidate.id !in used && listOf(candidate.name, candidate.nameRomaji, candidate.userPreferredName).filterNotNull().any { it.trim().equals(normalized, true) } }
                                ?: ani.firstOrNull { candidate -> candidate.id !in used && listOf(candidate.name, candidate.nameRomaji, candidate.userPreferredName).filterNotNull().any { it.trim().contains(normalized, true) || normalized.contains(it.trim(), true) } }
                            if (match != null) used.add(match.id)
                            (match?.userPreferredName?.takeIf { it.isNotBlank() } ?: title) + "  ·  " + (item.year.ifBlank { if (item.type == "movie") "Movie" else "TV" })
                        }.distinct()
                    }
                    if (request != suggestionRequest || binding.searchBarText.text.toString().trim() != query || rows.isEmpty()) return@launch
                    suggestionDialog?.dismiss()
                    val choices = rows + "See all results for ‘$query’"
                    suggestionDialog = AlertDialog.Builder(activity).setTitle("Suggestions").setItems(choices.toTypedArray()) { dialog, which ->
                        choosingSuggestion = true
                        binding.searchBarText.setText(if (which == rows.size) query else rows[which].substringBefore("  ·  "))
                        binding.searchBarText.setSelection(binding.searchBarText.text.length)
                        dialog.dismiss(); searchTitle()
                    }.setOnCancelListener { suggestionDialog = null }.create()
                    suggestionDialog?.setOnDismissListener { suggestionDialog = null }
                    suggestionDialog?.show()
                }
            }
        }
        binding.searchBarText.addTextChangedListener(textWatcher)

        binding.searchBarText.setOnEditorActionListener { _, actionId, _ ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEARCH -> {
                    searchTitle()
                    binding.searchBarText.clearFocus()
                    true
                }

                else -> false
            }
        }
        binding.searchBar.setEndIconOnClickListener { suggestionJob?.cancel(); suggestionDialog?.dismiss(); searchTitle() }

        binding.searchResultGrid.setOnClickListener {
            it.alpha = 1f
            binding.searchResultList.alpha = 0.33f
            activity.style = 0
            PrefManager.setVal(PrefName.SearchStyle, 0)
            activity.recycler()
        }
        binding.searchResultList.setOnClickListener {
            it.alpha = 1f
            binding.searchResultGrid.alpha = 0.33f
            activity.style = 1
            PrefManager.setVal(PrefName.SearchStyle, 1)
            activity.recycler()
        }

        binding.searchBarText.post {
            val mode = TvKeyboardUtil.keyboardMode()
            Logger.log(Log.INFO, "SearchAdapter: applying keyboard setup mode=$mode to searchBarText", "TvKeyboard")
            when (mode) {
                0 -> TvKeyboardUtil.setupSystemKeyboard(binding.searchBarText)
                1 -> TvKeyboardUtil.setupEditTextWithToggle(binding.searchBarText, binding.searchKeyboardToggle)
                2 -> TvKeyboardUtil.setupEditTextForAlwaysVisible(binding.searchBarText)
            }
        }
        search = Runnable { searchTitle() }
        requestFocus = Runnable { binding.searchBarText.requestFocus() }
    }

    override fun imageSearchVisible(): Boolean = activity.aniMangaResult.type == "ANIME"
}
