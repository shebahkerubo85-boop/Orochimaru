package ani.sanin.media.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib

/**
 * Video playback engine backed by **libmpv** (mpv-android).
 *
 * Ports the live-stream / HLS stack from Zangetsu's player_controller.dart
 * (`_configureMpv` + `_open`) to pure Kotlin.  Key properties:
 *
 *  - `cache` + `cache-secs`  — large read-ahead (60 s) absorbs CDN dips
 *  - `stream-lavf-o`        — per-segment reconnect for live HLS
 *  - `cache-pause`          — pause audio+video on underrun, resume in sync
 *  - `hwdec=mediacodec-copy`— routes frames through mpv's pipeline for A/V
 *                             sync recovery after a stall
 */
class MpvEngine(private val appContext: Context) : PlayerEngine {

    companion object {
        private const val TAG = "MpvEngine"
        // ── Zangetsu defaults ──────────────────────────────────────────
        private const val CACHE_SECS = 60
        private const val DEMUXER_MAX_BYTES = "128MiB"
        private const val DEMUXER_MAX_BACK_BYTES = "48MiB"
        private const val ANALYZEDURATION_US = 2_000_000  // 2 s
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

    // ── Lifecycle ─────────────────────────────────────────────────────

    init {
        MPVLib.create(appContext.applicationContext)
        MPVLib.init()
        MPVLib.addObserver(eventObserver)
        applyGlobalConfig()
        Log.i(TAG, "mpv created + initialised")
    }

    private fun applyGlobalConfig() {
        try {
            // ── Cache / read-ahead (Zangetsu _configureMpv) ──────────
            MPVLib.setPropertyString("cache", "yes")
            MPVLib.setPropertyString("cache-secs", "$CACHE_SECS")
            MPVLib.setPropertyString("demuxer-readahead-secs", "$CACHE_SECS")
            MPVLib.setPropertyString("demuxer-max-bytes", DEMUXER_MAX_BYTES)
            MPVLib.setPropertyString("demuxer-max-back-bytes", DEMUXER_MAX_BACK_BYTES)
            // Pause audio+video together on underrun, wait 2 s, resume in sync.
            MPVLib.setPropertyString("cache-pause", "yes")
            MPVLib.setPropertyString("cache-pause-wait", "2")

            // ── A/V sync after mid-stream stall ─────────────────────
            // mediacodec-copy routes frames through mpv's pipeline so audio
            // doesn't run ahead after a decoder freeze.
            MPVLib.setPropertyString("hwdec", "mediacodec-copy")

            // ── Force-seekable ───────────────────────────────────────
            MPVLib.setPropertyString("force-seekable", "yes")

            // ── Reconnect (Zangetsu stream-lavf-o) ───────────────────
            MPVLib.setPropertyString(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1," +
                    "reconnect_on_network_error=1,reconnect_delay_max=5"
            )

            // ── Demuxer tuning ───────────────────────────────────────
            MPVLib.setPropertyString(
                "demuxer-lavf-o",
                "extension_picky=0,allowed_extensions=ALL," +
                    "http_persistent=0,analyzeduration=$ANALYZEDURATION_US"
            )

            // ── Software decode fallback ─────────────────────────────
            MPVLib.setPropertyString("vd-lavc-threads", "4")
            MPVLib.setPropertyString("vd-lavc-skiploopfilter", "nonkey")
            MPVLib.setPropertyString("vd-lavc-fast", "yes")

            // ── Volume ───────────────────────────────────────────────
            MPVLib.setPropertyString("volume-max", "200")
            MPVLib.setPropertyString("audio-pitch-correction", "yes")

            // ── Observe properties so we get callbacks ────────────────
            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        } catch (e: Exception) {
            Log.e(TAG, "applyGlobalConfig failed", e)
        }
    }

    // ── Surface ───────────────────────────────────────────────────────

    override fun setSurface(surface: Surface?) {
        if (surface != null) MPVLib.attachSurface(surface)
        else MPVLib.detachSurface()
    }

    // ── Source / playback ─────────────────────────────────────────────

    override fun setMediaSource(
        url: String,
        headers: Map<String, String>?,
        mimeType: String?,
        subs: List<Any>?
    ) {
        // Per-source demuxer tuning: HLS uses http_persistent=0 for
        // anti-leech CDNs; non-HLS keeps persistent connections.
        val isHls = url.contains(".m3u8", ignoreCase = true)
        MPVLib.setPropertyString(
            "demuxer-lavf-o",
            (if (isHls) {
                "extension_picky=0,allowed_extensions=ALL,http_persistent=0," +
                    "analyzeduration=$ANALYZEDURATION_US"
            } else {
                "extension_picky=0,allowed_extensions=ALL,analyzeduration=$ANALYZEDURATION_US"
            })
        )
        // Non-HLS: allow reconnect for progressive MP4 file hosts.
        if (!isHls) {
            MPVLib.setPropertyString(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1," +
                    "reconnect_delay_max=30"
            )
        } else {
            MPVLib.setPropertyString(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1," +
                    "reconnect_delay_max=5"
            )
        }
    }

    override fun prepare() {
        // mpv loads on command(); prepare() is a no-op here.
    }

    /** Actually open a URL in mpv. Called after setMediaSource + setSurface. */
    fun openUrl(url: String, headers: Map<String, String>?) {
        // Build mpv command: loadfile <url> replace
        val args = mutableListOf("loadfile", url, "replace")
        // mpv doesn't have per-request headers natively; if headers contain
        // Cookie/Auth we pass them via http-header-fields (comma-separated).
        if (!headers.isNullOrEmpty()) {
            val hdrStr = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
            MPVLib.setPropertyString("http-header-fields", hdrStr)
        }
        MPVLib.command(args.toTypedArray())
    }

    // ── Transport ─────────────────────────────────────────────────────

    override fun play() {
        MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) {
        MPVLib.command(arrayOf("seek", "${positionMs}ms", "absolute"))
    }

    override fun setSpeed(speed: Float) {
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    override fun setVolume(volume: Float) {
        MPVLib.setPropertyDouble("volume", (volume * 100.0).coerceIn(0.0, 200.0))
    }

    override fun setAudioSessionId(sessionId: Int) {
        // mpv uses Android's AudioTrack directly; session ID is set via
        // option at create time or here if the activity provides it.
        // This is best-effort — mpv doesn't expose a setAudioSessionId API.
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
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    Log.i(TAG, "FILE_LOADED")
                    notifyState(PlayerEngine.State.READY)
                    // Read initial video size
                    try {
                        val w = MPVLib.getPropertyInt("width") ?: 0
                        val h = MPVLib.getPropertyInt("height") ?: 0
                        if (w > 0 && h > 0) notifyVideoSize(w, h)
                    } catch (_: Exception) {}
                }
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    Log.i(TAG, "START_FILE → BUFFERING")
                    notifyState(PlayerEngine.State.BUFFERING)
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    Log.i(TAG, "END_FILE")
                    notifyState(PlayerEngine.State.ENDED)
                }
                MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                    val snapshot: List<PlayerEngine.Listener>
                    synchronized(listeners) { snapshot = listeners.toList() }
                    mainHandler.post {
                        snapshot.forEach { it.onPositionDiscontinuity(0) }
                    }
                }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    notifyState(PlayerEngine.State.READY)
                }
            }
        }

        override fun eventProperty(property: String) { /* no-op */ }

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
                    // core-idle=true means buffering; false means decoding/playing
                    if (!value && _state != PlayerEngine.State.READY) {
                        notifyState(PlayerEngine.State.READY)
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) { /* no-op */ }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "duration" -> _durationMs = (value * 1000).toLong()
                "time-pos" -> _currentPositionMs = (value * 1000).toLong()
            }
        }
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
        // Don't call MPVLib.destroy() — it's global; only call on app exit.
        Log.i(TAG, "mpv engine released")
    }
}
