package ani.sanin.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsAddonsBinding
import ani.sanin.download.FfmpegRuntime
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.SizeFormatter
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch

class SettingsAddonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAddonsBinding
    private var renderedState = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        FfmpegRuntime.init(applicationContext)
        binding = ActivitySettingsAddonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAddonsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            addonSettingsBack.isFocusable = true
            FocusEffectUtil.applyFocusListener(addonSettingsBack)
            addonSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsRecyclerView.layoutManager =
                LinearLayoutManager(this@SettingsAddonActivity, LinearLayoutManager.VERTICAL, false)
        }

        lifecycleScope.launch {
            FfmpegRuntime.state.collect(::render)
        }
    }

    private fun render(state: FfmpegRuntime.State) {
        val stateKey = when (state) {
            FfmpegRuntime.State.Unsupported -> "unsupported"
            FfmpegRuntime.State.NotInstalled -> "not-installed"
            is FfmpegRuntime.State.Downloading -> {
                val percent = if (state.progress >= 0f) {
                    (state.progress * 100).toInt()
                } else {
                    -1
                }
                "downloading-$percent"
            }

            is FfmpegRuntime.State.Installed -> "installed-${state.sizeBytes}"
            is FfmpegRuntime.State.Error -> "error-${state.message}"
        }
        if (stateKey == renderedState) return
        renderedState = stateKey

        val title: String
        val desc: String
        val enabled = state !is FfmpegRuntime.State.Unsupported
        when (state) {
            FfmpegRuntime.State.Unsupported -> {
                title = getString(R.string.pack_ffmpeg)
                desc = getString(R.string.pack_ffmpeg_unsupported)
            }

            FfmpegRuntime.State.NotInstalled -> {
                title = getString(R.string.pack_ffmpeg)
                desc = getString(R.string.pack_ffmpeg_install_desc)
            }

            is FfmpegRuntime.State.Downloading -> {
                title = getString(R.string.pack_ffmpeg_downloading)
                desc = if (state.progress >= 0f) {
                    getString(R.string.pack_ffmpeg_progress, (state.progress * 100).toInt())
                } else {
                    getString(R.string.pack_ffmpeg_progress_unknown)
                }
            }

            is FfmpegRuntime.State.Installed -> {
                title = getString(R.string.pack_ffmpeg_installed)
                desc = getString(
                    R.string.pack_ffmpeg_installed_desc,
                    SizeFormatter.formatBytes(state.sizeBytes),
                )
            }

            is FfmpegRuntime.State.Error -> {
                title = getString(R.string.pack_ffmpeg)
                desc = getString(R.string.pack_ffmpeg_failed, state.message)
            }
        }

        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = title,
                    desc = desc,
                    icon = R.drawable.ic_download_24,
                    onClick = { handleAction(state) },
                    isVisible = enabled,
                    isActivity = true,
                ),
            ),
        )
    }

    private fun handleAction(state: FfmpegRuntime.State) {
        when (state) {
            FfmpegRuntime.State.Unsupported -> toast(R.string.pack_ffmpeg_unsupported)
            is FfmpegRuntime.State.Installed -> {
                customAlertDialog().apply {
                    setTitle(getString(R.string.pack_ffmpeg_remove_title))
                    setMessage(getString(R.string.pack_ffmpeg_remove_message))
                    setPosButton(getString(R.string.remove)) {
                        FfmpegRuntime.uninstall()
                        toast(R.string.pack_ffmpeg_removed)
                    }
                    setNegButton(getString(R.string.cancel)) {}
                }.show()
            }

            is FfmpegRuntime.State.Downloading -> toast(R.string.pack_ffmpeg_busy)
            FfmpegRuntime.State.NotInstalled,
            is FfmpegRuntime.State.Error,
            -> {
                toast(R.string.pack_ffmpeg_started)
                FfmpegRuntime.install()
            }
        }
    }
}
