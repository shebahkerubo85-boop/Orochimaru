package com.lagradost.cloudstream3.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Matrix
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.content.Intent
import android.content.BroadcastReceiver
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.animation.doOnEnd
import androidx.core.math.MathUtils.clamp
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.preference.PreferenceManager
import com.google.android.material.slider.Slider
import com.lagradost.cloudstream3.CommonActivity.screenHeightWithOrientation
import com.lagradost.cloudstream3.CommonActivity.screenWidthWithOrientation
import com.lagradost.cloudstream3.CommonActivity.showToast
import ani.sanin.R
import ani.sanin.brightnessConverter
import ani.sanin.getCurrentBrightnessValue
import ani.sanin.others.ResettableTimer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.getStatusBarHeight
import com.lagradost.cloudstream3.utils.Vector2
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handles all gesture, volume, brightness, speed-up, zoom, and hardware-key-event input.
 *
 * Ported from the anime ExoplayerView gesture system: per-area touch detection
 * (left = brightness, right = volume), double-tap seek, long-press fast-forward speed,
 * and Material Slider-based brightness/volume display.
 */
@OptIn(UnstableApi::class)
class PlayerGestureHelper(private val playerView: PlayerView) {

    companion object {
        const val MINIMUM_SEEK_TIME = 7000L
        const val DOUBLE_TAP_MAXIMUM_HOLD_TIME = 200L
        const val DOUBLE_TAP_MINIMUM_TIME_BETWEEN = 200L
        const val DOUBLE_TAP_PAUSE_PERCENTAGE = 0.15

        const val MINIMUM_ZOOM = 0.95f
        const val ZOOM_SNAP_SENSITIVITY = 0.07f
        const val MAXIMUM_ZOOM = 4.0f

        fun matrixToTranslationAndScale(matrix: Matrix): Triple<Float, Float, Float> {
            val points = floatArrayOf(0f, 0f, 1f, 1f)
            matrix.mapPoints(points)
            val translationX = points[0]
            val translationY = points[1]
            val scale = points[2] - translationX
            return Triple(translationX, translationY, scale)
        }
    }

    private val context: Context get() = playerView.context
    private val handler = Handler(Looper.getMainLooper())

    var isFullScreen: Boolean = false
    var isLocked: Boolean = false
    var isCurrentTouchValid = false
        private set

    /** Volume state */
    var currentRequestedVolume: Float = 0.0f
    var isVolumeLocked: Boolean = false
    var hasShownVolumeToast: Boolean = false
    private var loudnessEnhancer: LoudnessEnhancer? = null

    /** Brightness state */
    var currentRequestedBrightness: Float = 1.0f
    var currentExtraBrightness: Float = 0.0f
    var isBrightnessLocked: Boolean = false
    var hasShownBrightnessToast: Boolean = false
    var useTrueSystemBrightness: Boolean = true
    var brightnessOverlay: View? = null

    /** Settings */
    var speedupEnabled: Boolean = false
    var doubleTapEnabled: Boolean = false
    var doubleTapPauseEnabled: Boolean = false
    var fastForwardTime: Long = 10_000L

    /** Hold / speed-up */
    val holdHandler = Handler(Looper.getMainLooper())
    var hasTriggeredSpeedUp = false
    val holdRunnable = Runnable {
        playerView.player.setPlaybackSpeed(2.0f)
        showOrHideSpeedUp(true)
        playerView.callbacks?.onHoldSpeedUp(true)
        hasTriggeredSpeedUp = true
    }

    /** Zoom state */
    var videoOutline: View? = null
    var zoomMatrix: Matrix? = null
    var desiredMatrix: Matrix? = null
    var matrixAnimation: android.animation.ValueAnimator? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    var lastPan: Vector2? = null

    /** Double-tap state */
    private var doubleTapToken = 0
    private var tapCount = 0
    var lastTouchEndTime: Long = 0L

    /** Overlay layout listener */
    private var overlayLayoutListener: View.OnLayoutChangeListener? = null

