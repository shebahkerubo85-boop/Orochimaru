package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.databinding.ActivityTmdbAllServicesBinding
import ani.sanin.getThemeColor
import ani.sanin.hideSystemBarsExtendView
import ani.sanin.initActivity
import ani.sanin.isTvDevice
import ani.sanin.setSafeOnClickListener
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Every service the region has, as a grid — the destination of the rail's See All card.
 *
 * This is the app's streaming services screen rather than a new one in kind: the tiles
 * are the same [StreamingServicesAdapter] ones, the brand tint included, so the rail and
 * this grid cannot drift apart. Only the shape differs, so the See All card is dropped
 * (there is nothing further to see) and the cap is lifted (the point of the screen is
 * the long tail).
 */
class TmdbAllServicesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTmdbAllServicesBinding
    private lateinit var scope: CoroutineScope

    private companion object {
        /** Zangetsu's grid side padding, matching the recycler view's own. */
        const val GRID_SIDE_PADDING_DP = 32f

        /** Zangetsu's SliverGridDelegateWithMaxCrossAxisExtent. */
        const val GRID_MAX_EXTENT_DP = 150f

        /** Zangetsu's crossAxisSpacing. */
        const val GRID_CROSS_SPACING_DP = 12f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbAllServicesBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.allServicesTitle.setTextColor(
            getThemeColor(com.google.android.material.R.attr.colorPrimary)
        )

        // The title doubles as the back button, the way the other detail screens do it.
        binding.allServicesTitle.setSafeOnClickListener { finish() }

        scope = CoroutineScope(Dispatchers.IO)
        setUpGrid()
        load()
    }

    private fun setUpGrid() {
        // The arrow-mark screens (Continue Watching and friends) size their grid from the
        // screen rather than a fixed count, and carry a grid/list pair in the app bar. This
        // screen is one of them, so the shape is theirs.
        val mode = PrefManager.getCustomVal("mediaView", 0)
        fun changeView(newMode: Int) {
            binding.mediaList.alpha = if (newMode == 1) 1f else 0.33f
            binding.mediaGrid.alpha = if (newMode == 1) 0.33f else 1f
            PrefManager.setCustomVal("mediaView", newMode)
            binding.allServicesRecyclerView.layoutManager =
                GridLayoutManager(this, if (newMode == 1) 1 else gridSpanCount())
        }
        binding.mediaList.setOnClickListener { changeView(1) }
        binding.mediaGrid.setOnClickListener { changeView(0) }
        changeView(if (mode == 1) 1 else 0)

        binding.allServicesRecyclerView.adapter = StreamingServicesAdapter(
            isTv = isTvDevice(this),
            onServiceClick = { provider -> openCatalogue(provider) },
            onSeeAll = { },
            showSeeAll = false,
            limit = Int.MAX_VALUE
        )
    }

    /**
     * Zangetsu's service grid, in both of its shapes: six across on a TV, and on a phone
     * a max-extent grid that comes out at three columns around 360dp.
     *
     * The arithmetic this replaces divided the screen width by a 128dp plate and truncated
     * the quotient, which floored a 360dp phone to two columns and then stranded each
     * 46dp tile at the start of a half-screen cell. A max-extent grid has to round the
     * count up, because it is a ceiling on how wide a column may get rather than a guess
     * at how wide one is.
     */
    private fun gridSpanCount(): Int {
        if (isTvDevice(this)) return 6
        val screenWidthDp = resources.displayMetrics.run { widthPixels / density }
        val availableDp = screenWidthDp - GRID_SIDE_PADDING_DP
        val columnDp = GRID_MAX_EXTENT_DP + GRID_CROSS_SPACING_DP
        return ceil(availableDp / columnDp).toInt().coerceAtLeast(2)
    }

    private fun load() {
        scope.launch {
            val result = runCatching { Tmdb.watchProviders() }
            withContext(Dispatchers.Main) {
                result.onSuccess { providers ->
                    (binding.allServicesRecyclerView.adapter as? StreamingServicesAdapter)
                        ?.submit(providers)
                }.onFailure {
                    Snackbar.make(binding.root, R.string.error, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * The same hand-off the rail's tiles make, so a service behaves identically whether
     * it was tapped in the rail or in this grid.
     */
    private fun openCatalogue(provider: TmdbProvider) {
        startActivity(
            Intent(this, TmdbServiceCatalogueActivity::class.java)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_ID, provider.id)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_NAME, provider.displayName)
                .putExtra(TmdbServiceCatalogueActivity.ARG_MEDIA_TYPE, "movie")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
