package ani.sanin.media.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib

/**
 * Video playback engine backed by **libmpv** (mpv-android 0.1.9).
 *
 * Ports the live-stream / HLS stack from Zangetsu to pure Kotlin.
 * MPVLib is a singleton — we configure it globally and use addObserver
 * for event callbacks.
 */
class MpvEngine(private val appContext: Context) : PlayerEngine {

    companion object {
        private const val TAG = "MpvEngine"
        private const val CACHE_SECS = 60
        private const val DEMUXER_MAX_BYTES = "128MiB"
        private const val DEMUXER_MAX_BACK_BYTES = "48MiB"
        private const val ANALYZEDURATION_US = 2_000_000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _released = false
    private val listeners = mutableListOf<PlayerEngine.Listener>()
    private var _state = PlayerEngine.State.IDLE
    private var _isPlaying = false
    private var _currentPositionMs = 0L
    private var _durationMs = 0L
    private var _videoWidth = 0
    private var _videoHeight = 0

    init {
        applyGlobalConfig()
        MPVLib.addObserver(eventObserver)
        Log.i(TAG, "mpv engine initialised, observer registered")
    }

    private fun applyGlobalConfig() {
        try {
            // Cache / read-ahead (Zangetsu)
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("cache-secs", "$CACHE_SECS")
            MPVLib.setPropertyString("demuxer-readahead-secs", "$CACHE_SECS")
            MPVLib.setPropertyString("demuxer-max-bytes", DEMUXER_MAX_BYTES)
            MPVLib.setPropertyString("demuxer-max-back-bytes", DEMUXER_MAX_BACK_BYTES)
            // Pause audio+video together on underrun, wait 2 s, resume in sync
            MPVLib.setPropertyString("cache-pause", "yes")
            MPVLib.setPropertyString("cache-pause-wait", "2")
            // A/V sync after mid-stream stall
            MPVLib.setPropertyString("hwdec", "mediacodec-copy")
            // Force-seekable
            MPVLib.setPropertyString("force-seekable", "yes")
            // Reconnect for live HLS
            MPVLib.setPropertyString(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1," +
                    "reconnect_on_network_error=1,reconnect_delay_max=5"
            )
            // Demuxer tuning
            MPVLib.setPropertyString(
                "demuxer-lavf-o",
                "extension_picky=0,allowed_extensions=ALL," +
                    "http_persistent=0,analyzeduration=$ANALYZEDURATION_US"
            )
            // Software decode fallback
            MPVLib.setPropertyString("vd-lavc-threads", "4")
            MPVLib.setPropertyString("vd-lavc-skiploopfilter", "nonkey")
            MPVLib.setPropertyString("vd-lavc-fast", "yes")
            // Volume
            MPVLib.setPropertyString("volume-max", "200")
            MPVLib.setPropertyString("audio-pitch-correction", "yes")

            // Observe properties so we get callbacks
            MPVLib.observeProperty("pause", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("duration", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("width", MPVLib.mpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("height", MPVLib.mpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("core-idle", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply global config", e)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun setSurface(surface: Surface?) {
        if (_released) return
        try {
            if (surface != null) {
                MPVLib.attachSurface(surface)
            } else {
                MPVLib.detachSurface()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setSurface error", e)
        }
    }

    override fun setMediaSource(
        url: String,
        headers: Map<String, String>?,
        mimeType: String?,
        subs: List<Any>?
    ) {
        // Headers are set at openUrl time
    }

    override fun prepare() {
        // no-op; prepare happens in openUrl
    }

    /** Actually open a URL in mpv. */
    fun openUrl(url: String, headers: Map<String, String>?) {
        if (_released) return
        try {
            if (!headers.isNullOrEmpty()) {
                val headerStr = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
                MPVLib.setPropertyString("http-header-fields", headerStr)
            }
            MPVLib.command(arrayOf("loadfile", url))
        } catch (e: Exception) {
            Log.e(TAG, "openUrl failed", e)
            notifyError(e.message)
        }
    }

    override fun play() {
        if (_released) return
        MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        if (_released) return
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) {
        if (_released) return
        MPVLib.setPropertyDouble("seekto", positionMs / 1000.0)
    }

    override fun setSpeed(speed: Float) {
        if (_released) return
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setVolume(volume: Float) {
        if (_released) return
        MPVLib.setPropertyDouble("volume", (volume * 100.0).coerceIn(0.0, 200.0))
    }

    override fun setAudioSessionId(sessionId: Int) {
        // mpv uses Android's AudioTrack directly
    }

    // ── Listener management ───────────────────────────────────────────

    override fun addListener(listener: PlayerEngine.Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun removeListener(listener: PlayerEngine.Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyState(state: PlayerEngine.State) {
        _state = state
        val snapshot: List<PlayerEngine.Listener>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post { snapshot.forEach { it.onStateChanged(state) } }
    }

    private fun notifyError(msg: String?) {
        val snapshot: List<PlayerEngine.Listener>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post { snapshot.forEach { it.onError(msg) } }
    }

    private fun notifyPlaying(playing: Boolean) {
        _isPlaying = playing
        val snapshot: List<PlayerEngine.Listener>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post { snapshot.forEach { it.onIsPlayingChanged(playing) } }
    }

    private fun notifyVideoSize(w: Int, h: Int) {
        _videoWidth = w
        _videoHeight = h
        val snapshot: List<PlayerEngine.Listener>
        synchronized(listeners) { snapshot = listeners.toList() }
        mainHandler.post { snapshot.forEach { it.onVideoSizeChanged(w, h) } }
    }

    // ── MPVLib event observer ─────────────────────────────────────────

    private val eventObserver = object : MPVLib.EventObserver {
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED -> {
                    Log.i(TAG, "FILE_LOADED")
                    notifyState(PlayerEngine.State.READY)
                    try {
                        val w = MPVLib.getPropertyInt("width") ?: 0
                        val h = MPVLib.getPropertyInt("height") ?: 0
                        if (w > 0 && h > 0) notifyVideoSize(w, h)
                    } catch (_: Exception) {}
                }
                MPVLib.mpvEventId.MPV_EVENT_START_FILE -> {
                    Log.i(TAG, "START_FILE -> BUFFERING")
                    notifyState(PlayerEngine.State.BUFFERING)
                }
                MPVLib.mpvEventId.MPV_EVENT_END_FILE -> {
                    Log.i(TAG, "END_FILE")
                    notifyState(PlayerEngine.State.ENDED)
                }
                MPVLib.mpvEventId.MPV_EVENT_SEEK -> {
                    val snapshot: List<PlayerEngine.Listener>
                    synchronized(listeners) { snapshot = listeners.toList() }
                    mainHandler.post {
                        snapshot.forEach { it.onPositionDiscontinuity(0) }
                    }
                }
                MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> {
                    notifyState(PlayerEngine.State.READY)
                }
            }
        }

        override fun eventProperty(property: String) {}

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "width" -> if (value > 0) notifyVideoSize(value.toInt(), _videoHeight)
                "height" -> if (value > 0) notifyVideoSize(_videoWidth, value.toInt())
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> notifyPlaying(!value)
                "core-idle" -> {
                    if (!value && _state != PlayerEngine.State.READY) {
                        notifyState(PlayerEngine.State.READY)
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) {}

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "duration" -> _durationMs = (value * 1000).toLong()
                "time-pos" -> _currentPositionMs = (value * 1000).toLong()
            }
        }

        override fun eventProperty(property: String, value: Long) {}

        override fun eventProperty(property: String, value: `is`.xyz.mpv.MPVNode) {}
    }

    // ── Property accessors ────────────────────────────────────────────

    override val isPlaying: Boolean get() = _isPlaying && !_released
    override val currentPositionMs: Long get() = if (_released) 0 else _currentPositionMs
    override val durationMs: Long get() = if (_released) 0 else _durationMs
    override val state: PlayerEngine.State get() = _state
    override var videoWidth: Int get() = _videoWidth
        set(_) {}
    override var videoHeight: Int get() = _videoHeight
        set(_) {}

    override fun isReleased(): Boolean = _released

    override fun release() {
        if (_released) return
        _released = true
        MPVLib.removeObserver(eventObserver)
        try {
            MPVLib.detachSurface()
            MPVLib.command(arrayOf("stop"))
        } catch (_: Exception) {}
    }
}