    /** Anime-style gesture views */
    private var rewindArea: View? = null
    private var forwardArea: View? = null
    private var brightnessSlider: Slider? = null
    private var brightnessContainer: View? = null
    private var volumeSlider: Slider? = null
    private var volumeContainer: View? = null
    private var fastForwardText: TextView? = null
    private var fastRewindAnim: TextView? = null
    private var fastForwardAnim: TextView? = null

    /** Per-area seek state */
    private val seekTimerF = ResettableTimer()
    private val seekTimerR = ResettableTimer()
    private var seekTimesF = 0
    private var seekTimesR = 0
    private var isSeeking = false
    private var isFastForwarding = false
    private var fastForwardStartX = 0f
    private var fastForwardInitialSpeed = 1f
    private var fastForwardOriginalSpeed = 1f

    /** Brightness/volume hide timers */
    private var brightnessHideTimer: Timer? = null
    private var volumeHideTimer: Timer? = null

    // ──────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────

    fun initialize() {
        try {
            val sm = PreferenceManager.getDefaultSharedPreferences(context)
            speedupEnabled = sm.getBoolean(context.getString(R.string.speedup_key), false)
            doubleTapEnabled = sm.getBoolean(context.getString(R.string.double_tap_enabled_key), false)
            doubleTapPauseEnabled = sm.getBoolean(context.getString(R.string.double_tap_pause_enabled_key), false)
            fastForwardTime = sm.getInt(context.getString(R.string.double_tap_seek_time_key), 10).toLong() * 1000L
        } catch (_: Exception) {}

        safe {
            val pkg = context.packageName
            @SuppressLint("DiscouragedApi")
            val contentId = context.resources.getIdentifier("exo_content_frame", "id", pkg)
            val contentFrame = playerView.exoPlayerView?.findViewById<ViewGroup>(contentId)
            if (contentFrame != null) {
                brightnessOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
                @SuppressLint("InflateParams")
                brightnessOverlay = android.view.LayoutInflater.from(context)
                    .inflate(R.layout.extra_brightness_overlay, contentFrame, false)
                contentFrame.addView(brightnessOverlay)
            }
        }

        setupTouchGestures()
    }

    fun release() {
        safe {
            brightnessOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        brightnessOverlay = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        holdHandler.removeCallbacksAndMessages(null)
        clearZoomState()
        releaseOverlayLayoutListener()
        brightnessHideTimer?.cancel()
        volumeHideTimer?.cancel()
    }

    // ──────────────────────────────────────────────
    //  Key event listener
    // ──────────────────────────────────────────────

    private val keyEventListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.extras?.getInt(android.content.Intent.EXTRA_KEY_EVENT, 0)?.let { handleVolumeKey(it) }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupKeyEventListener() {
        val filter = android.content.IntentFilter(Intent.ACTION_MEDIA_BUTTON)
        try {
            context.registerReceiver(keyEventListener, filter)
        } catch (_: Exception) {}
    }

    fun releaseKeyEventListener() {
        try {
            context.unregisterReceiver(keyEventListener)
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────────────
    //  Speed-up
    // ──────────────────────────────────────────────

    fun showOrHideSpeedUp(show: Boolean) {
        playerView.playerSpeedupButton?.let { btn ->
            btn.clearAnimation()
            btn.alpha = if (show) 0f else 1f
            btn.isVisible = show
            btn.animate()
                .alpha(if (show) 1f else 0f)
                .setDuration(200L)
                .withEndAction { if (!show) btn.isVisible = false }
                .start()
        }
    }

    // ──────────────────────────────────────────────
    //  Volume
    // ──────────────────────────────────────────────

    fun verifyVolume() {
        ((context as? Activity)?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { am ->
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (cur < max || currentRequestedVolume <= 1.0f) {
                currentRequestedVolume = cur.toFloat() / max.toFloat()
                loudnessEnhancer?.release()
                loudnessEnhancer = null
            }
        }
    }

    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!isLayout(PHONE or EMULATOR) || !isFullScreen) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        verifyVolume()
        if (currentRequestedVolume <= 1.0f) hasShownVolumeToast = false
        isVolumeLocked = currentRequestedVolume < 1.0f
        handleVolumeAdjustment(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f, fromButton = true)
        return true
    }

    fun handleVolumeAdjustment(delta: Float, fromButton: Boolean) {
        val am = (context as? Activity)?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val curStep = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxStep = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val cur = currentRequestedVolume
        val locked = isVolumeLocked
        val next = (cur + delta).coerceIn(0.0f, if (locked) 1.0f else 2.0f)
        val nextStep = (next * maxStep.toFloat()).roundToInt().coerceIn(0, maxStep)

        if (fromButton || !locked) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, nextStep, 0)
        }

        val raw = cur + delta
        if (locked && raw > 1.0f && !hasShownVolumeToast) {
            showToast(R.string.slide_up_again_to_exceed_100)
            hasShownVolumeToast = true
        }

        var hasBoostError = false
        if (next > 1.0f && locked) {
            val boost = ((next - 1.0f) * 1000).toInt()
            val existing = loudnessEnhancer
            try {
                val sesId = (playerView.exoPlayerView?.player as? ExoPlayer)?.audioSessionId ?: 0
                val enhancer = existing ?: LoudnessEnhancer(sesId).also { loudnessEnhancer = it }
                enhancer.setTargetGain(boost)
                if (existing == null) enhancer.enabled = true
            } catch (e: Exception) {
                logError(e)
                hasBoostError = true
            }
        } else {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        }

        if (!hasBoostError) currentRequestedVolume = next

        // Update anime-style slider
        volumeSlider?.value = (next * 10f).coerceIn(0f, 10f)
        if (volumeContainer?.visibility != View.VISIBLE) {
            volumeContainer?.visibility = View.VISIBLE
            volumeContainer?.alpha = 1f
        }
        scheduleVolumeHide()
    }

