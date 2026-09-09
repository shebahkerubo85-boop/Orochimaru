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
        binding.subscreenTitle.text = getString(R.string.anime)
        binding.subscreenSubtitle.text = getString(R.string.anime_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_anime)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Playback", R.drawable.ic_set_video, listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.player_settings),
                    desc = getString(R.string.player_settings_desc),
                    iconRes = R.drawable.ic_set_video,
                    onClick = { startActivity(Intent(this, PlayerSettingsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.prefer_dub),
                    desc = getString(R.string.prefer_dub_desc),
                    switch = PrefManager.getVal<Boolean>(PrefName.PreferDub) to {
                        PrefManager.setVal(PrefName.PreferDub, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Pause Overlay",
                    desc = "Show pause overlay when video is paused",
                    switch = PrefManager.getVal<Boolean>(PrefName.PauseOverlay) to {
                        PrefManager.setVal(PrefName.PauseOverlay, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Gesture Sliders",
                    desc = "Brightness/volume sliders via vertical gestures",
                    switch = PrefManager.getVal<Boolean>(PrefName.GestureSliders) to {
                        PrefManager.setVal(PrefName.GestureSliders, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto-Hide Timeout",
                    desc = "Seconds before player controls auto-hide",
                    choice = SubscreenBuilder.Choice(
                        title = "Auto-Hide Timeout",
                        options = arrayOf("2s", "3s", "4s", "5s", "6s", "8s", "10s"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.AutoHideTimeout) - 2,
                    ) { idx -> PrefManager.setVal(PrefName.AutoHideTimeout, idx + 2) },
                ),
                SubscreenBuilder.Entry(
                    title = "Buffer Size",
                    desc = "Video buffer size in MB",
                    choice = SubscreenBuilder.Choice(
                        title = "Buffer Size",
                        options = arrayOf("16 MB", "32 MB", "64 MB", "128 MB"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.BufferSize)) {
                            16 -> 0; 32 -> 1; 64 -> 2; 128 -> 3; else -> 1
                        },
                    ) { idx -> PrefManager.setVal(PrefName.BufferSize, intArrayOf(16, 32, 64, 128)[idx]) },
                ),
                SubscreenBuilder.Entry(
                    title = "Decoding Mode",
                    desc = "Hardware or Software decoder",
                    choice = SubscreenBuilder.Choice(
                        title = "Decoding Mode",
                        options = arrayOf("Hardware (MediaCodec)", "Software (FFmpeg)"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DecodingMode),
                    ) { idx -> PrefManager.setVal(PrefName.DecodingMode, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Subtitle Render Mode",
                    desc = "Canvas=CPU (TV), OpenGL=GPU (Phone)",
                    choice = SubscreenBuilder.Choice(
                        title = "Subtitle Render Mode",
                        options = arrayOf("Canvas (TV default)", "OpenGL (Phone default)"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.SubtitleRenderMode),
                    ) { idx -> PrefManager.setVal(PrefName.SubtitleRenderMode, idx) },
                ),
            ), defaultExpanded = true),

            SubscreenBuilder.Section("Library", R.drawable.ic_set_anime, listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.include_list),
                    desc = getString(R.string.include_list_anime_desc),
                    switch = PrefManager.getVal<Boolean>(PrefName.IncludeAnimeList) to {
                        PrefManager.setVal(PrefName.IncludeAnimeList, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Smart Source Persistence",
                    desc = "Remember source selection across sessions",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmartSourcePersistence) to {
                        PrefManager.setVal(PrefName.SmartSourcePersistence, it)
                    },
                ),
            )),
        ))
    }
}
