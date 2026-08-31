package ani.sanin.media.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPV

/**
 * Video playback engine backed by **libmpv** (mpv-android 0.1.9).
 *
 * Ports the live-stream / HLS stack from Zangetsu's player to pure Kotlin.
 * Key properties:
 *  - `cache` + `cache-secs`  — large read-ahead (60 s) absorbs CDN dips
 *  - `stream-lavf-o`        — per-segment reconnect for live HLS
 *  - `cache-pause`          — pause audio+video on underrun, resume in sync
 *  - `hwdec=mediacodec-copy`— routes frames through mpv's pipeline for A/V
 *                             sync recovery after a stall
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

    lateinit var mpv: MPV
        private set

    init {
        mpv = MPV(appContext.applicationContext)
        applyGlobalConfig()
        mpv.initSession()
        mpv.addObserver(eventObserver)
        Log.i(TAG, "mpv created + initialised")
    }

    private fun applyGlobalConfig() {
        try {
            mpv.setPropertyString("cache", "yes")
            mpv.setPropertyString("cache-secs", "$CACHE_SECS")
            mpv.setPropertyString("demuxer-readahead-secs", "$CACHE_SECS")
            mpv.setPropertyString("demuxer-max-bytes", DEMUXER_MAX_BYTES)
            mpv.setPropertyString("demuxer-max-back-bytes", DEMUXER_MAX_BACK_BYTES)
            mpv.setPropertyString("cache-pause", "yes")
            mpv.setPropertyString("cache-pause-wait", "2")
            mpv.setPropertyString("hwdec", "mediacodec-copy")
            mpv.setPropertyString("force-seekable", "yes")

            mpv.setPropertyString(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1," +
                    "reconnect_on_network_error=1,reconnect_delay_max=5"
            )
            mpv.setPropertyString(
                "demuxer-lavf-o",
                "extension_picky=0,allowed_extensions=ALL," +
                    "http_persistent=0,analyzeduration=$ANALYZEDURATION_US"
            )

            mpv.setPropertyString("vd-lavc-threads", "4")
            mpv.setPropertyString("vd-lavc-skiploopfilter", "nonkey")
            mpv.setPropertyString("vd-lavc-fast", "yes")
            mpv.setPropertyString("volume-max", "200")
            mpv.setPropertyString("audio-pitch-correction", "yes")

            mpv.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("width", MPV.mpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty("height", MPV.mpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty("core-idle", MPV.mpvFormat.MPV_FORMAT_FLAG)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply global config", e)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun setSurface(surface: Surface?) {
        if (_released) return
        if (surface != null) {
            mpv.attachSurface(surface)
        } else {
            mpv.detachSurface()
        }
    }

    override fun setMediaSource(
        url: String,
        headers: Map<String, String>?,
        mimeType: String?,
        subs: List<Any>?
    ) {
        // Headers are set at openUrl time via http-header-fields
    }

    override fun prepare() {
        // no-op; prepare happens in openUrl
    }

    /** Actually open a URL in mpv. Called after setSurface. */
    fun openUrl(url: String, headers: Map<String, String>?) {
        if (_released) return
        try {
            if (!headers.isNullOrEmpty()) {
                val headerStr = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
                mpv.setPropertyString("http-header-fields", headerStr)
            }
            mpv.command("loadfile", url)
        } catch (e: Exception) {
            Log.e(TAG, "openUrl failed", e)
            notifyError(e.message)
        }
    }

    override fun play() {
        if (_released) return
        mpv.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        if (_released) return
        mpv.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) {
        if (_released) return
        mpv.setPropertyDouble("seekto", positionMs / 1000.0)
    }

    override fun setSpeed(speed: Float) {
        if (_released) return
        mpv.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setVolume(volume: Float) {
        if (_released) return
        mpv.setPropertyDouble("volume", (volume * 100.0).coerceIn(0.0, 200.0))
    }

    override fun setAudioSessionId(sessionId: Int) {
        // mpv uses Android's AudioTrack directly; session ID is not directly settable.
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

    // ── MPV event observer ────────────────────────────────────────────

    private val eventObserver = object : MPV.EventObserver {
        override fun event(eventId: Int, data: `is`.xyz.mpv.MPVNode) {
            when (eventId) {
                MPV.mpvEvent.MPV_EVENT_FILE_LOADED -> {
                    Log.i(TAG, "FILE_LOADED")
                    notifyState(PlayerEngine.State.READY)
                    try {
                        val w = mpv.getPropertyInt("width") ?: 0
                        val h = mpv.getPropertyInt("height") ?: 0
                        if (w > 0 && h > 0) notifyVideoSize(w, h)
                    } catch (_: Exception) {}
                }
                MPV.mpvEvent.MPV_EVENT_START_FILE -> {
                    Log.i(TAG, "START_FILE -> BUFFERING")
                    notifyState(PlayerEngine.State.BUFFERING)
                }
                MPV.mpvEvent.MPV_EVENT_END_FILE -> {
                    Log.i(TAG, "END_FILE")
                    notifyState(PlayerEngine.State.ENDED)
                }
                MPV.mpvEvent.MPV_EVENT_SEEK -> {
                    val snapshot: List<PlayerEngine.Listener>
                    synchronized(listeners) { snapshot = listeners.toList() }
                    mainHandler.post {
                        snapshot.forEach { it.onPositionDiscontinuity(0) }
                    }
                }
                MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
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
        mpv.removeObserver(eventObserver)
        try {
            mpv.detachSurface()
            mpv.command("stop")
        } catch (_: Exception) {}
    }
}