    // ──────────────────────────────────────────────
    //  Brightness
    // ──────────────────────────────────────────────

    fun getBrightness(): Float? {
        return if (useTrueSystemBrightness) {
            try {
                Settings.System.getInt(
                    (context as? Activity)?.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (_: Exception) {
                useTrueSystemBrightness = false
                getBrightness()
            }
        } else {
            try {
                (context as? Activity)?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
    }

    fun setBrightness(brightness: Float) {
        if (useTrueSystemBrightness) {
            try {
                Settings.System.putInt(
                    (context as? Activity)?.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    (context as? Activity)?.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    min(1, (brightness.coerceIn(0.0f, 1.0f) * 255).toInt())
                )
            } catch (_: Exception) {
                useTrueSystemBrightness = false
                setBrightness(brightness)
            }
        } else {
            try {
                val lp = (context as? Activity)?.window?.attributes ?: return
                lp.screenBrightness = brightness.coerceIn(0.004f, 1.0f)
                (context as? Activity)?.window?.attributes = lp
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    fun handleBrightnessAdjustment(verticalAddition: Float) {
        val lastBrightness = currentRequestedBrightness
        val raw = currentRequestedBrightness + verticalAddition
        val next = raw.coerceIn(0.0f, 2.0f)

        currentRequestedBrightness = next
        if (lastBrightness != currentRequestedBrightness) setBrightness(currentRequestedBrightness)

        currentExtraBrightness = if (next > 1.0f) min(2.0f, next) - 1.0f else 0.0f
        brightnessOverlay?.alpha = currentExtraBrightness
        playerView.callbacks?.onBrightnessExtra(currentExtraBrightness)

        // Update anime-style slider
        brightnessSlider?.value = (next * 10f).coerceIn(0f, 10f)
        if (brightnessContainer?.visibility != View.VISIBLE) {
            brightnessContainer?.visibility = View.VISIBLE
            brightnessContainer?.alpha = 1f
        }
        scheduleBrightnessHide()
    }

    // ──────────────────────────────────────────────
    //  Brightness / Volume hide scheduling
    // ──────────────────────────────────────────────

    private fun scheduleBrightnessHide() {
        brightnessHideTimer?.cancel()
        brightnessHideTimer = Timer()
        brightnessHideTimer?.schedule(object : TimerTask() {
            override fun run() {
                handler.post {
                    brightnessContainer?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                        brightnessContainer?.visibility = View.GONE
                    }?.start()
                }
            }
        }, 3000)
    }

    private fun scheduleVolumeHide() {
        volumeHideTimer?.cancel()
        volumeHideTimer = Timer()
        volumeHideTimer?.schedule(object : TimerTask() {
            override fun run() {
                handler.post {
                    volumeContainer?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                        volumeContainer?.visibility = View.GONE
                    }?.start()
                }
            }
        }, 3000)
    }

    // ──────────────────────────────────────────────
    //  Zoom / Pan
    // ──────────────────────────────────────────────

    fun currentZoomMatrix(): Matrix {
        val current = zoomMatrix
        if (current != null) return current
        val exoView = playerView.exoPlayerView
        val videoView = exoView?.videoSurfaceView
        if (exoView == null || videoView == null ||
            exoView.resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            return Matrix()
        }
        val videoWidth = videoView.width.toFloat()
        val videoHeight = videoView.height.toFloat()
        val playerWidth = screenWidthWithOrientation.toFloat()
        val playerHeight = screenHeightWithOrientation.toFloat()
        if (videoWidth <= 1f || videoHeight <= 1f || playerWidth <= 1f || playerHeight <= 1f) {
            return Matrix()
        }
        val initAspect = (playerHeight * videoWidth) / (playerWidth * videoHeight)
        val aspect = max(initAspect, 1f / initAspect)
        return Matrix().apply { postScale(aspect, aspect) }
    }

    fun applyZoomMatrix(newMatrix: Matrix, animation: Boolean) {
        val exoView = playerView.exoPlayerView ?: return
        if (!animation) {
            matrixAnimation?.cancel()
            matrixAnimation = null
        }
        val (translationX, translationY, scale) = matrixToTranslationAndScale(newMatrix)
        if (exoView.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            exoView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        val videoView = exoView.videoSurfaceView ?: return
        val videoWidth = videoView.width.toFloat()
        val videoHeight = videoView.height.toFloat()
        val playerWidth = screenWidthWithOrientation.toFloat()
        val playerHeight = screenHeightWithOrientation.toFloat()
        if (videoWidth <= 1f || videoHeight <= 1f || playerWidth <= 1f || playerHeight <= 1f || scale <= 0.01f) return
        val initAspect = (playerHeight * videoWidth) / (playerWidth * videoHeight)
        val aspect = min(initAspect, 1f / initAspect)
        val scaledAspect = scale * aspect
        val maxTransX = max(0f, videoWidth * scaledAspect - playerWidth) * 0.5f
        val maxTransY = max(0f, videoHeight * scaledAspect - playerHeight) * 0.5f
        val expectedTranslationX = translationX.coerceIn(-maxTransX, maxTransX)
        val expectedTranslationY = translationY.coerceIn(-maxTransY, maxTransY)
        newMatrix.postTranslate(expectedTranslationX - translationX, expectedTranslationY - translationY)
        zoomMatrix = newMatrix

        if (!animation) {
            if ((scaledAspect - 1f).absoluteValue < ZOOM_SNAP_SENSITIVITY) {
                videoOutline?.isVisible = true
                val desired = Matrix()
                desired.setScale(1f / aspect, 1f / aspect)
                desiredMatrix = desired
            } else if (scale < 1f) {
                videoOutline?.isVisible = false
                desiredMatrix = Matrix()
            } else {
                videoOutline?.isVisible = false
                desiredMatrix = null
            }
        }
        videoView.scaleX = scaledAspect
        videoView.scaleY = scaledAspect
        videoView.translationX = expectedTranslationX
        videoView.translationY = expectedTranslationY
        updateBrightnessOverlayBounds()
    }

    fun clearZoomState() {
        matrixAnimation?.cancel()
        matrixAnimation = null
        zoomMatrix = null
        desiredMatrix = null
        scaleGestureDetector = null
        lastPan = null
        playerView.exoPlayerView?.videoSurfaceView?.apply {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }
    }

    fun resetZoomToDefault() {
        if (zoomMatrix != null) {
            clearZoomState()
            playerView.resize(PlayerResize.Fit, false)
        }
    }

    private fun updateBrightnessOverlayBounds() {
        val holder = playerView.exoPlayerView?.findViewById<ViewGroup>(
            context.resources.getIdentifier("exo_content_frame", "id", context.packageName)
        ) ?: return
        brightnessOverlay?.let { ov ->
            ov.layout(0, 0, holder.width, holder.height)
        }
    }

    fun requestUpdateBrightnessOverlayOnNextLayout() {
        val contentFrame = playerView.exoPlayerView?.findViewById<ViewGroup>(
            context.resources.getIdentifier("exo_content_frame", "id", context.packageName)
        ) ?: return
        releaseOverlayLayoutListener()
        overlayLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateBrightnessOverlayBounds()
        }
        contentFrame.addOnLayoutChangeListener(overlayLayoutListener)
    }

    fun releaseOverlayLayoutListener() {
        val contentFrame = playerView.exoPlayerView?.findViewById<ViewGroup>(
            context.resources.getIdentifier("exo_content_frame", "id", context.packageName)
        )
        overlayLayoutListener?.let { contentFrame?.removeOnLayoutChangeListener(it) }
        overlayLayoutListener = null
    }

    private fun handleZoomPanGesture(
        event: MotionEvent,
        ctx: Context,
        onFirstPointerDown: () -> Unit,
        onGestureEnd: () -> Unit,
    ): Boolean {
        scaleGestureDetector?.onTouchEvent(event)
        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPan = Vector2(event.x, event.y)
                onFirstPointerDown()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    lastPan = null
                    scaleGestureDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val factor = detector.scaleFactor
                            val current = currentZoomMatrix()
                            val (tx, ty, s) = matrixToTranslationAndScale(current)
                            val newScale = (s * factor).coerceIn(MINIMUM_ZOOM, MAXIMUM_ZOOM)
                            val newMatrix = Matrix()
                            newMatrix.postScale(newScale, newScale)
                            newMatrix.postTranslate(tx, ty)
                            applyZoomMatrix(newMatrix, false)
                            return true
                        }
                    })
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2) return true
                val prev = lastPan
                if (prev != null) {
                    val dx = event.x - prev.x
                    val dy = event.y - prev.y
                    val current = currentZoomMatrix()
                    val (tx, ty, s) = matrixToTranslationAndScale(current)
                    val newMatrix = Matrix()
                    newMatrix.postScale(s, s)
                    newMatrix.postTranslate(tx + dx, ty + dy)
                    applyZoomMatrix(newMatrix, false)
                    lastPan = Vector2(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPan = null
                scaleGestureDetector = null
                val desired = desiredMatrix
                if (desired != null) {
                    val current = currentZoomMatrix()
                    val (_, _, currentScale) = matrixToTranslationAndScale(current)
                    val (_, _, desiredScale) = matrixToTranslationAndScale(desired)
                    if ((currentScale - desiredScale).absoluteValue > ZOOM_SNAP_SENSITIVITY) {
                        matrixAnimation = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 300
                            addUpdateListener { anim ->
                                val fraction = anim.animatedValue as Float
                                val (cx, cy, cs) = matrixToTranslationAndScale(current)
                                val (dx, dy, ds) = matrixToTranslationAndScale(desired)
                                val interpCx = cx + (dx - cx) * fraction
                                val interpCy = cy + (dy - cy) * fraction
                                val interpCs = cs + (ds - cs) * fraction
                                val m = Matrix()
                                m.postScale(interpCs, interpCs)
                                m.postTranslate(interpCx, interpCy)
                                applyZoomMatrix(m, true)
                            }
                            doOnEnd {
                                matrixAnimation = null
                                onGestureEnd()
                            }
                            start()
                        }
                    } else {
                        onGestureEnd()
                    }
                } else {
                    onGestureEnd()
                }
            }
        }
        return true
    }

