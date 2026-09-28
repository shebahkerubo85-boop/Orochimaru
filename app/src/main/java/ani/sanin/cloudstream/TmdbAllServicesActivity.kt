package ani.sanin.cloudstream

import android.content.Intent
import android.content.res.Configuration
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

        /** Zangetsu's crossAxisSpacing: 12 on a phone, 16 on a TV. */
        const val GRID_CROSS_SPACING_DP = 12f
        const val GRID_CROSS_SPACING_DP_TV = 16f

        /** Zangetsu's mainAxisSpacing: 14 on a phone, 16 on a TV. */
        const val GRID_MAIN_SPACING_DP = 14f
        const val GRID_MAIN_SPACING_DP_TV = 16f

        /** Zangetsu's childAspectRatio of 128/92, so a cell's height follows its width. */
        const val GRID_TILE_RATIO_W = 128f
        const val GRID_TILE_RATIO_H = 92f
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
        // The adapter is attached first: applyGrid() hands it the cell size, so a grid that
        // is set up before it exists would leave the tiles on their rail dimensions.
        binding.allServicesRecyclerView.adapter = StreamingServicesAdapter(
            isTv = isTvDevice(this),
            onServiceClick = { provider -> openCatalogue(provider) },
            onSeeAll = { },
            showSeeAll = false,
            limit = Int.MAX_VALUE
        )
        val mode = PrefManager.getCustomVal("mediaView", 0)
        fun changeView(newMode: Int) {
            binding.mediaList.alpha = if (newMode == 1) 1f else 0.33f
            binding.mediaGrid.alpha = if (newMode == 1) 0.33f else 1f
            PrefManager.setCustomVal("mediaView", newMode)
            applyGrid(isGrid = newMode == 0)
        }
        binding.mediaList.setOnClickListener { changeView(1) }
        binding.mediaGrid.setOnClickListener { changeView(0) }
        changeView(if (mode == 1) 1 else 0)
    }

    /**
     * Lays out the grid, and sizes the tiles to the cell they land in.
     *
     * The span alone is not enough: GridLayoutManager does not clamp a child to its cell,
     * so a tile of a fixed width spills into the next column once the cells get narrower
     * than the tile. Zangetsu's grid passes the card double.infinity and lets SliverGrid
     * do the measuring, so the same numbers here have to be measured out by hand.
     *
     * displayMetrics is the source rather than the view, because this runs from
     * onConfigurationChanged before the new layout has been applied.
     */
    private fun applyGrid(isGrid: Boolean) {
        val rv = binding.allServicesRecyclerView
        val adapter = rv.adapter as? StreamingServicesAdapter
        if (!isGrid) {
            adapter?.setGridTileSize(0, 0, 0, 0)
            rv.layoutManager = GridLayoutManager(this, 1)
            return
        }
        val tv = isTvDevice(this)
        val span = gridSpanCount()
        rv.layoutManager = GridLayoutManager(this, span)

        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()
        val cross = dp(if (tv) GRID_CROSS_SPACING_DP_TV else GRID_CROSS_SPACING_DP)
        val main = dp(if (tv) GRID_MAIN_SPACING_DP_TV else GRID_MAIN_SPACING_DP)
        val sidePadding = dp(GRID_SIDE_PADDING_DP)
        val availablePx = resources.displayMetrics.widthPixels - sidePadding
        val cellPx = availablePx / span
        val tileWidthPx = cellPx - cross
        val tileHeightPx =
            (tileWidthPx * GRID_TILE_RATIO_H / GRID_TILE_RATIO_W).toInt()
        adapter?.setGridTileSize(tileWidthPx, tileHeightPx, cross, main)
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

    /**
     * This screen declares configChanges, so it is not recreated when the device turns and
     * onCreate does not run again. Without this the portrait grid is kept into landscape
     * and the column count never changes, and because the tile is measured to the cell,
     * the tiles are stale in both dimensions.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyGrid(isGrid = PrefManager.getCustomVal("mediaView", 0) == 0)
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
