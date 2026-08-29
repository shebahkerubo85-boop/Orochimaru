package ani.sanin.cloudstream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.content.Intent
import android.widget.Toast

/**
 * A thin, transparent [AppCompatActivity] that hosts a CloudStream plugin's OWN
 * settings UI.
 *
 * Plugins expose settings via `Plugin.openSettings(Context)`. There are two shapes:
 *  - Fragment/BottomSheet plugins (e.g. Ultima, StremioX) cast the Context to
 *    [AppCompatActivity] and show a `BottomSheetDialogFragment` on
 *    `supportFragmentManager`.
 *  - Plain-dialog plugins (e.g. CineStream) show an `android.app.AlertDialog`
 *    straight on this activity's window — NO fragment is added.
 *
 * The plugin sheet must render under a MaterialComponents theme; Sanin's own
 * Theme.Material3 host crashes it (ComponentDialog NPE), so this dedicated
 * transparent activity carries `Theme.CloudStreamSettings` (MaterialComponents
 * parent) and finishes as soon as the sheet/dialog is dismissed — leaving the
 * user back where they were.
 */
class CloudStreamSettingsActivity : AppCompatActivity() {

    private var dialogTookFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        val source = sourceId?.let { id ->
            CsRepos.installed(this).firstOrNull { it.id == id }
        }
        if (source == null) {
            Toast.makeText(this, "No settings available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (savedInstanceState == null) {
            supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                        if (fm.fragments.isEmpty()) finish()
                    }
                },
                false,
            )
            // openSettingsFor binds the plugin against THIS activity (an
            // AppCompatActivity), so plugins that capture the activity at load
            // time (e.g. Ultima) can actually show their sheet.
            val shown = runCatching {
                val opener = CsRuntime.openSettingsFor(this, source) ?: return@runCatching false
                opener(this)
                true
            }.getOrElse { t ->
                val detail = t.stackTraceToString().lineSequence().take(2).joinToString(" | ")
                Toast.makeText(this, "Failed to open settings: ${t.message} ($detail)", Toast.LENGTH_LONG).show()
                false
            }
            if (!shown) finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            dialogTookFocus = true
            return
        }
        if (dialogTookFocus && supportFragmentManager.fragments.isEmpty()) finish()
    }

    companion object {
        const val EXTRA_SOURCE_ID = "sourceId"

        fun intent(context: android.content.Context, sourceId: String): Intent =
            Intent(context, CloudStreamSettingsActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
    }
}
