package ani.sanin.home

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import ani.sanin.R
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.FragmentTmdbLibraryBinding
import ani.sanin.getThemeColor
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

        binding.tmdbLibAppBar.setBackgroundColor(primaryColor)
        binding.tmdbLibTitle.setTextColor(primaryTextColor)
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

        binding.tmdbLibSort.setOnClickListener {
            val popup = PopupMenu(requireContext(), it)
            popup.setOnMenuItemClickListener { item ->
                val sort = when (item.itemId) {
                    R.id.score -> "score"
                    R.id.title -> "title"
                    R.id.updated -> "updated"
                    R.id.release -> "year"
                    else -> null
                }
                if (sort != null) {
                    sectionFragments.forEach { it.sort(sort) }
                }
                true
            }
            popup.inflate(R.menu.list_sort_menu)
            popup.show()
        }

        binding.tmdbLibFilter.setOnClickListener {
            val statuses = listOf(
                "All", "Completed Movies", "Completed TV", "Watching",
                "Planning", "Paused", "Dropped", "Favourites"
            )
            val popup = PopupMenu(requireContext(), it)
            statuses.forEach { popup.menu.add(it) }
            popup.setOnMenuItemClickListener { menuItem ->
                val selected = menuItem.title.toString()
                if (selected == "All") {
                    showSections(allItems)
                } else {
                    val filtered = when (selected) {
                        "Completed Movies" -> allItems.filter {
                            it.status?.lowercase() == "completed" && it.mediaType == "movie"
                        }
                        "Completed TV" -> allItems.filter {
                            it.status?.lowercase() == "completed" && it.mediaType == "tv"
                        }
                        "Watching" -> allItems.filter {
                            it.status?.lowercase() == "watching" || it.status?.lowercase() == "current"
                        }
                        "Planning" -> allItems.filter {
                            it.status?.lowercase() == "plantowatch" || it.status?.lowercase() == "planning"
                        }
                        "Paused" -> allItems.filter {
                            it.status?.lowercase() == "onhold" || it.status?.lowercase() == "paused"
                        }
                        "Dropped" -> allItems.filter {
                            it.status?.lowercase() == "dropped"
                        }
                        "Favourites" -> allItems.filter { (it.userRating ?: 0) > 0 }
                        else -> allItems
                    }
                    showFilteredSections(filtered, selected)
                }
                true
            }
            popup.show()
        }

        binding.tmdbLibSearch.setOnClickListener {
            toggleSearchView(binding.tmdbLibSearchView.isVisible)
            if (!binding.tmdbLibSearchView.isVisible) {
                sectionFragments.forEach { it.filter("") }
            }
        }

        binding.tmdbLibSearchText.addTextChangedListener { editable ->
            val query = editable?.toString() ?: ""
            sectionFragments.forEach { it.filter(query) }
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
            it.status?.lowercase() == "onhold" || it.status?.lowercase() == "paused"
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

    private fun toggleSearchView(isVisible: Boolean) {
        if (isVisible) {
            binding.tmdbLibSearchView.visibility = View.GONE
            binding.tmdbLibSearchText.text.clear()
            sectionFragments.forEach { it.filter("") }
        } else {
            binding.tmdbLibSearchView.visibility = View.VISIBLE
            binding.tmdbLibSearchText.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        viewPagerAttached = false
        sectionFragments.clear()
    }
}
