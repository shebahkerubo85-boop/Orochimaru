package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager

class SettingsDownloadsActivity : AppCompatActivity() {
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
        binding.subscreenTitle.text = getString(R.string.settings_download_card_title)
        binding.subscreenSubtitle.text = getString(R.string.settings_download_card_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_download)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            // ───────── General ─────────
            SubscreenBuilder.Section(
                title = getString(R.string.download_section_general),
                iconRes = R.drawable.ic_set_video,
                desc = "Concurrency and per-file connection limits",
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.download_parallel_items),
                        desc = getString(R.string.download_parallel_items_desc),
                        iconRes = R.drawable.ic_set_video,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.DownloadParallelItems)
                                .coerceIn(1, 6).toFloat(),
                            valueFrom = 1f,
                            valueTo = 6f,
                            step = 1f,
                            suffix = "",
                        ) { v ->
                            PrefManager.setVal(PrefName.DownloadParallelItems, v.toInt())
                            DownloadQueueManager.forceRefreshQueue()
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.download_connections_per_file),
                        desc = getString(R.string.download_connections_per_file_desc),
                        iconRes = R.drawable.ic_set_motion,
                        slider = SubscreenBuilder.SliderOption(
                            value = PrefManager.getVal<Int>(PrefName.DownloadConnectionsPerFile)
                                .coerceIn(1, 8).toFloat(),
                            valueFrom = 1f,
                            valueTo = 8f,
                            step = 1f,
                            suffix = "",
                        ) { v ->
                            PrefManager.setVal(PrefName.DownloadConnectionsPerFile, v.toInt())
                        },
                    ),
                ),
            ),

            // ───────── Advanced ─────────
            SubscreenBuilder.Section(
                title = getString(R.string.download_section_advanced),
                iconRes = R.drawable.ic_set_misc,
                desc = "Buffer size, retries and network restrictions",
                entries = listOf(
                    SubscreenBuilder.Entry(
                        title = getString(R.string.download_segment_buffer),
                        desc = getString(R.string.download_segment_buffer_desc),
                        iconRes = R.drawable.ic_set_blur,
                        choice = SubscreenBuilder.Choice(
                            title = getString(R.string.download_segment_buffer),
                            options = arrayOf("64 KB", "128 KB", "256 KB", "512 KB"),
                            currentIndex = when (PrefManager.getVal<Int>(PrefName.DownloadSegmentBufferKb)) {
                                64 -> 0; 128 -> 1; 256 -> 2; 512 -> 3; else -> 0
                            },
                        ) { idx ->
                            PrefManager.setVal(
                                PrefName.DownloadSegmentBufferKb,
                                intArrayOf(64, 128, 256, 512)[idx],
                            )
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.download_retry_expired_urls),
                        desc = getString(R.string.download_retry_expired_urls_desc),
                        iconRes = R.drawable.ic_set_dns,
                        switch = PrefManager.getVal<Boolean>(PrefName.DownloadRetryExpiredUrls) to { v ->
                            PrefManager.setVal(PrefName.DownloadRetryExpiredUrls, v)
                        },
                    ),
                    SubscreenBuilder.Entry(
                        title = getString(R.string.download_wifi_only),
                        desc = getString(R.string.download_wifi_only_desc),
                        iconRes = R.drawable.ic_set_dns,
                        switch = PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) to { v ->
                            PrefManager.setVal(PrefName.DownloadWifiOnly, v)
                        },
                    ),
                ),
            ),
        ))
    }
}
