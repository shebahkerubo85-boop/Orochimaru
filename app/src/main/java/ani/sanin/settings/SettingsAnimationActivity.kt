package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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

class SettingsAnimationActivity : AppCompatActivity() {

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
        binding.subscreenTitle.text = getString(R.string.animation)
        binding.subscreenSubtitle.text = getString(R.string.animation_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_motion)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            // ─── Master Control ─────────────────────────────────
            SubscreenBuilder.Section(
                "Master Control", R.drawable.ic_set_motion,
                defaultExpanded = true,
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = "Enable Animations",
                        desc = "Master toggle for all animations",
                        switch = PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) to {
                            PrefManager.setVal(PrefName.AnimationsEnabled, it); restartApp()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = "Animation Speed",
                        desc = "Overall animation playback speed",
                        choice = SubscreenBuilder.Choice(
                            title = "Animation Speed",
                            options = arrayOf("0.5×", "0.75×", "1× (Normal)", "1.25×", "1.5×", "1.75×", "2×"),
                            currentIndex = speedToIndex(PrefManager.getVal(PrefName.AnimationSpeed)),
                        ) { idx -> PrefManager.setVal(PrefName.AnimationSpeed, indexToSpeed(idx)) },
                    ),
                ),
            ),

            // ─── Home & Feed ───────────────────────────────────
            SubscreenBuilder.Section(
                "Home & Feed", R.drawable.ic_set_home,
                entries = listOf(
                    switchEntry(PrefName.BannerAnimations, "Banner Animations"),
                    switchEntry(PrefName.LayoutAnimations, "Layout Animations"),
                    switchEntry(PrefName.TrendingScroller, "Trending Scroller"),
                    switchEntry(PrefName.HomeAnimations, "Home Section Animations"),
                    switchEntry(PrefName.ProfileAnimations, "Profile Animations"),
                    switchEntry(PrefName.LiveSideRail, "Live Side Rail"),
                ),
            ),

            // ─── Player & Playback ─────────────────────────────
            SubscreenBuilder.Section(
                "Player & Playback", R.drawable.ic_set_video,
                entries = listOf(
                    switchEntry(PrefName.PlayerGestureAnimations, "Gesture Animations"),
                    switchEntry(PrefName.PlayerControllerAnimations, "Controller Animations"),
                    switchEntry(PrefName.PlayerOverlayAnimations, "Overlay Animations"),
                    switchEntry(PrefName.DoubleTapAnimations, "Double-Tap Feedback"),
                    switchEntry(PrefName.SeekBarAnimations, "Seek Bar Animations"),
                    switchEntry(PrefName.ProgressShakeAnimations, "Progress Shake"),
                ),
            ),

            // ─── Navigation & Focus ────────────────────────────
            SubscreenBuilder.Section(
                "Navigation & Focus", R.drawable.ic_set_focus,
                entries = listOf(
                    switchEntry(PrefName.NavRailAnimations, "Nav Rail Animations"),
                    switchEntry(PrefName.FocusAnimations, "Focus Effects"),
                    switchEntry(PrefName.KeyboardKeyAnimations, "Keyboard Key Press"),
                ),
            ),

            // ─── Overlays & Dialogs ────────────────────────────
            SubscreenBuilder.Section(
                "Overlays & Dialogs", R.drawable.ic_set_overlay,
                entries = listOf(
                    switchEntry(PrefName.TransitionAnimations, "Screen Transitions"),
                    switchEntry(PrefName.NotificationPopupAnimations, "Notification Popups"),
                    switchEntry(PrefName.CommentInputAnimations, "Comment Input"),
                    switchEntry(PrefName.ImageDialogAnimations, "Image Dialogs"),
                    switchEntry(PrefName.SplashAnimations, "Splash Screen"),
                    switchEntry(PrefName.LikeButtonAnimations, "Like Button"),
                ),
            ),

            // ─── Functional & Misc ─────────────────────────────
            SubscreenBuilder.Section(
                "Functional & Misc", R.drawable.ic_set_misc,
                entries = listOf(
                    switchEntry(PrefName.IncognitoBannerAnimations, "Incognito Banner"),
                    switchEntry(PrefName.SearchHeaderAnimations, "Search Header"),
                    switchEntry(PrefName.ScrollToTopAnimations, "Scroll-to-Top"),
                    switchEntry(PrefName.InstallSpinnerAnimations, "Install Spinner"),
                    switchEntry(PrefName.FilterResetAnimations, "Filter Reset"),
                    switchEntry(PrefName.DescriptionExpandAnimations, "Description Expand"),
                    switchEntry(PrefName.InfoPageAnimations, "Info Page"),
                    switchEntry(PrefName.NoInternetAnimations, "No-Internet Splash"),
                    switchEntry(PrefName.XpandableAnimations, "Expandable Lists"),
                    switchEntry(PrefName.AnimatedVectorDrawables, "Animated Vector Drawables"),
                    switchEntry(PrefName.MiscUiAnimations, "Miscellaneous UI"),
                ),
            ),
        ))
    }

    /** Wrap a PrefName boolean into a SubscreenBuilder switch entry. */
    private fun switchEntry(pref: PrefName, title: String) = SubscreenBuilder.Entry(
        title = title,
        switch = PrefManager.getVal<Boolean>(pref) to { PrefManager.setVal(pref, it) },
    )

    private fun speedToIndex(speed: Float): Int = when (speed) {
        0f -> 0; 0.625f -> 1; 1f -> 2; 1.25f -> 3
        1.5f -> 4; 1.75f -> 5; 2f -> 6; else -> 2
    }

    private fun indexToSpeed(idx: Int): Float = floatArrayOf(
        0f, 0.625f, 1f, 1.25f, 1.5f, 1.75f, 2f
    ).getOrElse(idx) { 1f }
}
