package ani.sanin.settings

import android.os.Bundle
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.cloudstream.CloudStreamAvailableFragment
import ani.sanin.cloudstream.CloudStreamInstalledFragment
import ani.sanin.cloudstream.CsRepos
import ani.sanin.databinding.ActivityExtensionsBinding
import ani.sanin.initActivity
import ani.sanin.media.MediaType
import ani.sanin.navBarHeight
import ani.sanin.others.AndroidBug5497Workaround
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.TvKeyboardUtil
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding

    private var cloudStreamMode = false
    private var tabMediator: TabLayoutMediator? = null

    /**
     * Intercept DPAD DOWN when the ViewPager2 is focused so it reaches the
     * Browse button instead of triggering ViewPager2's horizontal scroll.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val vp = binding.viewPager
            // Intercept DPAD DOWN from either the ViewPager or the TabLayout
            // so focus always lands on the first Browse button instead of
            // being swallowed by ViewPager2 scroll or TabLayout navigation.
            val focused = currentFocus
            var tabFocused = false
            var p: android.view.ViewParent? = focused?.parent
            while (p != null) {
                if (p === binding.tabLayout) { tabFocused = true; break }
                p = p.parent as? android.view.ViewParent
            }
            if (vp.isFocused || tabFocused) {
                focusFirstBrowseButton(vp)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight
                }
            } else {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight + navBarHeight
                }
            }
        }

        binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = statusBarHeight + navBarHeight
        }

        FocusEffectUtil.applyFocusListener(binding.aniyomiChip)
        FocusEffectUtil.applyFocusListener(binding.cloudstreamChip)
        binding.aniyomiChip.setOnCheckedChangeListener { _, checked ->
            if (checked) switchMode(false)
        }
        binding.cloudstreamChip.setOnCheckedChangeListener { _, checked ->
            if (checked) switchMode(true)
        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.offscreenPageLimit = 1

        // When the ViewPager2 gains focus (e.g. from search bar UP or tab DOWN),
        // forward it into the Browse button of the first repo row
        viewPager.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) focusFirstBrowseButton(viewPager)
        }

        setupTabs()

        // ViewPager2 internally sets FOCUS_BLOCK_DESCENDANTS on its child
        // RecyclerView, which prevents DPAD focus from reaching Browse buttons.
        // Override it AFTER adapter setup (setupTabs) since that recreates internals.
        viewPager.post {
            (viewPager.getChildAt(0) as? ViewGroup)?.let { internal ->
                internal.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    binding.searchViewText.setText("")
                    binding.searchViewText.clearFocus()
                    focusFirstBrowseButton(viewPager)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    tabLayout.clearFocus()
                }

                override fun onTabReselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                }
            }
        )

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)

        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentFragment =
                    supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.updateContentBasedOnQuery(s?.toString()?.trim())
                }
            }
        })

        TvKeyboardUtil.setupTvInput(binding.searchViewText)

        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        setupModeButtons()
    }

    /** Focus the first Browse button in the current ViewPager page. */
    private fun focusFirstBrowseButton(viewPager: ViewPager2, attempt: Int = 0) {
        viewPager.postDelayed({
            val currentFragment = supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
            val rv = currentFragment?.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.allExtensionsRecyclerView
            )
            val browseBtn = rv?.findViewHolderForAdapterPosition(0)
                ?.itemView?.findViewById<android.view.View>(R.id.repoBrowseButton)
            if (browseBtn?.requestFocus() == true) return@postDelayed
            // First attempt: retry once more (RecyclerView might not be laid out yet)
            if (attempt == 0) {
                focusFirstBrowseButton(viewPager, 1)
            } else {
                rv?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }, if (attempt == 0) 150 else 100)
    }

    private fun switchMode(cloudStream: Boolean) {
        if (cloudStreamMode == cloudStream) return
        cloudStreamMode = cloudStream
        binding.searchViewText.setText("")
        binding.searchViewText.clearFocus()
        setupTabs()
        setupModeButtons()
    }

    private fun setupTabs() {
        tabMediator?.detach()
        tabMediator = null
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                return if (cloudStreamMode) {
                    when (position) {
                        0 -> CloudStreamInstalledFragment()
                        else -> CloudStreamAvailableFragment()
                    }
                } else {
                    when (position) {
                        0 -> InstalledAnimeExtensionsFragment()
                        else -> AnimeExtensionsFragment()
                    }
                }
            }
        }
        tabMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (cloudStreamMode) {
                when (position) {
                    0 -> "Installed Extensions"
                    else -> "Available Extensions"
                }
            } else {
                when (position) {
                    0 -> "Installed Anime"
                    else -> "Available Anime"
                }
            }
        }
        tabMediator?.attach()
        // Re-apply focus override after adapter re-attach (mode switch recreates internals)
        viewPager.post {
            (viewPager.getChildAt(0) as? ViewGroup)?.let { internal ->
                internal.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
        }
    }

    private fun setupModeButtons() {
        binding.openSettingsButton.setOnClickListener {
            val repos = if (cloudStreamMode) {
                CsRepos.repos().toList()
            } else {
                PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toList()
            }
            AddRepositoryBottomSheet.newInstance(
                MediaType.ANIME,
                repos,
                { input, _ -> AddRepositoryBottomSheet.addRepo(input, MediaType.ANIME, cloudStreamMode) },
                { input, _ -> AddRepositoryBottomSheet.removeRepo(input, MediaType.ANIME, cloudStreamMode) },
                cloudStreamMode
            ).show(supportFragmentManager, "add_repo")
        }
        FocusEffectUtil.applyFocusListener(binding.openSettingsButton)
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
