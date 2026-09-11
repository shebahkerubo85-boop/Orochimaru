package ani.sanin.youtube

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import ani.sanin.R
import ani.sanin.themes.ThemeManager

class YouTubeShortsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        setContentView(R.layout.activity_youtube_shorts_host)

        findViewById<ImageButton>(R.id.shortsBackBtn).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.shortsHostContainer, YouTubeShortsFragment())
                .commit()
        }
    }
}