    // ──────────────────────────────────────────────
    //  Fast-forward / rewind buttons
    // ──────────────────────────────────────────────

    fun fastForward() {
        safe {
            val exoView = playerView.exoPlayerView ?: return@safe
            val ffwdHolder = exoView.findViewById<View>(R.id.exo_fast_forward)
            val ffwdText = exoView.findViewById<TextView>(R.id.exo_fast_forward_anim)
            val prevCenterMenuGone = playerView.playerCenterMenu?.visibility == View.GONE
            val prevVideoHolderVisible = playerView.playerVideoHolder?.isVisible ?: true
            val wasShowing = playerView.callbacks?.isUIShowing() == true

            ffwdHolder?.alpha = 1f
            ffwdText?.alpha = 1f
            ffwdText?.text = "+${fastForwardTime / 1000}"
            playerView.player.seekTime(fastForwardTime)

            handler.postDelayed({
                ffwdHolder?.animate()?.alpha(0f)?.setDuration(200)?.start()
                ffwdText?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    ffwdText?.text = context.getString(R.string.ffw_text_format).format(fastForwardTime / 1000)
                }?.start()
            }, 400)
        }
    }

    fun rewind() {
        safe {
            val exoView = playerView.exoPlayerView ?: return@safe
            val rewHolder = exoView.findViewById<View>(R.id.exo_fast_rewind)
            val rewText = exoView.findViewById<TextView>(R.id.exo_fast_rewind_anim)

            rewHolder?.alpha = 1f
            rewText?.alpha = 1f
            rewText?.text = "-${fastForwardTime / 1000}"
            playerView.player.seekTime(-fastForwardTime)

            handler.postDelayed({
                rewHolder?.animate()?.alpha(0f)?.setDuration(200)?.start()
                rewText?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                    rewText?.text = "-${fastForwardTime / 1000}"
                }?.start()
            }, 400)
        }
    }

    // ──────────────────────────────────────────────
    //  Missing helpers needed by FullScreenPlayer / ResultTrailerPlayer
    // ──────────────────────────────────────────────

    fun animateCenterControls(alpha: Float) {
        val exoView = playerView.exoPlayerView ?: return
        val controller = exoView.findViewById<View>(R.id.exo_controller_cont)
        val ffwd = exoView.findViewById<View>(R.id.exo_fast_forward)
        val rew = exoView.findViewById<View>(R.id.exo_fast_rewind)
        controller?.animate()?.alpha(alpha)?.setDuration(150)?.start()
        ffwd?.animate()?.alpha(alpha)?.setDuration(150)?.start()
        rew?.animate()?.alpha(alpha)?.setDuration(150)?.start()
    }

    fun resetFastForwardText() {
        val exoView = playerView.exoPlayerView ?: return
        exoView.findViewById<TextView>(R.id.exo_fast_forward_anim)?.text =
            context.getString(R.string.ffw_text_format).format(fastForwardTime / 1000)
    }

    fun resetRewindText() {
        val exoView = playerView.exoPlayerView ?: return
        exoView.findViewById<TextView>(R.id.exo_fast_rewind_anim)?.text =
            "-${fastForwardTime / 1000}"
    }

    // ──────────────────────────────────────────────
    //  Double-tap detection
    // ──────────────────────────────────────────────

    fun onTapDetected(x: Float, viewWidth: Int, isLocked: Boolean, onSingleTap: () -> Unit): Boolean {
        val anyDoubleTap = doubleTapEnabled || doubleTapPauseEnabled
        if (!anyDoubleTap) {
            onSingleTap()
            return false
        }
        val timeSinceLast = System.currentTimeMillis() - lastTouchEndTime
        return if (!isLocked && timeSinceLast < DOUBLE_TAP_MINIMUM_TIME_BETWEEN) {
            tapCount++
            doubleTapToken++
            if (doubleTapPauseEnabled) {
                when {
                    x < viewWidth / 2f - (DOUBLE_TAP_PAUSE_PERCENTAGE * viewWidth) -> {
                        if (doubleTapEnabled) rewind()
                    }
                    x > viewWidth / 2f + (DOUBLE_TAP_PAUSE_PERCENTAGE * viewWidth) -> {
                        if (doubleTapEnabled) fastForward()
                    }
                    else -> {
                        playerView.player.handleEvent(CSPlayerEvent.PlayPauseToggle, PlayerEventSource.UI)
                    }
                }
            } else if (doubleTapEnabled) {
                if (x < viewWidth / 2f) rewind() else fastForward()
            }
            true
        } else {
            tapCount = 0
            val token = ++doubleTapToken
            playerView.playerHolder?.postDelayed({
                if (token == doubleTapToken) onSingleTap()
            }, DOUBLE_TAP_MINIMUM_TIME_BETWEEN)
            false
        }
    }

    // ──────────────────────────────────────────────
    //  Touch gestures — anime per-area pattern
    // ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    fun setupTouchGestures() {
        val exoView = playerView.exoPlayerView ?: return
        val ctx = exoView.context

        rewindArea = exoView.findViewById(R.id.exo_rewind_area)
        forwardArea = exoView.findViewById(R.id.exo_forward_area)
        brightnessSlider = exoView.findViewById(R.id.exo_brightness)
        brightnessContainer = exoView.findViewById(R.id.exo_brightness_cont)
        volumeSlider = exoView.findViewById(R.id.exo_volume)
        volumeContainer = exoView.findViewById(R.id.exo_volume_cont)
        fastForwardText = exoView.findViewById(R.id.exo_fast_forward_text)
        fastRewindAnim = exoView.findViewById(R.id.exo_fast_rewind_anim)
        fastForwardAnim = exoView.findViewById(R.id.exo_fast_forward_anim)

        // Initialize sliders from current values
        brightnessSlider?.value = (getBrightness()?.coerceIn(0f, 1f) ?: 0.5f) * 10f
        brightnessSlider?.addOnChangeListener { _, value, _ ->
            // fromUser check is handled by the Slider framework
            setBrightness(value / 10f)
            currentRequestedBrightness = value / 10f
            scheduleBrightnessHide()
        }

        val am = (ctx as? Activity)?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volumeMax = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        volumeSlider?.value = (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / volumeMax * 10f
        volumeSlider?.addOnChangeListener { _, value, _ ->
            // fromUser check is handled by the Slider framework
            val vol = (value / 10f * volumeMax).toInt().coerceIn(0, volumeMax)
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            currentRequestedVolume = value / 10f
            scheduleVolumeHide()
        }

        // ── Fast-forward speed via long-press + horizontal drag ──
        val minLongPressSpeed = 0.25f
        val maxLongPressSpeed = 4f
        val dragSpeedSensitivity = 4f
        val minSpeedUpdateDelta = 0.01f
        val horizontalDeadZoneRatio = 0.03f
        var lastFastForwardSpeed = 1f

        fun currentPlaybackSpeed(): Float =
            playerView.player.getPlaybackSpeed()

        fun updateFastForwardText(speed: Float) {
            fastForwardText?.text = String.format(java.util.Locale.US, "%.2fx", speed)
        }

        fun fastForwardStart(event: MotionEvent) {
            isFastForwarding = true
            fastForwardStartX = event.rawX
            fastForwardOriginalSpeed = currentPlaybackSpeed()
            fastForwardInitialSpeed = clamp(fastForwardOriginalSpeed * 2f, minLongPressSpeed, maxLongPressSpeed)
            playerView.player.setPlaybackSpeed(fastForwardInitialSpeed)
            lastFastForwardSpeed = fastForwardInitialSpeed
            fastForwardText?.let { it.visibility = View.VISIBLE }
            updateFastForwardText(fastForwardInitialSpeed)
        }

        fun updateFastForwardSpeed(event: MotionEvent) {
            if (!isFastForwarding) return
            val width = exoView.width.toFloat().takeIf { it > 0f } ?: return
            val deltaX = event.rawX - fastForwardStartX
            if (abs(deltaX) < width * horizontalDeadZoneRatio) return
            val deltaRatio = deltaX / width
            val targetSpeed = clamp(
                fastForwardInitialSpeed + (deltaRatio * dragSpeedSensitivity),
                minLongPressSpeed, maxLongPressSpeed
            )
            if (abs(targetSpeed - lastFastForwardSpeed) < minSpeedUpdateDelta) return
            playerView.player.setPlaybackSpeed(targetSpeed)
            lastFastForwardSpeed = targetSpeed
            updateFastForwardText(targetSpeed)
        }

        fun stopFastForward() {
            if (isFastForwarding) {
                isFastForwarding = false
                playerView.player.setPlaybackSpeed(fastForwardOriginalSpeed)
                fastForwardText?.visibility = View.GONE
            }
        }

        // ── Double-tap seek per area ──
        fun seek(forward: Boolean, event: MotionEvent?) {
            val seekTimeSec = fastForwardTime / 1000
            val (text) = if (forward) {
                seekTimesF++
                fastForwardAnim to "+${seekTimeSec * seekTimesF}"
            } else {
                seekTimesR++
                fastRewindAnim to "-${seekTimeSec * seekTimesR}"
            }

            text?.let { tv ->
                tv.text = if (forward) "+${seekTimeSec * seekTimesF}" else "-${seekTimeSec * seekTimesR}"
                tv.alpha = 1f
            }

            if (forward) {
                playerView.player.seekTo(playerView.player.getPosition()!! + seekTimeSec * 1000)
            } else {
                playerView.player.seekTo(playerView.player.getPosition()!! - seekTimeSec * 1000)
            }

            isSeeking = true

            val resetTask = object : TimerTask() {
                override fun run() {
                    isSeeking = false
                    handler.post {
                        text?.alpha = 0f
                    }
                    if (forward) seekTimesF = 0 else seekTimesR = 0
                }
            }
            if (forward) {
                seekTimerF.reset(resetTask, 850)
            } else {
                seekTimerR.reset(resetTask, 850)
            }
        }

        // ── Left Panel: brightness + double-tap rewind + long-press speed ──
        val rewindDetector = GestureDetector(ctx, object : ani.sanin.GesturesListener() {
            override fun onLongClick(event: MotionEvent) {
                fastForwardStart(event)
            }
            override fun onDoubleClick(event: MotionEvent) {
                seek(false, event)
            }
            override fun onScrollYClick(y: Float) {
                brightnessSlider?.let { slider ->
                    slider.value = clamp(slider.value + y / 100, 0f, 10f)
                    if (brightnessContainer?.visibility != View.VISIBLE) {
                        brightnessContainer?.visibility = View.VISIBLE
                    }
                    brightnessContainer?.alpha = 1f
                    scheduleBrightnessHide()
                }
            }
            override fun onSingleClick(event: MotionEvent) {
                if (isSeeking) seek(false, event) else playerView.callbacks?.onSingleTap()
            }
        })

        rewindArea?.isClickable = true
        rewindArea?.setOnTouchListener { v, event ->
            rewindDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
            }
            v.performClick()
            true
        }

        // ── Right Panel: volume + double-tap forward + long-press speed ──
        val forwardDetector = GestureDetector(ctx, object : ani.sanin.GesturesListener() {
            override fun onLongClick(event: MotionEvent) {
                fastForwardStart(event)
            }
            override fun onDoubleClick(event: MotionEvent) {
                seek(true, event)
            }
            override fun onScrollYClick(y: Float) {
                volumeSlider?.let { slider ->
                    slider.value = clamp(slider.value + y / 100, 0f, 10f)
                    if (volumeContainer?.visibility != View.VISIBLE) {
                        volumeContainer?.visibility = View.VISIBLE
                    }
                    volumeContainer?.alpha = 1f
                    scheduleVolumeHide()
                }
            }
            override fun onSingleClick(event: MotionEvent) {
                if (isSeeking) seek(true, event) else playerView.callbacks?.onSingleTap()
            }
        })

        forwardArea?.isClickable = true
        forwardArea?.setOnTouchListener { v, event ->
            forwardDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_MOVE -> updateFastForwardSpeed(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopFastForward()
            }
            v.performClick()
            true
        }
    }

    // ──────────────────────────────────────────────
    //  Seek time helpers
    // ──────────────────────────────────────────────

    private fun forceLetters(inp: Long, letters: Int = 2): String {
        val added = letters - inp.toString().length
        return if (added > 0) "0".repeat(added) + inp.toString() else inp.toString()
    }

    private fun convertTimeToString(sec: Long): String {
        val rsec = sec % 60L
        val min = ceil((sec - rsec) / 60.0).toInt()
        val rmin = min % 60L
        val h = ceil((min - rmin) / 60.0).toLong()
        return (if (h > 0) forceLetters(h) + ":" else "") +
               (if (rmin >= 0 || h >= 0) forceLetters(rmin) + ":" else "") +
               forceLetters(rsec)
    }
}
