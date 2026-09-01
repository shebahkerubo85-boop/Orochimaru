package ani.sanin.cloudstream

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import ani.sanin.R
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import com.lagradost.cloudstream3.utils.UIHelper.navigate

/**
 * Thin activity that hosts the CS3 nav graph so GeneratorPlayer can be
 * launched from activities (like TmdbDetailsActivity) that don't themselves
 * have a NavHostFragment.
 *
 * The generator is stored in GeneratorPlayer's in-memory map via
 * [com.lagradost.cloudstream3.ui.player.GeneratorPlayer.newInstance];
 * the returned bundle (containing the uuid) is passed here as [EXTRA_PLAYER_ARGS].
 */
class CsPlayerActivity : AppCompatActivity() {

    companion object {
        const val TAG = "CsPlayerActivity"
        const val EXTRA_PLAYER_ARGS = "player_args"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        CommonActivity.onKeyDown(this, keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        CommonActivity.onUserLeaveHint(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)
        enableEdgeToEdgeCompat()
        setContentView(R.layout.empty_layout)

        // When the player calls exitPlayer() -> popCurrentPage(), the nav
        // back-stack pops back to the start destination (navigation_home).
        // At that point no further nav-pop is possible, so finish the activity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        if (savedInstanceState == null) {
            val args = intent.getBundleExtra(EXTRA_PLAYER_ARGS)
            if (args == null) {
                Log.e(TAG, "No player args provided, finishing")
                finish()
                return
            }
            navigate(R.id.global_to_navigation_player, args)
            Log.i(TAG, "Navigated to GeneratorPlayer")
        }
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }
}
