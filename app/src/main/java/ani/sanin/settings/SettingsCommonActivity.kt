package ani.sanin.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import ani.sanin.R
import ani.sanin.databinding.ActivitySettingsSubscreenBinding
import ani.sanin.databinding.DialogUserAgentBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.restartApp
import ani.sanin.savePrefsToDownloads
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.settings.saving.internal.Location
import ani.sanin.settings.saving.internal.PreferenceKeystore
import ani.sanin.settings.saving.internal.PreferencePackager
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.toast
import ani.sanin.util.TvKeyboardUtil
import ani.sanin.util.customAlertDialog

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
                        } else toast(getString(R.string.password_cannot_be_empty))
                    }
                } else if (name.endsWith(".ani")) {
                    if (PreferencePackager.unpack(jsonString.toString(Charsets.UTF_8))) restartApp()
                } else toast(getString(R.string.unknown_file_type))
            } catch (e: Exception) { e.printStackTrace(); toast(getString(R.string.error_importing_settings)) }
        }
    }

    private val dnsNames = arrayOf(
        "None", "Cloudflare", "Google", "AdGuard", "Quad9",
        "AliDNS", "DNSPod", "360", "Quad101", "Mullvad",
        "Controld", "Njalla", "Shecan", "Libre",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsSubscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.subscreenContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight; bottomMargin = navBarHeight
        }
        binding.subscreenBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.subscreenTitle.text = "Network & Data"
        binding.subscreenSubtitle.text = "Connection, behavior & backup"
        binding.subscreenIcon.setImageResource(R.drawable.ic_set_dns)

        SubscreenBuilder.build(this, binding.subscreenContent, listOf(
            SubscreenBuilder.Section("Connection", R.drawable.ic_set_dns, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "DNS Provider",
                    desc = dnsNames[PrefManager.getVal<Int>(PrefName.DohProvider)],
                    iconRes = R.drawable.ic_set_dns,
                    choice = SubscreenBuilder.Choice(
                        title = "DNS Provider",
                        options = dnsNames,
                        currentIndex = PrefManager.getVal<Int>(PrefName.DohProvider),
                    ) { idx -> PrefManager.setVal(PrefName.DohProvider, idx) },
                ),
                SubscreenBuilder.Entry(
                    title = "User Agent",
                    desc = "Custom HTTP identity string",
                    iconRes = R.drawable.ic_set_user_agent,
                    onClick = { showUserAgentDialog() },
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
            )),

            SubscreenBuilder.Section("Sync", R.drawable.ic_set_anime, entries = listOf(
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
                    title = "Auto-Update Extensions",
                    desc = "Update extensions on startup",
                    switch = PrefManager.getVal<Boolean>(PrefName.AutoUpdateExtensions) to { v: Boolean -> PrefManager.setVal(PrefName.AutoUpdateExtensions, v) },
                ),
            )),

            SubscreenBuilder.Section("Interface", R.drawable.ic_set_theme, entries = listOf(
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
            )),

            SubscreenBuilder.Section("Backup & Restore", R.drawable.ic_set_backup, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "Export Settings",
                    desc = "Save config to downloads folder",
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { savePrefsToDownloads("SaninSettings", PrefManager.exportAllPrefs(listOf(Location.General, Location.UI, Location.Player)), this@SettingsCommonActivity) },
                ),
                SubscreenBuilder.Entry(
                    title = "Import Settings",
                    desc = "Load config from file",
                    iconRes = R.drawable.ic_set_backup,
                    onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) },
                ),
            )),

            SubscreenBuilder.Section("Alerts", R.drawable.ic_set_overlay, entries = listOf(
                SubscreenBuilder.Entry(
                    title = "AniList Count",
                    desc = "Fetch notification count from AniList",
                    switch = PrefManager.getVal<Boolean>(PrefName.AnilistNotifications) to { v: Boolean -> PrefManager.setVal(PrefName.AnilistNotifications, v) },
                ),
                SubscreenBuilder.Entry(
                    title = "New Episode Alerts",
                    desc = "Notify when new episodes drop",
                    switch = PrefManager.getVal<Boolean>(PrefName.EpisodeNotifications) to { v: Boolean -> PrefManager.setVal(PrefName.EpisodeNotifications, v) },
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
            .setTitle(getString(R.string.enter_password)).setView(dialogView.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ -> password.fill('0'); d.dismiss(); callback(null) }.create()
        fun handleOk() {
            if (box.text?.isNotBlank() == true) { box.text?.toString()?.trim()?.toCharArray(password); dialog.dismiss(); callback(password) }
            else toast(getString(R.string.password_cannot_be_empty))
        }
        box.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { handleOk(); true } else false }
        dialogView.subtitle.visibility = android.view.View.VISIBLE
        if (!isExporting) dialogView.subtitle.text = getString(R.string.enter_password_to_decrypt_file)
        dialog.window?.apply { setDimAmount(0.8f); TvKeyboardUtil.retainWindowFocus(this) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { handleOk() }
    }
}
