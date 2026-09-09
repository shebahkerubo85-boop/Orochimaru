package ani.sanin.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.databinding.DialogUserAgentBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.savePrefsToDownloads
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.settings.saving.internal.PreferenceKeystore
import ani.sanin.settings.saving.internal.PreferencePackager
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class SettingsCommonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsSubscreenBinding

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val jsonString = contentResolver.openInputStream(uri)?.readBytes() ?: throw Exception("Error reading file")
                val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                if (name.endsWith(".sani")) {
                    passwordDialog(false) { password ->
                        if (password != null) {
                            val salt = jsonString.copyOfRange(0, 16)
                            val encrypted = jsonString.copyOfRange(16, jsonString.size)
                            val decrypted = try { PreferenceKeystore.decryptWithPassword(password, encrypted, salt) }
                            catch (e: Exception) { toast(getString(R.string.incorrect_password)); return@passwordDialog }
                            if (PreferencePackager.unpack(decrypted)) restartApp()
                        } else {
                            toast(getString(R.string.password_cannot_be_empty))
                        }
                    }
                } else if (name.endsWith(".ani")) {
                    if (PreferencePackager.unpack(jsonString.toString(Charsets.UTF_8))) restartApp()
                } else {
                    toast(getString(R.string.unknown_file_type))
                }
            } catch (e: Exception) { e.printStackTrace(); toast(getString(R.string.error_importing_settings)) }
        }
    }

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
        binding.subscreenTitle.text = getString(R.string.common)
        binding.subscreenSubtitle.text = getString(R.string.common_desc)
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_dns)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Network", R.drawable.ic_set_dns, listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.selected_dns),
                    desc = "DNS provider for extension connections",
                    iconRes = R.drawable.ic_set_dns,
                    choice = SubscreenBuilder.Choice(
                        title = getString(R.string.selected_dns),
                        options = arrayOf("None", "Google", "Cloudflare", "OpenDNS"),
                        currentIndex = PrefManager.getVal<Int>(PrefName.DohProvider),
                    ) { idx -> PrefManager.setVal(PrefName.DohProvider, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "User Agent",
                    desc = "Custom HTTP user agent string",
                    iconRes = R.drawable.ic_set_user_agent,
                    choice = SubscreenBuilder.Choice(
                        title = "User Agent",
                        options = arrayOf("Default", "Custom"),
                        currentIndex = 0,
                    ) { _ -> showUserAgentDialog() },
                ),
            )),

            SubscreenBuilder.Section("Appearance", R.drawable.ic_set_theme, listOf(
                SubscreenBuilder.Entry(
                    title = "UI Scale",
                    desc = "Adjust UI element size",
                    iconRes = R.drawable.ic_set_theme,
                    choice = SubscreenBuilder.Choice(
                        title = "UI Scale",
                        options = arrayOf("0.75x", "0.85x", "1.0x (Default)", "1.15x", "1.25x"),
                        currentIndex = when (PrefManager.getVal<Float>(PrefName.UIScale)) {
                            0.75f -> 0; 0.85f -> 1; 1.15f -> 3; 1.25f -> 4; else -> 2
                        },
                    ) { idx -> PrefManager.setVal(PrefName.UIScale, floatArrayOf(0.75f, 0.85f, 1f, 1.15f, 1.25f)[idx]) },
                ),
            )),

            SubscreenBuilder.Section("Data", R.drawable.ic_set_backup, listOf(
                SubscreenBuilder.Entry(
                    title = getString(R.string.export_settings),
                    desc = getString(R.string.export_settings_desc),
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { savePrefsToDownloads() },
                ),
                SubscreenBuilder.Entry(
                    title = getString(R.string.import_settings),
                    desc = getString(R.string.import_settings_desc),
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) },
                ),
            )),

            SubscreenBuilder.Section("Notifications", R.drawable.ic_settings_display, listOf(
                SubscreenBuilder.Entry(
                    title = "AniList Notifications",
                    desc = "Fetch AniList notification count",
                    switch = PrefManager.getVal<Boolean>(PrefName.AnilistNotifications) to {
                        PrefManager.setVal(PrefName.AnilistNotifications, it)
                    },
                ),
                SubscreenBuilder.Entry(
                    title = "Episode Notifications",
                    desc = "Notify when new episodes are released",
                    switch = PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications) to {
                        PrefManager.setVal(PrefName.EpisodeNotifications, it)
                    },
                ),
            )),
        ))
    }

    private fun showUserAgentDialog() {
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        TvKeyboardUtil.setupTvInput(dialogView.userAgentTextBox)
        dialogView.userAgentTextBox.setText(PrefManager.getVal<String>(PrefName.DefaultUserAgent))
        customAlertDialog().apply {
            setTitle(R.string.user_agent)
            setCustomView(dialogView.root)
            setPosButton(R.string.ok) { PrefManager.setVal(PrefName.DefaultUserAgent, dialogView.userAgentTextBox.text.toString()) }
            setNeutralButton(R.string.reset) { PrefManager.removeVal(PrefName.DefaultUserAgent); dialogView.userAgentTextBox.setText("") }
            setNegButton(R.string.cancel)
        }.show()
    }

    private fun passwordDialog(isExporting: Boolean, callback: (CharArray?) -> Unit) {
        val password = CharArray(16).apply { fill('0') }
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        TvKeyboardUtil.setupTvInput(dialogView.userAgentTextBox)
        val box = dialogView.userAgentTextBox
        box.hint = getString(R.string.password); box.setSingleLine()
        val dialog = AlertDialog.Builder(this, R.style.MyPopup)
            .setTitle(getString(R.string.enter_password))
            .setView(dialogView.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ -> password.fill('0'); d.dismiss(); callback(null) }
            .create()
        fun handleOk() {
            if (box.text?.isNotBlank() == true) { box.text?.toString()?.trim()?.toCharArray(password); dialog.dismiss(); callback(password) }
            else toast(getString(R.string.password_cannot_be_empty))
        }
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { handleOk(); true } else false }
        dialogView.subtitle.visibility = View.VISIBLE
        if (!isExporting) dialogView.subtitle.text = getString(R.string.enter_password_to_decrypt_file)
        dialog.window?.apply { setDimAmount(0.8f); TvKeyboardUtil.retainWindowFocus(this) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { handleOk() }
    }
}
