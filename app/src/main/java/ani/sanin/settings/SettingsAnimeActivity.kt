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
