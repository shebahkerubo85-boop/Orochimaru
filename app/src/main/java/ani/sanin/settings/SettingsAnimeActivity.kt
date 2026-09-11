package ani.sanin.settings

import android.content.Intent
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

class SettingsAnimeActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = "Playback"
        binding.subscreenSubtitle.text = "Player, decoding & sync"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_video)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Player", R.drawable.ic_set_video, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Player Settings",
                    desc = "Advanced player configuration",
                    iconRes = R.drawable.ic_set_video,
                    onClick = { startActivity(Intent(this, PlayerSettingsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Blur Unwatched Episodes",
                    desc = "Blur episodes you haven't seen",
                    switch = PrefManager.getVal<Boolean>(PrefName.BlurUnwatchedEpisodes) to {
                        PrefManager.setVal(PrefName.BlurUnwatchedEpisodes, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Grey Watched Episodes",
                    desc = "Grey out episodes you've watched",
                    switch = PrefManager.getVal<Boolean>(PrefName.GreyWatchedEpisodes) to {
                        PrefManager.setVal(PrefName.GreyWatchedEpisodes, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Behavior", R.drawable.ic_set_misc, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Startup Tab",
                    desc = "Which tab opens on launch",
                    choice = SubscreenBuilder.Choice(
                        title = "Startup Tab",
                        options = arrayOf("Home", "Search", "Library", "Extensions"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DefaultStartUpTab).coerceIn(0, 3),
                    ) { idx -> PrefManager.setVal(PrefName.DefaultStartUpTab, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Continue Media",
                    desc = "Auto-resume from last position",
                    switch = PrefManager.getVal<Boolean>(PrefName.ContinueMedia) to { v: Boolean -> PrefManager.setVal(PrefName.ContinueMedia, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Hide Private Sources",
                    desc = "Hide NSFW extensions from lists",
                    switch = PrefManager.getVal<Boolean>(PrefName.HidePrivate) to { v: Boolean -> PrefManager.setVal(PrefName.HidePrivate, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Adult Content",
                    desc = "Show adult-only sources",
                    switch = PrefManager.getVal<Boolean>(PrefName.AdultOnly) to { v: Boolean -> PrefManager.setVal(PrefName.AdultOnly, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Search All Sources",
                    desc = "Search across all installed sources",
                    switch = PrefManager.getVal<Boolean>(PrefName.SearchSources) to { v: Boolean -> PrefManager.setVal(PrefName.SearchSources, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Recently Updated Only",
                    desc = "Only show recently updated entries",
                    switch = PrefManager.getVal<Boolean>(PrefName.RecentlyListOnly) to { v: Boolean -> PrefManager.setVal(PrefName.RecentlyListOnly, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Keyboard Mode",
                    desc = "System keyboard or in-app toggle",
                    choice = SubscreenBuilder.Choice(
                        title = "Keyboard Mode",
                        options = arrayOf("System Keyboard", "In-App Toggle"),
                        currentIndex = if (PrefManager.getVal<Int>(PrefName.KeyboardMode) == 2) 1 else 0,
                    ) { idx -> PrefManager.setVal(PrefName.KeyboardMode, if (idx == 1) 2 else 0) },
                ),
                SubscreenBuilder.Entry(
                    title = "Server Timeout",
                    desc = "Seconds before server load timeout",
                    choice = SubscreenBuilder.Choice(
                        title = "Server Timeout",
                        options = arrayOf("4s", "8s", "12s (Default)", "16s", "20s", "30s"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.ServerLoadTimeoutSeconds)) {
                            4 -> 0; 8 -> 1; 12 -> 2; 16 -> 3; 20 -> 4; 30 -> 5; else -> 2
                        },
                    ) { idx -> PrefManager.setVal(PrefName.ServerLoadTimeoutSeconds, intArrayOf(4, 8, 12, 16, 20, 30)[idx]) },
                ),
                SubscreenBuilder.Entry(
                    title = "Smart Source Memory",
                    desc = "Remember last source across sessions",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmartSourcePersistence) to {
                        PrefManager.setVal(PrefName.SmartSourcePersistence, it)
                    },
                ),
            )),

            SubscreenBuilder.Section("Library Sync", R.drawable.ic_set_anime, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Auto-Sync AniList",
                    desc = "Keep AniList library in sync",
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoSyncAniList) to { v: Boolean -> PrefManager.setVal(PrefName.AutoSyncAniList, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto-Update Progress",
                    desc = "Push episode progress to AniList",
                    switch = PrefManager.getVal<Boolean>(PrefName.UpdateProgressAutomatically) to { v: Boolean -> PrefManager.setVal(PrefName.UpdateProgressAutomatically, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "Include in Library",
                    desc = "Show anime list in library tab",
                    switch = PrefManager.getVal<Boolean>(PrefName.IncludeAnimeList) to {
                        PrefManager.setVal(PrefName.IncludeAnimeList, it); restartApp()
                    },
                ),
            )),
        ))
    }
}
