package ani.sanin.media.engine

import android.view.Surface

/**
 * Abstraction over a video playback engine so the ExoplayerView UI (buttons,
 * seek bar, focus chain, overlays) works identically whether the underlying
 * engine is ExoPlayer (Media3) or mpv (libmpv via Media3-free MPVLib wrapper).
 *
 * The UI talks to a [PlayerEngine] exclusively — never to the concrete engine —
 * so adding a new engine or toggling engines at runtime requires no UI changes.
 */
interface PlayerEngine {
    /** One of the stable playback states the UI can interpret generically. */
    enum class State {
        IDLE, BUFFERING, READY, ENDED, ERROR
    }

    val isPlaying: Boolean
    val currentPositionMs: Long
    val durationMs: Long
    val state: State
    /** Current video size in pixels (0,0 until known). */
    var videoWidth: Int
    var videoHeight: Int

    /** Called from the main thread. */
    fun setSurface(surface: Surface?)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    /** Volume in 0..1 (1 = 100%). */
    fun setVolume(volume: Float)
    fun setAudioSessionId(sessionId: Int)
    /** Subscribe to engine events. */
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    /** Prepare + start a source. [headers] may be null. */
    fun setMediaSource(url: String, headers: Map<String, String>?, mimeType: String?, subs: List<Any>?)
    fun prepare()
    fun release()
    fun isReleased(): Boolean

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onError(message: String?) {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPositionDiscontinuity(reason: Int) {}
        fun onTracksChanged() {}
    }
}
