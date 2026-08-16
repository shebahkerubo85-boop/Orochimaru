package ani.sanin.connections.simkl

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.sanin.logError
import ani.sanin.snackString
import ani.sanin.startMainActivity
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        val data: Uri? = intent?.data
        if (data != null) {
            val code = data.getQueryParameter("code")
            if (code != null) {
                snackString("Logging in to Simkl...")
                lifecycleScope.launch {
                    val token = withContext(Dispatchers.IO) {
                        Simkl.exchangeCode(code)
                    }
                    if (token != null && token.accessToken != null) {
                        Simkl.token = token.accessToken
                        snackString("Fetching profile...")
                        withContext(Dispatchers.IO) {
                            Simkl.fetchUserData()
                        }
                        startMainActivity(this@Login)
                    } else {
                        snackString("Simkl login failed: no token")
                        logError(Exception("Simkl login: token exchange returned null"), snackbar = false)
                        startMainActivity(this@Login)
                    }
                }
            } else {
                snackString("Simkl login failed: no code in response")
                logError(Exception("Simkl login: no code in $data"), snackbar = false)
                startMainActivity(this)
            }
        } else {
            snackString("Simkl login failed: no response URI")
            logError(Exception("Simkl login: intent.data is null"), snackbar = false)
            startMainActivity(this)
        }
    }
}
