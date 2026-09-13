package ani.sanin.home

import android.os.Bundle
import ani.sanin.statusBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.databinding.FragmentTmdbLibraryBinding
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.getThemeColor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbLibraryFragment : Fragment() {

    private var _binding: FragmentTmdbLibraryBinding? = null
    private val binding get() = _binding!!
    private var selectedTabIdx = 0
    private var viewPagerAttached = false
    private var allItems: List<Simkl.SimklWatchedItem> = emptyList()
    private var sectionFragments = mutableListOf<SimklSectionFragment>()
    private val libraryGenres = sortedSetOf<String>()
    private val itemGenres = HashMap<Pair<String, Int>, Set<String>>()
    private var genreEnrichmentRunning = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val primaryColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val secondaryTextColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorOutline)

        // Follow CalendarActivity pattern
        if (PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            binding.tmdbLibSettingsContainer.setPadding(0, 0, 0, 0)
            binding.tmdbLibSettingsContainer.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            binding.root.fitsSystemWindows = false
        } else {
            binding.root.fitsSystemWindows = true
        }

        binding.tmdbLibAppBar.setBackgroundColor(primaryColor)
        binding.tmdbLibTabLayout.setBackgroundColor(primaryColor)
        binding.tmdbLibTabLayout.setTabTextColors(secondaryTextColor, primaryTextColor)
        binding.tmdbLibTabLayout.setSelectedTabIndicatorColor(primaryTextColor)

        binding.tmdbLibTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTabIdx = tab?.position ?: 0
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        if (Simkl.token == null) {
            showNotLoggedIn()
            return
        }

        binding.tmdbLibProgressBar.visibility = View.VISIBLE
        loadLibrary()

        // Observe Refresh signals so library reloads after list edits or scrobble
        val live = Refresh.activity.getOrPut(requireActivity().hashCode()) { androidx.lifecycle.MutableLiveData(true) }
        live.observe(viewLifecycleOwner) { if (it == true) { loadLibrary(); live.postValue(false) } }

        // Settings → bottom sheet (sort + status filter, no NSFW for movie mode)
        FocusEffectUtil.applyFocusListener(binding.tmdbLibSettings)
        binding.tmdbLibSettings.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.tmdbLibSettings)
            LibrarySettingsBottomSheet.newInstance(
                currentSort = "updated",
                filterItems = synchronized(itemGenres) { libraryGenres.toList() },
                showNsfw = false,
                onSortChanged = { sort ->
                    val mapped = when (sort) {
                        "updatedAt" -> "updated"; "release" -> "year"; else -> sort
                    }
                    sectionFragments.forEach { it.sort(mapped) }
                },
                onGenreFilterChanged = { selected ->
                    if (selected.isBlank() || selected == "All") {
                        showSections(allItems)
                    } else {
                        val filtered = synchronized(itemGenres) {
                            allItems.filter { item ->
                                val tmdbId = item.ids?.tmdb
                                tmdbId != null &&
                                    (itemGenres[(item.mediaType ?: "tv") to tmdbId] ?: emptySet()).contains(selected)
                            }
                        }
                        showFilteredSections(filtered, selected)
                    }
                },
                onNsfwChanged = null
            ).show(childFragmentManager, LibrarySettingsBottomSheet.TAG)
        }

        // Search → inline bar
        TvKeyboardUtil.setupTvInput(binding.tmdbLibSearchText)
        binding.tmdbLibSearchBar.setOnClickListener { binding.tmdbLibSearchText.requestFocus() }
        FocusEffectUtil.applyFocusListener(binding.tmdbLibSearchBar)
        binding.tmdbLibSearchText.addTextChangedListener { editable ->
            val query = editable?.toString() ?: ""
            sectionFragments.forEach { it.filter(query) }
        }

        // Avatar → open side rail
        FocusEffectUtil.applyFocusListener(binding.tmdbLibAvatar)
        val libAvatarUrl = Anilist.avatar ?: Simkl.avatar
        if (!libAvatarUrl.isNullOrBlank()) {
            binding.tmdbLibAvatar.loadImage(libAvatarUrl)
        }
        binding.tmdbLibAvatar.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.tmdbLibAvatar)
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


    }
    private fun loadLibrary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val movies = withContext(Dispatchers.IO) { Simkl.getMovieLibrary() }
            val shows = withContext(Dispatchers.IO) { Simkl.getShowLibrary() }
            binding.tmdbLibProgressBar.visibility = View.GONE
            allItems = movies + shows
            if (allItems.isEmpty()) {
                showEmpty()
                return@launch
            }
            showSections(allItems)
            enrichGenres()
        }
    }

    /** Fetch TMDB genres for library items once per session (batched, cached). */
    private fun enrichGenres() {
        if (genreEnrichmentRunning) return
        genreEnrichmentRunning = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            coroutineScope {
                val targets = allItems.filter { it.ids?.tmdb != null }
                    .distinctBy { (it.mediaType ?: "tv") to it.ids!!.tmdb!! }
                targets.chunked(8).forEach { batch ->
                    batch.map { item -> async { enrichOne(item) } }.forEach { it.await() }
                }
            }
            genreEnrichmentRunning = false
        }
    }

    private suspend fun enrichOne(item: Simkl.SimklWatchedItem) {
        val tmdbId = item.ids?.tmdb ?: return
        val type = item.mediaType ?: "tv"
        val key = type to tmdbId
        synchronized(itemGenres) { if (itemGenres.containsKey(key)) return }
        val genres = runCatching { Tmdb.detailGenres(type, tmdbId).map { it.name }.toSet() }
            .getOrDefault(emptySet())
        if (genres.isEmpty()) return
        synchronized(itemGenres) {
            itemGenres[key] = genres
            libraryGenres.addAll(genres)
        }
    }

    private fun showSections(items: List<Simkl.SimklWatchedItem>) {
        viewPagerAttached = false
        binding.tmdbLibTabLayout.removeAllTabs()
        sectionFragments.clear()

        val sections = linkedMapOf<String, List<Simkl.SimklWatchedItem>>()

        val completedMovies = items.filter {
            it.status?.lowercase() == "completed" && it.mediaType == "movie"
        }
        val completedShows = items.filter {
            it.status?.lowercase() == "completed" && it.mediaType == "tv"
        }
        val watching = items.filter {
            it.status?.lowercase() == "watching" || it.status?.lowercase() == "current"
        }
        val planning = items.filter {
            it.status?.lowercase() == "plantowatch" || it.status?.lowercase() == "planning"
        }
        val paused = items.filter {
            it.status?.lowercase() == "hold" || it.status?.lowercase() == "onhold" || it.status?.lowercase() == "paused"
        }
        val dropped = items.filter { it.status?.lowercase() == "dropped" }
        val favourites = items.filter { (it.userRating ?: 0) > 0 }

        if (completedMovies.isNotEmpty()) sections["Completed Movies (${completedMovies.size})"] = completedMovies
        if (completedShows.isNotEmpty()) sections["Completed TV (${completedShows.size})"] = completedShows
        if (watching.isNotEmpty()) sections["Watching (${watching.size})"] = watching
        if (planning.isNotEmpty()) sections["Planning (${planning.size})"] = planning
        if (paused.isNotEmpty()) sections["Paused (${paused.size})"] = paused
        if (dropped.isNotEmpty()) sections["Dropped (${dropped.size})"] = dropped
        if (favourites.isNotEmpty()) sections["Favourites (${favourites.size})"] = favourites
        sections["All (${items.size})"] = items

        if (sections.isEmpty()) {
            showEmpty()
            return
        }

        val fragments = sections.map { (_, sectionItems) ->
            SimklSectionFragment.newInstance(sectionItems)
        }
        sectionFragments.addAll(fragments)

        val titles = sections.keys.toList()

        binding.tmdbLibViewPager.adapter = SimklPagerAdapter(sectionFragments, requireActivity())
        binding.tmdbLibTabLayout.isVisible = true
        binding.tmdbLibViewPager.isVisible = true

        TabLayoutMediator(binding.tmdbLibTabLayout, binding.tmdbLibViewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        viewPagerAttached = true
        binding.tmdbLibViewPager.setCurrentItem(
            selectedTabIdx.coerceIn(0, titles.size - 1), false
        )
    }

    private fun showFilteredSections(items: List<Simkl.SimklWatchedItem>, title: String) {
        viewPagerAttached = false
        binding.tmdbLibTabLayout.removeAllTabs()
        sectionFragments.clear()

        val fragment = SimklSectionFragment.newInstance(items)
        sectionFragments.add(fragment)

        binding.tmdbLibViewPager.adapter = SimklPagerAdapter(sectionFragments, requireActivity())
        binding.tmdbLibTabLayout.isVisible = true
        binding.tmdbLibViewPager.isVisible = true

        val tab = binding.tmdbLibTabLayout.newTab()
        tab.text = "$title (${items.size})"
        binding.tmdbLibTabLayout.addTab(tab)

        viewPagerAttached = true
        binding.tmdbLibViewPager.setCurrentItem(0, false)
    }

    private fun showNotLoggedIn() {
        binding.tmdbLibProgressBar.visibility = View.GONE
        binding.tmdbLibTabLayout.isVisible = false
        binding.tmdbLibViewPager.isVisible = false
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Log in to Simkl to see your library"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        (binding.root as? ViewGroup)?.addView(msg, lp)
    }

    private fun showEmpty() {
        binding.tmdbLibProgressBar.visibility = View.GONE
        binding.tmdbLibTabLayout.isVisible = false
        binding.tmdbLibViewPager.isVisible = false
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Your Simkl library is empty"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        (binding.root as? ViewGroup)?.addView(msg, lp)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        viewPagerAttached = false
        sectionFragments.clear()
    }
}
