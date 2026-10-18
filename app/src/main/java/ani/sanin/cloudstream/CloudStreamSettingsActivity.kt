package ani.sanin.cloudstream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity

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
        // Pin CommonActivity to THIS activity before CsRuntime re-instantiates
        // the plugin (freshSettingsOpener runs the plugin constructor, which
        // captures SharedPreferences via CommonActivity.getActivity()). Without
        // this the constructor captures a null activity -> null prefs -> checkbox
        // saves silently no-op while the "Restart Required" dialog still shows.
        CommonActivity.setActivityInstance(this)

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
            // Auto-dismiss when the plugin fragment is removed
            supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                        if (fm.fragments.isEmpty()) finish()
                    }
                },
                false,
            )
            // Force any plugin BottomSheetDialogFragment to expand fully on TV.
            // Plugin sheets lack our ani.sanin.BottomSheetDialogFragment base class
            // so they don't get isFitToContents=false / maxHeight=screen / skipCollapsed.
            supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        if (f is com.google.android.material.bottomsheet.BottomSheetDialogFragment) {
                            val sheetView = f.dialog?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                            val sheet = sheetView?.let { com.google.android.material.bottomsheet.BottomSheetBehavior.from(it) }
                            if (sheet != null) {
                                sheet.isFitToContents = false
                                sheet.maxHeight = resources.displayMetrics.heightPixels
                                sheet.skipCollapsed = true
                                sheet.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                            }
                        }
                    }
                },
                false,
            )
            // openSettingsFor binds the plugin against THIS activity (an
            // AppCompatActivity), so plugins that capture the activity at load
            // time (e.g. Ultima) can actually show their sheet.
            val opener = runCatching { CsRuntime.openSettingsFor(this, source) }
            if (opener.isFailure) {
                val t = opener.exceptionOrNull()!!
                Log.e(TAG, "Failed to open ${source.name} settings", t)
                Toast.makeText(this, "Couldn't open ${source.name} settings", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val invoke = opener.getOrNull()
            if (invoke == null) {
                // Either the plugin exposes no openSettings at all, or the plugin
                // itself never loaded — say which, instead of always blaming
                // "not configurable" for what is really a load failure.
                val loadError = if (CsRuntime.isLoaded(source.id)) null else CsRuntime.lastError
                val message = if (loadError != null) {
                    "Failed to load ${source.name}: $loadError"
                } else {
                    "${source.name} has no settings"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val shown = runCatching { invoke(this); true }.getOrElse { t ->
                Log.e(TAG, "Failed to open ${source.name} settings", t)
                Toast.makeText(this, "Couldn't open ${source.name} settings", Toast.LENGTH_SHORT).show()
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
        private const val TAG = "CloudStreamSettings"

        fun intent(context: android.content.Context, sourceId: String): Intent =
            Intent(context, CloudStreamSettingsActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
    }
}
