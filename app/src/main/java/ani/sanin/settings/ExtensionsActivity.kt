package ani.sanin.settings

import android.os.Bundle
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

        setupTabs()

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    binding.searchViewText.setText("")
                    binding.searchViewText.clearFocus()
                    tabLayout.clearFocus()
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
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
