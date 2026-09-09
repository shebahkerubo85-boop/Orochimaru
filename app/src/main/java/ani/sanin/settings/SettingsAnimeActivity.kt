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
            SubscreenBuilder.Section("Player", R.drawable.ic_set_video, defaultExpanded = true, listOf(
                SubscreenBuilder.Entry(
                    title = "Player Settings",
                    desc = "Advanced player configuration",
                    iconRes = R.drawable.ic_set_video,
                    onClick = { startActivity(Intent(this, PlayerSettingsActivity::class.java)) },
                ),
                SubscreenBuilder.Entry(
                    title = "Prefer Dubbed Audio",
                    desc = "Use dub track when available",
                    switch = PrefManager.getVal<Boolean>(PrefName.PreferDub) to {
                        PrefManager.setVal(PrefName.PreferDub, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Pause Overlay",
                    desc = "Show overlay when paused",
                    switch = PrefManager.getVal<Boolean>(PrefName.PauseOverlay) to {
                        PrefManager.setVal(PrefName.PauseOverlay, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Gesture Sliders",
                    desc = "Brightness & volume via swipe",
                    switch = PrefManager.getVal<Boolean>(PrefName.GestureSliders) to {
                        PrefManager.setVal(PrefName.GestureSliders, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Auto-Hide Delay",
                    desc = "Seconds before controls vanish",
                    choice = SubscreenBuilder.Choice(
                        title = "Auto-Hide Delay",
                        options = arrayOf("2s", "3s", "4s", "5s", "6s", "8s", "10s"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.AutoHideTimeout) - 2,
                    ) { idx -> PrefManager.setVal(PrefName.AutoHideTimeout, idx + 2) },
                ),
                SubscreenBuilder.Entry(
                    title = "Buffer Size",
                    desc = "Video buffer in megabytes",
                    choice = SubscreenBuilder.Choice(
                        title = "Buffer Size",
                        options = arrayOf("16 MB", "32 MB", "64 MB", "128 MB"),
                        currentIndex = when (PrefManager.getVal<Int>(PrefName.BufferSize)) {
                            16 -> 0; 32 -> 1; 64 -> 2; 128 -> 3; else -> 1
                        },
                    ) { idx -> PrefManager.setVal(PrefName.BufferSize, intArrayOf(16, 32, 64, 128)[idx]) },
                ),
                SubscreenBuilder.Entry(
                    title = "Decoder",
                    desc = "Hardware or software decoding",
                    choice = SubscreenBuilder.Choice(
                        title = "Decoder",
                        options = arrayOf("Hardware (MediaCodec)", "Software (FFmpeg)"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DecodingMode),
                    ) { idx -> PrefManager.setVal(PrefName.DecodingMode, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "Subtitle Renderer",
                    desc = "CPU canvas or GPU OpenGL",
                    choice = SubscreenBuilder.Choice(
                        title = "Subtitle Renderer",
                        options = arrayOf("Canvas (TV)", "OpenGL (Phone)"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.SubtitleRenderMode),
                    ) { idx -> PrefManager.setVal(PrefName.SubtitleRenderMode, idx) },
                ),
            )),

            SubscreenBuilder.Section("Library Sync", R.drawable.ic_set_anime, listOf(
                SubscreenBuilder.Entry(
                    title = "Include in Library",
                    desc = "Show anime list in library tab",
                    switch = PrefManager.getVal<Boolean>(PrefName.IncludeAnimeList) to {
                        PrefManager.setVal(PrefName.IncludeAnimeList, it); restartApp()
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Smart Source Memory",
                    desc = "Remember last source across sessions",
                    switch = PrefManager.getVal<Boolean>(PrefName.SmartSourcePersistence) to {
                        PrefManager.setVal(PrefName.SmartSourcePersistence, it)
                    },
                ),
            )),
        ))
    }
}
