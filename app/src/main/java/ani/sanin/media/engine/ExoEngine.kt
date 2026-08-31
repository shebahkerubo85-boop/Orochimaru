package ani.sanin.media.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer

/**
 * Thin wrapper that exposes [ExoPlayer] through the [PlayerEngine] interface.
 *
 * This is a **zero-behaviour-change** pass-through: every call delegates to the
 * existing ExoPlayer instance, so the current ExoplayerView code keeps working
 * identically once it starts calling `engine.*` instead of `exoPlayer.*`.
 */
class ExoEngine(val exoPlayer: ExoPlayer) : PlayerEngine {

    companion object {
        private const val TAG = "ExoEngine"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<PlayerEngine.Listener>()
    private var _state = PlayerEngine.State.IDLE

    /** Register on the real ExoPlayer so we translate callbacks. */
    init {
        exoPlayer.addListener(exoListener)
    }

    // ── ExoPlayer.Listener → PlayerEngine.Listener bridge ──────────────

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val mapped = when (playbackState) {
                Player.STATE_IDLE    -> PlayerEngine.State.IDLE
                Player.STATE_BUFFERING -> PlayerEngine.State.BUFFERING
                Player.STATE_READY   -> PlayerEngine.State.READY
                Player.STATE_ENDED   -> PlayerEngine.State.ENDED
                else -> PlayerEngine.State.IDLE
            }
            _state = mapped
            notify { it.onStateChanged(mapped) }
        }

        override fun onPlayerError(error: PlaybackException) {
            notify { it.onError(error.message) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notify { it.onIsPlayingChanged(isPlaying) }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            notify { it.onVideoSizeChanged(videoSize.width, videoSize.height) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            notify { it.onPositionDiscontinuity(reason) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            notify { it.onTracksChanged() }
        }
    }

    // ── PlayerEngine interface ─────────────────────────────────────────

    override val isPlaying: Boolean get() = exoPlayer.isPlaying
    override val currentPositionMs: Long get() = exoPlayer.currentPosition
    override val durationMs: Long get() = exoPlayer.duration
    override val state: PlayerEngine.State get() = _state
    override var videoWidth: Int = 0
        private set
    override var videoHeight: Int = 0
        private set

    override fun setSurface(surface: Surface?) {
        // ExoPlayer manages its own surface via PlayerView; this is a no-op.
    }

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)
    override fun setSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    override fun setVolume(volume: Float) {
        exoPlayer.volume = volume
    }

    override fun setAudioSessionId(sessionId: Int) {
        exoPlayer.setAudioSessionId(sessionId)
    }

    override fun addListener(listener: PlayerEngine.Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun removeListener(listener: PlayerEngine.Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun setMediaSource(
        url: String,
        headers: Map<String, String>?,
        mimeType: String?,
        subs: List<Any>?
    ) {
        // ExoPlayer source setup is handled directly in ExoplayerView; this is
        // a pass-through. The actual MediaSource creation stays in ExoplayerView.
    }

    override fun prepare() {
        // ExoPlayer.prepare() is called directly in ExoplayerView.
    }

    override fun release() {
        exoPlayer.removeListener(exoListener)
    }

    override fun isReleased(): Boolean = false

    private fun notify(block: (PlayerEngine.Listener) -> Unit) {
        val snapshot: List<PlayerEngine.Listener>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post { snapshot.forEach(block) }
    }
}
