package ani.sanin.cloudstream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ani.sanin.databinding.ActivityTmdbPlayerBinding
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.launch

class TmdbPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_HEADERS = "headers"
    }

    private lateinit var binding: ActivityTmdbPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tmdbPlayerBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbPlayerBack)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val referer = intent.getStringExtra(EXTRA_REFERER)
        @Suppress("DEPRECATION")
        val headers = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)
            ?: HashMap()
        if (url.isNullOrBlank()) {
            snackString("No video URL")
            finish()
            return
        }
        lifecycleScope.launch {
            binding.tmdbPlayerProgress.isVisible = true
            val requestProperties = HashMap<String, String>().apply {
                putAll(headers)
                if (!referer.isNullOrBlank()) put("Referer", referer)
            }
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(requestProperties)
            val p = ExoPlayer.Builder(this@TmdbPlayerActivity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build().also {
                    binding.tmdbPlayerView.player = it
                }
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) binding.tmdbPlayerProgress.isVisible = false
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.tmdbPlayerProgress.isVisible = false
                    snackString("Playback error: ${error.errorCodeName}")
                }
            })
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
