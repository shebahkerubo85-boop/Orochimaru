package ani.sanin.home

import android.graphics.Color
import android.os.Bundle
import ani.sanin.statusBarHeight
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.FragmentLibraryBinding
import ani.sanin.getThemeColor
import ani.sanin.isDarkTheme
import ani.sanin.loadImage
import ani.sanin.media.user.ListViewPagerAdapter
import ani.sanin.media.user.ListViewModel
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var selectedTabIdx = 0
    private var viewPagerAttached = false

    private val model: ListViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val primaryColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val secondaryTextColor = requireContext().getThemeColor(com.google.android.material.R.attr.colorOutline)

        // Follow CalendarActivity pattern
        if (PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            binding.settingsContainer.setPadding(0, 0, 0, 0)
            binding.settingsContainer.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            binding.root.fitsSystemWindows = false
        } else {
            binding.root.fitsSystemWindows = true
        }

        binding.listTabLayout.setBackgroundColor(primaryColor)
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTabLayout.setTabTextColors(secondaryTextColor, primaryTextColor)
        binding.listTabLayout.setSelectedTabIndicatorColor(primaryTextColor)

        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTabIdx = tab?.position ?: 0
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val defaultKeys = listOf(
            "Reading", "Watching", "Completed", "Paused", "Dropped", "Planning",
            "Favourites", "Rewatching", "Rereading", "All"
        )
        val userKeys: Array<String> = resources.getStringArray(R.array.keys)

        model.getLists().observe(viewLifecycleOwner) { it ->
            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                if (!viewPagerAttached) {
                    binding.listViewPager.adapter = ListViewPagerAdapter(it.size, false, requireActivity())
                    val keys = it.keys.toList()
                        .map { key -> userKeys.getOrNull(defaultKeys.indexOf(key)) ?: key }
                    val values = it.values.toList()
                    TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
                        tab.text = "${keys[position]} (${values[position].size})"
                    }.attach()
                    viewPagerAttached = true
                    binding.listViewPager.setCurrentItem(selectedTabIdx, false)
                }
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(viewLifecycleOwner) {
            if (it) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(true, Anilist.userid ?: 0)
                    }
                    live.postValue(false)
                }
            }
        }

        // Fix dpad chain: settings/profile pill/avatar → tabs → pager grid.
        // XML already sets nextFocusDown to tabLayout, but ensure avatar bar is reachable.
        binding.listSettings.nextFocusDownId = R.id.listTabLayout
        binding.profileButton.nextFocusDownId = R.id.listTabLayout
        binding.listAvatar.nextFocusDownId = R.id.listTabLayout
        binding.listTabLayout.nextFocusUpId = R.id.profileButton
        binding.listTabLayout.nextFocusDownId = R.id.listViewPager
        binding.listViewPager.nextFocusUpId = R.id.listTabLayout

        // Profile pill: solid fill with the label inverted against it, so it stays the
        // highest-contrast thing in the bar in either theme.
        styleProfilePill()
        // applyFocusListener draws the border only while the pill holds focus, so it shows up
        // for D-pad navigation and stays off under a finger.
        FocusEffectUtil.applyFocusListener(binding.profileButton)
        binding.profileButton.setOnClickListener {
            openProfile()
        }

        // Settings: bottom sheet with sort / genre / 18+ toggles
        FocusEffectUtil.applyFocusListener(binding.listSettings)
        binding.listSettings.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.listSettings)
            val genres = PrefManager.getVal<Set<String>>(PrefName.GenresList).toMutableSet().sorted()
            LibrarySettingsBottomSheet.newInstance(
                currentSort = PrefManager.getVal<String>(PrefName.AnimeListSortOrder),
                filterItems = genres.ifEmpty { listOf("All") },
                onSortChanged = { sort ->
                    reloadWithSort(sort)
                },
                onGenreFilterChanged = { genre ->
                    binding.listProgressBar.visibility = View.VISIBLE
                    if (genre.isBlank()) {
                        model.unfilterLists()
                        model.filterLists("All")
                    } else {
                        model.filterLists(genre)
                    }
                    binding.listProgressBar.visibility = View.GONE
                },
                onNsfwChanged = { enabled ->
                    binding.listProgressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            model.loadLists(true, Anilist.userid ?: 0)
                        }
                        binding.listProgressBar.visibility = View.GONE
                    }
                }
            ).show(childFragmentManager, LibrarySettingsBottomSheet.TAG)
        }

        // Avatar: opens the right-side rail drawer
        FocusEffectUtil.applyFocusListener(binding.listAvatar)
        val avatarUrl = Anilist.avatar ?: Simkl.avatar
        if (!avatarUrl.isNullOrBlank()) {
            binding.listAvatar.loadImage(avatarUrl)
        }
        binding.listAvatar.setOnClickListener {
            FocusEffectUtil.spinOnTouch(binding.listAvatar)
            openProfile()
        }
    }

    /** Solid pill behind the label, with the label colour inverted against it. */
    private fun styleProfilePill() {
        val dark = isDarkTheme()
        val fill = if (dark) Color.WHITE else Color.BLACK
        binding.profileButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(fill)
        }
        binding.profileButton.setTextColor(if (dark) Color.BLACK else Color.WHITE)
    }

    /** Opens the AniList profile for the signed-in user. */
    private fun openProfile() {
        val uid = Anilist.userid
        if (uid != null) {
            startActivity(
                android.content.Intent(requireContext(), ani.sanin.profile.ProfileActivity::class.java)
                    .putExtra("userId", uid)
            )
        } else {
            // No id yet (still logging in, or logged out): the rail at least has the sign-in.
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

    private fun reloadWithSort(sort: String) {
        binding.listProgressBar.visibility = View.VISIBLE
        binding.listViewPager.adapter = null
        viewPagerAttached = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                model.loadLists(true, Anilist.userid ?: 0, sort)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        viewPagerAttached = false
    }
}
