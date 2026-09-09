package ani.sanin.settings

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.customAlertDialog

class SettingsAppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsSubscreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = "Appearance"
        binding.subscreenSubtitle.text = "Colors, cards, blur & glass"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_theme)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Theme & Palette", R.drawable.ic_set_theme,
                defaultExpanded = true,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Light or Dark",
                        desc = "Choose the base theme",
                        choice = SubscreenBuilder.Choice(
                            title = "Light or Dark",
                            options = arrayOf("Light", "Dark"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.DarkMode),
                        ) { idx ->
                            PrefManager.setVal(PrefName.DarkMode, idx)
                            AppCompatDelegate.setDefaultNightMode(
                                if (idx == 1) AppCompatDelegate.MODE_NIGHT_YES
                                else AppCompatDelegate.MODE_NIGHT_NO
                            )
                            recreate()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "OLED Background",
                        desc = "Deep black or dark surface style",
                        choice = SubscreenBuilder.Choice(
                            title = "OLED Background",
                            options = arrayOf("Off", "Pure AMOLED", "Glow Spots", "Gradient", "Vignette"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.OledMode),
                        ) { idx -> PrefManager.setVal(PrefName.OledMode, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Accent Tint",
                        desc = "App-wide color accent",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showAccentColorPicker() },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Home Screen", R.drawable.ic_set_home,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Banner Style",
                        desc = "How the home banner behaves",
                        choice = SubscreenBuilder.Choice(
                            title = "Banner Style",
                            options = arrayOf(
                                getString(R.string.home_banner_carousel),
                                getString(R.string.home_banner_profile),
                                getString(R.string.home_banner_navigating),
                                getString(R.string.home_banner_off),
                            ),
                            currentIndex = PrefManager.getVal<Int>(PrefName.HomeBannerMode),
                        ) { idx -> PrefManager.setVal(PrefName.HomeBannerMode, idx) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Hero Artwork",
                        desc = "Show artwork in the hero card",
                        switch = PrefManager.getVal<Boolean>(PrefName.HeroCardImage) to {
                            PrefManager.setVal(PrefName.HeroCardImage, it)
                        },
                    ),
                    SubscreenBuilder.Entry(title = "Continue Watching", switch = restartSwitch(PrefName.ShowContinueWatching)),
                    SubscreenBuilder.Entry(title = "Planned", switch = restartSwitch(PrefName.ShowPlanned)),
                    SubscreenBuilder.Entry(title = "Recommendations", switch = restartSwitch(PrefName.ShowRecommendations)),
                    SubscreenBuilder.Entry(title = "Trending", switch = restartSwitch(PrefName.ShowTrending)),
                    SubscreenBuilder.Entry(title = "Popular", switch = restartSwitch(PrefName.ShowPopular)),
                    SubscreenBuilder.Entry(title = "Recent", switch = restartSwitch(PrefName.ShowRecent)),
                ),
            ),

            SubscreenBuilder.Section(
                "Card Design", R.drawable.ic_set_cards,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Scale",
                        desc = "How big cards appear",
                        choice = floatChoice(
                            "Scale", arrayOf("Tiny (0.5×)", "Small (0.75×)", "Default (1×)", "Big (1.25×)", "Large (1.5×)", "XL (1.75×)", "XXL (2.0×)"),
                            floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
                            PrefManager.getVal<Float>(PrefName.CardSize),
                        ) { PrefManager.setVal(PrefName.CardSize, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Shape",
                        desc = "Rounded or compact",
                        choice = SubscreenBuilder.Choice(
                            title = "Shape", options = arrayOf("Rounded", "Compact"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CardStyle).coerceAtMost(1),
                        ) { idx -> PrefManager.setVal(PrefName.CardStyle, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Orientation",
                        desc = "Landscape or portrait cards",
                        choice = SubscreenBuilder.Choice(
                            title = "Orientation", options = arrayOf("Landscape", "Portrait"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CardOrientation),
                        ) { idx -> PrefManager.setVal(PrefName.CardOrientation, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Title Placement",
                        desc = "Where the card title sits",
                        choice = SubscreenBuilder.Choice(
                            title = "Title Placement", options = arrayOf("Overlay Bottom", "Below Card", "Hidden"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.CardTitlePosition),
                        ) { idx -> PrefManager.setVal(PrefName.CardTitlePosition, idx); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Corner Radius",
                        desc = "Roundness of standard cards",
                        choice = intChoice(
                            "Corner Radius",
                            arrayOf("Sharp (0)", "Slight (20)", "Soft (40)", "Standard (50)", "Round (60)", "Very Round (80)", "Pill (100)"),
                            intArrayOf(0, 20, 40, 50, 60, 80, 100),
                            PrefManager.getVal<Int>(PrefName.StandardCardRoundness),
                        ) { PrefManager.setVal(PrefName.StandardCardRoundness, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Continue-Watch Radius",
                        desc = "Roundness of the continue card",
                        choice = intChoice(
                            "Continue-Watch Radius",
                            arrayOf("Sharp (0)", "Slight (20)", "Soft (40)", "Standard (50)", "Round (60)", "Very Round (80)", "Pill (100)"),
                            intArrayOf(0, 20, 40, 50, 60, 80, 100),
                            PrefManager.getVal<Int>(PrefName.ContinueWatchingCardRoundness),
                        ) { PrefManager.setVal(PrefName.ContinueWatchingCardRoundness, it); restartApp() },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Hide Notification Dot",
                        desc = "Remove the red badge on bell icon",
                        switch = !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot) to {
                            PrefManager.setVal(PrefName.ShowNotificationRedDot, !it)
                        },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Banner & Blur", R.drawable.ic_set_blur,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Blur Banners",
                        desc = "Apply blur to home banners",
                        switch = PrefManager.getVal<Boolean>(PrefName.BlurBanners) to {
                            PrefManager.setVal(PrefName.BlurBanners, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Blur Radius",
                        desc = "How strong the blur is",
                        choice = floatChoice("Blur Radius",
                            arrayOf("1", "2", "4", "6", "8", "10", "15", "20", "25", "30"),
                            floatArrayOf(1f, 2f, 4f, 6f, 8f, 10f, 15f, 20f, 25f, 30f),
                            PrefManager.getVal<Float>(PrefName.BlurRadius),
                        ) { PrefManager.setVal(PrefName.BlurRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Down-sample",
                        desc = "Performance vs quality trade-off",
                        choice = floatChoice("Down-sample",
                            arrayOf("1×", "2×", "3×", "4×", "5×", "6×", "8×"),
                            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 8f),
                            PrefManager.getVal<Float>(PrefName.BlurSampling),
                        ) { PrefManager.setVal(PrefName.BlurSampling, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Banner Darkness",
                        desc = "Dark overlay on banners",
                        choice = floatChoice("Banner Darkness",
                            arrayOf("None", "10%", "15%", "20%", "25%", "30%", "40%", "50%", "60%", "80%"),
                            floatArrayOf(0f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.40f, 0.50f, 0.60f, 0.80f),
                            PrefManager.getVal<Float>(PrefName.BannerBrightness),
                        ) { PrefManager.setVal(PrefName.BannerBrightness, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Gradient Strength",
                        desc = "How visible the card gradient is",
                        choice = floatChoice("Gradient Strength",
                            arrayOf("None", "10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "Full"),
                            floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.CardGradientIntensity),
                        ) { PrefManager.setVal(PrefName.CardGradientIntensity, it) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Glass Layers", R.drawable.ic_set_glass,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Enable Glass",
                        desc = "Master toggle for glassmorphism",
                        switch = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled) to {
                            PrefManager.setVal(PrefName.GlassEffectEnabled, it); restartApp()
                        },
                    ),
                    SubscreenBuilder.Entry(title = "Nav Pills", switch = glassSwitch(PrefName.GlassEffectNavPills)),
                    SubscreenBuilder.Entry(title = "Side Rail", switch = glassSwitch(PrefName.GlassEffectSideRail)),
                    SubscreenBuilder.Entry(title = "Server Sheet", switch = glassSwitch(PrefName.GlassEffectServerSheet)),
                    SubscreenBuilder.Entry(title = "List Editor", switch = glassSwitch(PrefName.GlassEffectListEditor)),
                    SubscreenBuilder.Entry(title = "Source Picker", switch = glassSwitch(PrefName.GlassEffectSourceSelector)),
                    SubscreenBuilder.Entry(title = "Episode Drawer", switch = glassSwitch(PrefName.GlassEffectEpisodeDrawer)),
                    SubscreenBuilder.Entry(title = "Subtitle Sync", switch = glassSwitch(PrefName.GlassEffectSubtitleSync)),
                    SubscreenBuilder.Entry(title = "Keyboard", switch = glassSwitch(PrefName.GlassEffectKeyboard)),
                ),
            ),

            SubscreenBuilder.Section(
                "Glass Tuning", R.drawable.ic_set_glass,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Blur Amount", desc = "How blurry the glass is",
                        choice = floatChoice("Blur Amount",
                            arrayOf("5", "10", "15", "20", "25", "30", "40", "50", "60", "80"),
                            floatArrayOf(5f, 10f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 80f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectBlurRadius),
                        ) { PrefManager.setVal(PrefName.GlassEffectBlurRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Tint Strength", desc = "Color overlay opacity",
                        choice = floatChoice("Tint Strength",
                            arrayOf("10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "Full"),
                            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectTintOpacity),
                        ) { PrefManager.setVal(PrefName.GlassEffectTintOpacity, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Color Pop", desc = "Vibrancy boost",
                        choice = floatChoice("Color Pop",
                            arrayOf("Flat", "Subtle", "Soft", "Normal", "Bright", "Vivid", "Punchy", "Intense"),
                            floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 1.0f, 1.25f, 1.5f, 2.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectVibrancy),
                        ) { PrefManager.setVal(PrefName.GlassEffectVibrancy, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Height", choice = floatChoice("Refraction Height",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionHeight),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionHeight, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Amount", choice = floatChoice("Refraction Amount",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionAmount),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionAmount, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Fringe Effect", desc = "Chromatic aberration at edges",
                        choice = floatChoice("Fringe Effect",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectChromaticAberration),
                        ) { PrefManager.setVal(PrefName.GlassEffectChromaticAberration, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "3D Depth", desc = "Parallax depth on glass",
                        switch = PrefManager.getVal<Boolean>(PrefName.GlassEffectDepth) to {
                            PrefManager.setVal(PrefName.GlassEffectDepth, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Surface Tint", desc = "Color overlay on glass",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Surface Tint", PrefName.GlassEffectSurfaceTint) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Text on Glass", desc = "Text color over glass",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Text on Glass", PrefName.GlassEffectTextColor) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Side Rail & Focus", R.drawable.ic_set_nav,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Pin Side Rail", desc = "Keep rail visible on all screens",
                        switch = PrefManager.getVal<Boolean>(PrefName.SideRailPersist) to {
                            PrefManager.setVal(PrefName.SideRailPersist, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Auto-Hide Rail", desc = "Hide rail while scrolling",
                        switch = PrefManager.getVal<Boolean>(PrefName.SideRailAutoOrientation) to {
                            PrefManager.setVal(PrefName.SideRailAutoOrientation, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Focus Style", desc = "Visual ring on focused items",
                        choice = SubscreenBuilder.Choice(
                            title = "Focus Style",
                            options = arrayOf("Glow", "Breathing", "Pulse", "Shaking", "None"),
                            currentIndex = PrefManager.getVal<Int>(PrefName.FocusEffect),
                        ) { idx -> PrefManager.setVal(PrefName.FocusEffect, idx) },
                    ),
                ),
            ),

            SubscreenBuilder.Section(
                "Navigation Pills", R.drawable.ic_set_nav,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Pill Height", desc = "Button height",
                        choice = intChoice("Pill Height",
                            arrayOf("36dp", "42dp", "48dp", "52dp", "58dp", "64dp", "72dp"),
                            intArrayOf(36, 42, 48, 52, 58, 64, 72),
                            PrefManager.getVal<Int>(PrefName.NavPillHeight).coerceIn(36, 72),
                        ) { PrefManager.setVal(PrefName.NavPillHeight, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Pill Width", desc = "Button width",
                        choice = intChoice("Pill Width",
                            arrayOf("36dp", "42dp", "48dp", "52dp", "58dp", "64dp", "72dp"),
                            intArrayOf(36, 42, 48, 52, 58, 64, 72),
                            PrefManager.getVal<Int>(PrefName.NavPillWidth).coerceIn(36, 72),
                        ) { PrefManager.setVal(PrefName.NavPillWidth, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Gap Between", desc = "Space between buttons",
                        choice = intChoice("Gap Between",
                            arrayOf("Tight (12dp)", "Compact (16dp)", "Default (20dp)", "Relaxed (26dp)", "Spacious (32dp)", "Wide (40dp)"),
                            intArrayOf(12, 16, 20, 26, 32, 40),
                            PrefManager.getVal<Int>(PrefName.NavPillSpacing).coerceIn(12, 40),
                        ) { PrefManager.setVal(PrefName.NavPillSpacing, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Icon Size", desc = "Size of icons inside pills",
                        choice = intChoice("Icon Size",
                            arrayOf("Tiny (12dp)", "Small (16dp)", "Medium (20dp)", "Default (23dp)", "Large (28dp)"),
                            intArrayOf(12, 16, 20, 23, 28),
                            PrefManager.getVal<Int>(PrefName.NavPillIconSize).coerceIn(8, 28),
                        ) { PrefManager.setVal(PrefName.NavPillIconSize, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Corner Round", desc = "How round the pill corners are",
                        choice = intChoice("Corner Round",
                            arrayOf("Square (0)", "Slight (6)", "Soft (12)", "Default (18)", "Round (24)", "Very Round (32)", "Full Pill (48)"),
                            intArrayOf(0, 6, 12, 18, 24, 32, 48),
                            PrefManager.getVal<Int>(PrefName.NavPillCornerRadius).coerceIn(0, 48),
                        ) { PrefManager.setVal(PrefName.NavPillCornerRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Icon Tint", desc = "Color of pill icons",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Icon Tint", PrefName.NavPillIconColor) },
                    ),
                ),
            ),
        ))
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun restartSwitch(pref: PrefName): Pair<Boolean, (Boolean) -> Unit> =
        PrefManager.getVal<Boolean>(pref) to { v: Boolean -> PrefManager.setVal(pref, v); restartApp() }

    private fun glassSwitch(pref: PrefName) =
        PrefManager.getVal<Boolean>(pref) to { PrefManager.setVal(pref, it) }

    private fun floatChoice(title: String, labels: Array<String>, values: FloatArray, current: Float, onSelect: (Float) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        ) { idx -> onSelect(values[idx]) }

    private fun intChoice(title: String, labels: Array<String>, values: IntArray, current: Int, onSelect: (Int) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        ) { idx -> onSelect(values[idx]) }

    // ─── Color Pickers ──────────────────────────────────────────

    private fun showAccentColorPicker() {
        val labels = arrayOf("Sanin", "Ocean", "Blood", "Lime", "Sun", "Kurama", "Saikou", "Indigo", "Monochrome")
        customAlertDialog().apply {
            setTitle("Accent Tint")
            singleChoiceItems(labels, PrefManager.getVal<Int>(PrefName.AccentColor)) { idx ->
                PrefManager.setVal(PrefName.AccentColor, idx); restartApp()
            }
            show()
        }
    }

    private val paletteColors = intArrayOf(
        Color.WHITE, Color.BLACK, Color.RED, Color.parseColor("#FF9800"),
        Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
        Color.parseColor("#9C27B0"), Color.parseColor("#E91E63"),
        Color.GRAY, Color.parseColor("#607D8B"), Color.parseColor("#795548"),
        Color.parseColor("#4CAF50"), Color.parseColor("#03A9F4"), Color.parseColor("#FF5722"),
    )
    private val paletteNames = arrayOf(
        "White", "Black", "Red", "Orange", "Yellow", "Green",
        "Cyan", "Blue", "Purple", "Pink", "Grey", "Blue Grey",
        "Brown", "Green 500", "Light Blue", "Deep Orange",
    )

    private fun showColorGrid(title: String, pref: PrefName) {
        val dp = resources.displayMetrics.density
        val chipSize = (48 * dp).toInt()
        val margin = (4 * dp).toInt()
        val grid = GridLayout(this).apply {
            columnCount = 4; rowCount = (paletteColors.size + 3) / 4
            setPadding(margin, margin, margin, margin)
        }
        for (i in paletteColors.indices) {
            val chip = ImageButton(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(paletteColors[i])
                    setStroke(if (paletteColors[i] == Color.BLACK) 2 else 0,
                        if (paletteColors[i] == Color.BLACK) Color.GRAY else Color.TRANSPARENT)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = chipSize; height = chipSize; setMargins(margin, margin, margin, margin)
                }
                contentDescription = paletteNames[i]; isFocusable = true
            }
            grid.addView(chip)
        }
        AlertDialog.Builder(this).setTitle(title).setView(grid)
            .setNegativeButton("Cancel", null).show().also { dialog ->
                for (i in 0 until grid.childCount) {
                    grid.getChildAt(i).setOnClickListener {
                        PrefManager.setVal(pref, paletteColors[i]); dialog.dismiss()
                    }
                }
            }
    }
}
