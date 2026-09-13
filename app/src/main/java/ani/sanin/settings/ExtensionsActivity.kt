package ani.sanin.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import ani.sanin.getThemeColor
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
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK &&
            searchExpanded
        ) {
            collapseSearchBar()
            return true
        }
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
        AndroidBug5497Workaround.assistActivity(this) { }

        // Segmented mode toggle: Aniyomi (checked) <-> CloudStream.
        binding.modeToggleGroup.check(R.id.modeButtonAniyomi)
        FocusEffectUtil.applyFocusListener(binding.modeButtonAniyomi, binding.modeButtonCloudstream)
        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) switchMode(checkedId == R.id.modeButtonCloudstream)
        }
        styleModeButtons()

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
                    // Repo-card tabs (Available) have no search — icon + bar
                    // only exist on the Installed extension lists.
                    updateSearchUiForTab(tab.position)
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

        FocusEffectUtil.applyFocusListener(binding.searchIconButton)
        binding.searchIconButton.setOnClickListener { toggleSearchBar() }
        updateSearchUiForTab(0)

        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        setupModeButtons()
    }

    /** Search only exists on Installed tabs (position 0): icon + expandable bar. */
    private fun updateSearchUiForTab(tabPosition: Int) {
        val installed = tabPosition == 0
        binding.searchIconButton.visibility = if (installed) View.VISIBLE else View.GONE
        if (!installed) collapseSearchBar()
    }

    private var searchExpanded = false

    private fun toggleSearchBar() {
        if (searchExpanded) collapseSearchBar() else expandSearchBar()
    }

    private fun expandSearchBar() {
        if (searchExpanded) return
        searchExpanded = true
        binding.searchIconButton.setImageResource(R.drawable.ic_round_close_24)
        val heightPx = (56 * resources.displayMetrics.density).toInt()
        binding.searchView.visibility = View.VISIBLE
        binding.searchView.alpha = 0f
        val anim = ValueAnimator.ofInt(0, heightPx).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                binding.searchView.layoutParams.height = it.animatedValue as Int
                binding.searchView.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.searchView.alpha = 1f
                    binding.searchViewText.requestFocus()
                }
            })
        }
        anim.start()
    }

    private fun collapseSearchBar() {
        if (!searchExpanded) return
        searchExpanded = false
        binding.searchIconButton.setImageResource(R.drawable.ic_round_search_24)
        binding.searchViewText.clearFocus()
        val startHeight = binding.searchView.layoutParams.height
        val anim = ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                binding.searchView.layoutParams.height = it.animatedValue as Int
                binding.searchView.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.searchView.visibility = View.GONE
                    binding.searchView.layoutParams.height = 0
                }
            })
        }
        anim.start()
    }

    /** Segmented toggle: selected side = primary fill + white text, other neutral. */
    private fun styleModeButtons() {
        val primary = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = getThemeColor(com.google.android.material.R.attr.colorOutline)
        val density = resources.displayMetrics.density
        fun style(btn: com.google.android.material.button.MaterialButton, active: Boolean) {
            btn.backgroundTintList = ColorStateList.valueOf(if (active) primary else Color.TRANSPARENT)
            btn.setTextColor(if (active) Color.WHITE else onSurface)
            btn.strokeColor = ColorStateList.valueOf(if (active) primary else outline)
            btn.strokeWidth = if (active) 0 else (1 * density).toInt()
        }
        style(binding.modeButtonAniyomi, !cloudStreamMode)
        style(binding.modeButtonCloudstream, cloudStreamMode)
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
        collapseSearchBar()
        binding.searchViewText.setText("")
        binding.searchViewText.clearFocus()
        styleModeButtons()
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
                    0 -> "Installed Plugins"
                    else -> "Available Plugins"
                }
            } else {
                when (position) {
                    0 -> "Installed Extensions"
                    else -> "Available Extensions"
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
