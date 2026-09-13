package ani.sanin.settings

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.google.android.material.materialswitch.MaterialSwitch

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

        buildSections = { SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Theme & Palette", R.drawable.ic_set_theme,
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
                        expandSlider = SubscreenBuilder.ExpandSlider(
                            slider = SubscreenBuilder.SliderOption(
                                value = PrefManager.getVal<Float>(PrefName.OledIntensity) * 100f,
                                valueFrom = 0f,
                                valueTo = 100f,
                                step = 5f,
                                suffix = "%",
                            ) { PrefManager.setVal(PrefName.OledIntensity, it / 100f) },
                            showOnIndex = 0,
                        ),
                    ),
                    SubscreenBuilder.Entry(
                        title = "Accent Tint",
                        desc = "App-wide color accent",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showAccentColorPicker() },
                    ),
                ),
            ),
        ))
            addHomeScreenSection(binding.subscreenContent)
            SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section(
                "Card Design", R.drawable.ic_set_cards,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Scale",
                        desc = "How big cards appear",
                        choice = floatChoice(
                            "Scale", arrayOf("Tiny (1×)", "Small (1.25×)", "Default (1.5×)", "Big (1.75×)", "Large (2×)", "XL (2.25×)", "XXL (2.5×)"),
                            floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f),
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
                            title = "Title Placement", options = arrayOf("Overlay Bottom (Landscape)", "Below Card", "Hidden"),
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
                        title = "Banner Brightness",
                        desc = "Brightness of the info-page banner. 0 keeps a plain surface, 100% shows the art fully.",
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Float>(PrefName.BannerBrightness) * 100f,
                            valueFrom = 0f, valueTo = 100f, step = 1f,
                            suffix = "%"
                        ) { PrefManager.setVal(PrefName.BannerBrightness, it / 100f) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Gradient Intensity",
                        desc = "How dark the gradient behind the card title is. Lower looks cleaner.",
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Float>(PrefName.CardGradientIntensity) * 100f,
                            valueFrom = 0f, valueTo = 100f, step = 1f,
                            suffix = "%"
                        ) { PrefManager.setVal(PrefName.CardGradientIntensity, it / 100f) },
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
                            PrefManager.setVal(PrefName.GlassEffectEnabled, it); rebuildSubscreen()
                        },
                    ),
                    SubscreenBuilder.Entry(title = "Nav Pills", switch = glassSwitch(PrefName.GlassEffectNavPills), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Side Rail", switch = glassSwitch(PrefName.GlassEffectSideRail), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Server Sheet", switch = glassSwitch(PrefName.GlassEffectServerSheet), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "List Editor", switch = glassSwitch(PrefName.GlassEffectListEditor), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Source Picker", switch = glassSwitch(PrefName.GlassEffectSourceSelector), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Episode Drawer", switch = glassSwitch(PrefName.GlassEffectEpisodeDrawer), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Subtitle Sync", switch = glassSwitch(PrefName.GlassEffectSubtitleSync), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                    SubscreenBuilder.Entry(title = "Keyboard", switch = glassSwitch(PrefName.GlassEffectKeyboard), isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled)),
                ),
            ),

            SubscreenBuilder.Section(
                "Glass Tuning", R.drawable.ic_set_glass,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Blur Amount", desc = "How blurry the glass is", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled),
                        choice = floatChoice("Blur Amount",
                            arrayOf("5", "10", "15", "20", "25", "30", "40", "50", "60", "80"),
                            floatArrayOf(5f, 10f, 15f, 20f, 25f, 30f, 40f, 50f, 60f, 80f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectBlurRadius),
                        ) { PrefManager.setVal(PrefName.GlassEffectBlurRadius, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Tint Strength", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Color overlay opacity",
                        choice = floatChoice("Tint Strength",
                            arrayOf("10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "Full"),
                            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectTintOpacity),
                        ) { PrefManager.setVal(PrefName.GlassEffectTintOpacity, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Color Pop", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Vibrancy boost",
                        choice = floatChoice("Color Pop",
                            arrayOf("Flat", "Subtle", "Soft", "Normal", "Bright", "Vivid", "Punchy", "Intense"),
                            floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 1.0f, 1.25f, 1.5f, 2.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectVibrancy),
                        ) { PrefManager.setVal(PrefName.GlassEffectVibrancy, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Height", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), choice = floatChoice("Refraction Height",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionHeight),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionHeight, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Refraction Amount", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), choice = floatChoice("Refraction Amount",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectRefractionAmount),
                        ) { PrefManager.setVal(PrefName.GlassEffectRefractionAmount, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Fringe Effect", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Chromatic aberration at edges",
                        choice = floatChoice("Fringe Effect",
                            arrayOf("Off", "Low", "Medium", "High", "Max"),
                            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1.0f),
                            PrefManager.getVal<Float>(PrefName.GlassEffectChromaticAberration),
                        ) { PrefManager.setVal(PrefName.GlassEffectChromaticAberration, it) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "3D Depth", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Parallax depth on glass",
                        switch = PrefManager.getVal<Boolean>(PrefName.GlassEffectDepth) to {
                            PrefManager.setVal(PrefName.GlassEffectDepth, it)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Surface Tint", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled), desc = "Color overlay on glass",
                        iconRes = R.drawable.ic_set_theme,
                        onClick = { showColorGrid("Surface Tint", PrefName.GlassEffectSurfaceTint) },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Text on Glass", desc = "Text color over glass", isEnabled = PrefManager.getVal<Boolean>(PrefName.GlassEffectEnabled),
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
            SubscreenBuilder.Section(
                "Interface", R.drawable.ic_set_theme,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "UI Scale",
                        desc = "Resize all interface elements",
                        iconRes = R.drawable.ic_set_theme,
                        choice = SubscreenBuilder.Choice(
                            title = "UI Scale",
                            options = arrayOf("0.75x", "0.85x", "1.0x Default", "1.15x", "1.25x"),
                            currentIndex = when (PrefManager.getVal<Float>(PrefName.UIScale)) {
                                0.75f -> 0; 0.85f -> 1; 1.15f -> 3; 1.25f -> 4; else -> 2
                            },
                        ) { idx -> PrefManager.setVal(PrefName.UIScale, floatArrayOf(0.75f, 0.85f, 1f, 1.15f, 1.25f)[idx]) },
                    ),
                ),
            ),
        ), clear = false) }
        buildSections?.invoke()
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private var buildSections: (() -> Unit)? = null

    private fun rebuildSubscreen() {
        buildSections?.invoke()
    }

    private fun glassSwitch(pref: PrefName): Pair<Boolean, (Boolean) -> Unit> =
        PrefManager.getVal<Boolean>(pref) to { v: Boolean -> PrefManager.setVal(pref, v) }

    // ─── Home Screen: toggle + reorder (touch & dpad) ───────────

    private val homeSectionTitles = arrayOf(
        "Continue Watching", "Favorite", "Planned", "Missed Sequels", "Recommended"
    )
    private val homeSectionDescs = arrayOf(
        "Resume where you left off",
        "Your favourite anime",
        "What you plan to watch next",
        "Sequels you haven't caught up on",
        "Picks based on your taste",
    )

    private fun homeOrderList(): MutableList<Int> =
        PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder).toMutableList().apply {
            val valid = filter { it in homeSectionTitles.indices }.distinct()
            clear(); addAll(valid)
            addAll(homeSectionTitles.indices.filterNot { it in valid })
        }

    private fun homeVisibilityList(): MutableList<Boolean> =
        PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout).toMutableList().apply {
            while (size < homeSectionTitles.size) add(true)
            if (size > homeSectionTitles.size) subList(homeSectionTitles.size, size).clear()
        }

    private fun showBannerStyleDialog() {
        customAlertDialog().apply {
            setTitle("Banner Style")
            singleChoiceItems(
                arrayOf(
                    getString(R.string.home_banner_carousel),
                    getString(R.string.home_banner_profile),
                    getString(R.string.home_banner_navigating),
                    getString(R.string.home_banner_off),
                ),
                PrefManager.getVal<Int>(PrefName.HomeBannerMode),
            ) { idx -> PrefManager.setVal(PrefName.HomeBannerMode, idx) }
            show()
        }
    }

    private fun addHomeScreenSection(container: LinearLayout) {
        val inflater = layoutInflater
        val sectionView = inflater.inflate(R.layout.item_settings_section, container, false)
        val header = sectionView.findViewById<LinearLayout>(R.id.sectionHeader)
        val icon = sectionView.findViewById<ImageView>(R.id.sectionIcon)
        val title = sectionView.findViewById<TextView>(R.id.sectionTitle)
        val desc = sectionView.findViewById<TextView>(R.id.sectionDesc)
        val chevron = sectionView.findViewById<ImageView>(R.id.sectionChevron)
        val items = sectionView.findViewById<LinearLayout>(R.id.sectionItems)

        icon.setImageResource(R.drawable.ic_set_home)
        title.text = "Home Screen"
        desc.text = "Show, hide & reorder home feed sections"
        desc.visibility = View.VISIBLE

        // Banner Style row
        val bannerRow = inflater.inflate(R.layout.item_settings_section_entry, items, false)
        bannerRow.findViewById<ImageView>(R.id.entryIcon).setImageResource(R.drawable.ic_set_home)
        bannerRow.findViewById<TextView>(R.id.entryTitle).text = "Banner Style"
        bannerRow.findViewById<TextView>(R.id.entryDesc).apply {
            text = "How the home banner behaves"
            visibility = View.VISIBLE
        }
        FocusEffectUtil.applyFocusListener(bannerRow)
        bannerRow.setOnClickListener { showBannerStyleDialog() }
        items.addView(bannerRow)

        val hintDefault = "Drag to reorder \u2014 hold the arrows and move, or press UP/DOWN"
        val hint = TextView(this).apply {
            text = hintDefault
            setPadding(16, 12, 16, 8)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val tvColor = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tvColor, true)
            setTextColor(tvColor.data)
            alpha = 0.72f
        }

        val order = homeOrderList()
        val visibility = homeVisibilityList()
        val rows = mutableListOf<View>()
        var dragRow: Int? = null
        var keyboardRow: Int? = null

        fun saveOrder() {
            PrefManager.setVal(PrefName.HomeLayoutOrder, order.toList())
        }

        fun tintArrows(row: View, active: Boolean) {
            val tv = TypedValue()
            theme.resolveAttribute(
                if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface,
                tv, true
            )
            row.findViewById<ImageView>(R.id.homeRowUpArrow).setColorFilter(tv.data)
            row.findViewById<ImageView>(R.id.homeRowDownArrow).setColorFilter(tv.data)
        }

        fun refreshArrows() {
            rows.forEachIndexed { pos, row -> tintArrows(row, dragRow == pos || keyboardRow == pos) }
        }

        fun moveRow(fromPos: Int, toPos: Int) {
            if (fromPos == toPos) return
            order.add(toPos, order.removeAt(fromPos))
            val row = rows.removeAt(fromPos)
            rows.add(toPos, row)
            items.removeViewAt(fromPos + 1)   // +1 = skip banner row
            items.addView(row, toPos + 1)
        }

        // TV: BACK while armed dismisses drag mode instead of leaving the screen.
        val dragDisarm = OnBackPressedCallback(false) {
            dragRow = null
            keyboardRow = null
            hint.text = hintDefault
            rows.forEach { it.elevation = 0f }
            refreshArrows()
            saveOrder()
            dragDisarm.isEnabled = false
        }
        onBackPressedDispatcher.addCallback(this, dragDisarm)

        fun updateDragDisarmState() {
            dragDisarm.isEnabled = keyboardRow != null || dragRow != null
        }

        order.forEach { idx ->
            val row = inflater.inflate(R.layout.item_home_section_row, items, false)
            row.findViewById<TextView>(R.id.homeRowTitle).text = homeSectionTitles[idx]
            row.findViewById<TextView>(R.id.homeRowDesc).text = homeSectionDescs[idx]
            val dragHandle = row.findViewById<LinearLayout>(R.id.homeRowDragHandle)
            val switch = row.findViewById<MaterialSwitch>(R.id.homeRowSwitch)
            switch.isChecked = visibility[idx]
            tintArrows(row, false)

            switch.setOnCheckedChangeListener { _, checked ->
                visibility[idx] = checked
                PrefManager.setVal(PrefName.HomeLayout, visibility.toList())
                restartApp()
            }
            row.findViewById<TextView>(R.id.homeRowTitle).setOnClickListener {
                switch.isChecked = !switch.isChecked
            }

            // Click handle -> arm drag mode (TV keeps handle focus, dismiss
            // via BACK or DPAD_RIGHT; phone then drags by touching the row).
            dragHandle.setOnClickListener {
                val pos = rows.indexOf(row)
                if (keyboardRow == pos) {
                    keyboardRow = null
                    hint.text = hintDefault
                    refreshArrows()
                    saveOrder()
                    updateDragDisarmState()
                } else {
                    keyboardRow = pos
                    hint.text = "Press UP/DOWN to reorder, ENTER to confirm"
                    refreshArrows()
                    updateDragDisarmState()
                }
                dragHandle.post { dragHandle.requestFocus() }
            }

            // Dpad reorder while mode active
            dragHandle.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = rows.indexOf(row)
                if (keyboardRow != pos) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (pos > 0) { moveRow(pos, pos - 1); keyboardRow = pos - 1; refreshArrows() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (pos < rows.size - 1) { moveRow(pos, pos + 1); keyboardRow = pos + 1; refreshArrows() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        keyboardRow = null
                        hint.text = hintDefault
                        refreshArrows()
                        saveOrder()
                        updateDragDisarmState()
                        false
                    }
                    else -> false
                }
            }

            // Phone: once armed, touching anywhere on the row drags it — no
            // need to keep holding the handle. Release commits the new order.
            row.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val pos = rows.indexOf(row)
                        if (keyboardRow == pos) {
                            keyboardRow = null
                            dragRow = pos
                            row.elevation = 8f
                            hint.text = "Release to drop"
                            refreshArrows()
                            updateDragDisarmState()
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragRow != null) {
                            val rawY = event.rawY
                            var target = dragRow!!
                            rows.forEach { r ->
                                val arr = IntArray(2)
                                r.getLocationOnScreen(arr)
                                if (rawY >= arr[1] && rawY <= arr[1] + r.height) target = rows.indexOf(r)
                            }
                            if (target != dragRow) {
                                moveRow(dragRow!!, target)
                                dragRow = target
                                refreshArrows()
                            }
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragRow != null) {
                            row.elevation = 0f
                            dragRow = null
                            hint.text = hintDefault
                            refreshArrows()
                            saveOrder()
                            updateDragDisarmState()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    else -> false
                }
            }

            FocusEffectUtil.applyFocusListener(row)
            FocusEffectUtil.applyFocusListener(dragHandle)
            rows.add(row)
            items.addView(row)
        }

        items.addView(hint)

        // Expand/collapse like the other settings cards
        var expanded = false
        fun toggleSection() {
            expanded = !expanded
            ObjectAnimator.ofFloat(
                chevron, "rotation",
                if (expanded) 0f else 180f,
                if (expanded) 180f else 0f
            ).apply { duration = 250; start() }
            if (expanded) {
                items.visibility = View.VISIBLE
                items.animate().alpha(1f).setDuration(200).start()
            } else {
                items.animate().alpha(0f).setDuration(150).withEndAction {
                    items.visibility = View.GONE
                }.start()
            }
        }
        header.setOnClickListener { toggleSection() }
        FocusEffectUtil.applyFocusListener(header)
        chevron.rotation = 0f
        items.visibility = View.GONE

        container.addView(sectionView)
    }

    private fun floatChoice(title: String, labels: Array<String>, values: FloatArray, current: Float, onSelect: (Float) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { idx -> kotlin.math.abs(values[idx] - current) } ?: 0
        ) { idx -> onSelect(values[idx]) }

    private fun intChoice(title: String, labels: Array<String>, values: IntArray, current: Int, onSelect: (Int) -> Unit) =
        SubscreenBuilder.Choice(title, labels,
            values.indices.minByOrNull { idx -> kotlin.math.abs(values[idx] - current) } ?: 0
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
