package ani.sanin.youtube

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.reels.ReelFeed
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen vertical reels player.
 *
 * Playback is a YouTube iframe driven entirely by `postMessage`, but every piece of YouTube's own
 * chrome is suppressed: the embed runs with `controls=0`, the item lays a transparent blocker over
 * the WebView so nothing inside the frame is tappable, and the activity paints its own transport
 * bar. Player state and progress are pushed back from the page through a small `Android` JS bridge,
 * which is what lets us draw a real progress bar and auto-advance when a reel ends (masking
 * YouTube's end-screen recommendations).
 */
class YouTubeShortsPlayerActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ShortsAdapter

    private lateinit var titleText: TextView
    private lateinit var channelText: TextView
    private lateinit var playPause: ImageButton
    private lateinit var muteButton: ImageButton
    private lateinit var progress: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var loading: ProgressBar
    private lateinit var commentsPanel: LinearLayout
    private lateinit var btnLike: ImageButton
    private lateinit var btnDislike: ImageButton

    private var isPlaying = false
    private var isMuted = false
    private var userSeeking = false

    private val liked = mutableSetOf<String>()
    private val disliked = mutableSetOf<String>()

    /** Reels mode appends more anime as the viewer nears the end; the shorts feature does not. */
    private var reelMode = false
    private val seenReelKeys = HashSet<String>()
    private var reelPage = 0
    private var reelLoading = false
    private var reelExhausted = false

    private val hidePlayPause = Runnable { playPause.isVisible = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        setContentView(R.layout.activity_youtube_shorts_player)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        val videoIds = intent.getStringArrayListExtra(EXTRA_VIDEO_IDS) ?: arrayListOf()
        val startIdx = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        reelMode = intent.getBooleanExtra(EXTRA_REEL_MODE, false)

        if (videoIds.isEmpty()) { finish(); return }

        seenReelKeys.addAll(videoIds)

        recycler = findViewById(R.id.shortsRecyclerView)
        titleText = findViewById(R.id.shortTitleText)
        channelText = findViewById(R.id.shortChannelText)
        playPause = findViewById(R.id.playerPlayPause)
        muteButton = findViewById(R.id.playerMute)
        progress = findViewById(R.id.playerProgress)
        timeCurrent = findViewById(R.id.playerTimeCurrent)
        timeDuration = findViewById(R.id.playerTimeDuration)
        loading = findViewById(R.id.playerLoading)
        commentsPanel = findViewById(R.id.commentsPanel)
        btnLike = findViewById(R.id.btnLike)
        btnDislike = findViewById(R.id.btnDislike)

        adapter = ShortsAdapter(videoIds, titles)
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recycler.adapter = adapter

        // PagerSnapHelper (not LinearSnapHelper): a reel is a full-screen page, so a fling must
        // land on the single adjacent item. LinearSnapHelper picks its target from fling velocity
        // and could skip several videos on a small swipe.
        PagerSnapHelper().attachToRecyclerView(recycler)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (!reelMode) return
                val last = (rv.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
                if (last >= adapter.itemCount - REEL_PREFETCH_DISTANCE) loadMoreReels()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                    if (pos != RecyclerView.NO_POSITION) {
                        adapter.setCurrent(pos)
                        onCurrentChanged(pos)
                        adapter.playCurrent()
                    }
                }
            }
        })

        // Single tap toggles play/pause. Fed from the intercept hook so it never eats the drags
        // the pager needs.
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }
        })
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                tapDetector.onTouchEvent(e)
                return false
            }
        })

        setUpControls()

        recycler.post {
            recycler.scrollToPosition(startIdx)
            adapter.setCurrent(startIdx)
            onCurrentChanged(startIdx)
            adapter.playCurrent()
        }
    }

    private fun setUpControls() {
        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener { finish() }
        playPause.setOnClickListener { togglePlayPause() }
        muteButton.setOnClickListener {
            isMuted = !isMuted
            adapter.setMuted(isMuted)
            muteButton.setImageResource(
                if (isMuted) R.drawable.ic_baseline_volume_mute_24
                else R.drawable.ic_baseline_volume_up_24
            )
        }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(value / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                adapter.seekTo(sb.progress / 1000f)
            }
        })

        btnLike.setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            if (id in liked) liked.remove(id) else { liked.add(id); disliked.remove(id) }
            updateActionIcons()
        }
        btnDislike.setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            if (id in disliked) disliked.remove(id) else { disliked.add(id); liked.remove(id) }
            updateActionIcons()
        }
        findViewById<ImageButton>(R.id.btnComments).setOnClickListener {
            commentsPanel.isVisible = !commentsPanel.isVisible
        }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener {
            val id = adapter.currentVideoId() ?: return@setOnClickListener
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        }
    }

    private fun onCurrentChanged(position: Int) {
        titleText.text = adapter.titleAt(position)
        channelText.text = "Aniphex"
        userSeeking = false
        progress.progress = 0
        timeCurrent.text = formatTime(0)
        timeDuration.text = formatTime(0)
        loading.isVisible = true
        updateActionIcons()
        adapter.setMuted(isMuted)
    }

    private fun updateActionIcons() {
        val id = adapter.currentVideoId()
        btnLike.setColorFilter(
            if (id != null && id in liked) android.graphics.Color.parseColor("#FF4CAF50")
            else android.graphics.Color.WHITE
        )
        btnDislike.setColorFilter(
            if (id != null && id in disliked) android.graphics.Color.parseColor("#FFE53935")
            else android.graphics.Color.WHITE
        )
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            adapter.pauseCurrent()
            showPlayPause(R.drawable.ic_baseline_play_arrow_24, persist = true)
        } else {
            adapter.playCurrent()
            showPlayPause(R.drawable.ic_baseline_pause_24, persist = false)
        }
    }

    private fun showPlayPause(icon: Int, persist: Boolean) {
        playPause.setImageResource(icon)
        playPause.isVisible = true
        playPause.removeCallbacks(hidePlayPause)
        if (!persist) playPause.postDelayed(hidePlayPause, 900)
    }

    /** Callback from the page's player state (0 ended, 1 playing, 2 paused, 3 buffering, 5 cued). */
    fun onPlayerState(state: Int) {
        when (state) {
            1 -> {
                isPlaying = true
                loading.isVisible = false
                showPlayPause(R.drawable.ic_baseline_pause_24, persist = false)
            }
            2 -> {
                isPlaying = false
                loading.isVisible = false
                showPlayPause(R.drawable.ic_baseline_play_arrow_24, persist = true)
            }
            3 -> loading.isVisible = true
            0 -> {
                isPlaying = false
                onReelEnded()
            }
        }
    }

    /** Callback with the page's playback head, in seconds. */
    fun onPlayerProgress(currentSeconds: Double, durationSeconds: Double) {
        if (userSeeking) return
        val duration = durationSeconds.toInt()
        val current = currentSeconds.toInt()
        if (duration > 0) {
            progress.max = duration * 1000
            progress.progress = current * 1000
            timeDuration.text = formatTime(duration)
        }
        timeCurrent.text = formatTime(current)
    }

    private fun onReelEnded() {
        val next = adapter.currentPos + 1
        if (next < adapter.itemCount) {
            recycler.smoothScrollToPosition(next)
        } else if (reelMode && !reelExhausted) {
            loadMoreReels {
                if (adapter.currentPos + 1 < adapter.itemCount) {
                    recycler.smoothScrollToPosition(adapter.currentPos + 1)
                }
            }
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "0:00"
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /**
     * Appends the next mixed batch of anime videos.
     *
     * Pages the AniList pool, resolves each show's videos and interleaves them, skipping keys
     * already queued so the rail's seed feed and earlier batches never repeat.
     */
    private fun loadMoreReels(onAppended: (() -> Unit)? = null) {
        if (reelLoading || reelExhausted) return
        reelLoading = true
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { nextReelBatch() }
            if (items.isEmpty()) {
                reelExhausted = true
            } else {
                adapter.appendItems(items.map { it.key }, items.map { it.animeTitle })
            }
            reelLoading = false
            onAppended?.invoke()
        }
    }

    private suspend fun nextReelBatch(): List<ReelFeed.ReelItem> {
        var attempts = 0
        while (attempts++ < REEL_MAX_EMPTY_PAGES) {
            val page = ++reelPage
            val pool = runCatching { ReelFeed.pool(page = page, perPage = 30) }.getOrDefault(emptyList())
            if (pool.isEmpty()) return emptyList()
            val fresh = runCatching { ReelFeed.buildInterleaved(pool) }.getOrDefault(emptyList())
                .filter { seenReelKeys.add(it.key) }
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) adapter.pauseCurrent()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::adapter.isInitialized) adapter.destroyAll()
    }

    inner class ShortsAdapter(
        private val videoIds: MutableList<String>,
        private val titles: MutableList<String>
    ) : RecyclerView.Adapter<ShortsAdapter.VH>() {

        private val webViews = mutableMapOf<Int, WebView>()
        var currentPos = -1

        fun currentVideoId(): String? = videoIds.getOrNull(currentPos)

        fun titleAt(position: Int): String = titles.getOrElse(position) { "" }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_youtube_short_player, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videoIds.size

        /** Appends a batch to the end of the feed without disturbing what is on screen. */
        fun appendItems(newIds: List<String>, newTitles: List<String>) {
            if (newIds.isEmpty()) return
            val start = videoIds.size
            videoIds.addAll(newIds)
            titles.addAll(newTitles)
            notifyItemRangeInserted(start, newIds.size)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.boundPosition = position
            val videoId = videoIds[position]
            if (holder.loadedVideoId != videoId) {
                holder.loadedVideoId = videoId
                setupWebView(holder, videoId, position)
            }
            webViews[position] = holder.youtubeWebView
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            val pos = holder.boundPosition
            if (pos != RecyclerView.NO_POSITION && webViews[pos] === holder.youtubeWebView) {
                webViews.remove(pos)
            }
            holder.loadedVideoId = null
            holder.youtubeWebView.stopLoading()
            holder.youtubeWebView.loadUrl("about:blank")
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun setupWebView(holder: VH, videoId: String, position: Int) {
            val webView = holder.youtubeWebView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            }
            webView.addJavascriptInterface(makeBridge(position), "Android")
            webView.loadDataWithBaseURL(
                "https://www.youtube-nocookie.com", buildHtml(videoId), "text/html", "utf-8", null
            )
            webViews[position] = webView
        }

        /**
         * Bridge from the page back into the activity. Each WebView gets its own instance bound to
         * its position so stray events from pre-loaded neighbours are ignored.
         */
        private fun makeBridge(position: Int): Any = object {
            @JavascriptInterface
            fun onReady() {
                runOnUiThread { if (position == currentPos) { loading.isVisible = false; playCurrent() } }
            }

            @JavascriptInterface
            fun onState(state: String) {
                val s = state.toDoubleOrNull()?.toInt() ?: return
                runOnUiThread { if (position == currentPos) onPlayerState(s) }
            }

            @JavascriptInterface
            fun onProgress(currentSeconds: String, durationSeconds: String) {
                val cur = currentSeconds.toDoubleOrNull() ?: return
                val dur = durationSeconds.toDoubleOrNull() ?: 0.0
                runOnUiThread { if (position == currentPos) onPlayerProgress(cur, dur) }
            }
        }

        fun setCurrent(position: Int) { currentPos = position }

        fun playCurrent() {
            val webView = webViews[currentPos] ?: return
            webViews.forEach { (pos, wv) -> if (pos != currentPos) command(wv, "pause()") }
            command(webView, "play()")
        }

        fun pauseCurrent() {
            webViews[currentPos]?.let { command(it, "pause()") }
        }

        fun seekTo(seconds: Float) {
            webViews[currentPos]?.let { command(it, "seek($seconds)") }
        }

        fun setMuted(muted: Boolean) {
            webViews[currentPos]?.let { command(it, if (muted) "mute()" else "unMute()") }
        }

        private fun command(webView: WebView, call: String) {
            webView.evaluateJavascript("window.cmd && window.cmd.$call", null)
        }

        fun destroyAll() {
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }

        private fun buildHtml(videoId: String): String = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}iframe{width:100%;height:100%;border:0;display:block}</style>
</head><body>
<iframe id="yt" src="https://www.youtube-nocookie.com/embed/$videoId?enablejsapi=1&controls=0&rel=0&iv_load_policy=3&disablekb=1&playsinline=1&fs=0&cc_load_policy=0&origin=https%3A%2F%2Fwww.youtube-nocookie.com" allow="autoplay; encrypted-media" frameborder="0"></iframe>
<script>
(function(){
  var f=document.getElementById('yt');
  function send(m){ try{ f.contentWindow.postMessage(JSON.stringify(m),'*'); }catch(e){} }
  window.cmd={
    play:function(){send({event:'command',func:'playVideo',args:[]})},
    pause:function(){send({event:'command',func:'pauseVideo',args:[]})},
    seek:function(s){send({event:'command',func:'seekTo',args:[s,true]})},
    mute:function(){send({event:'command',func:'mute',args:[]})},
    unMute:function(){send({event:'command',func:'unMute',args:[]})}
  };
  function listen(){ send({event:'listening',id:1,channel:'widget'}); }
  f.addEventListener('load',function(){ listen(); setTimeout(listen,300); });
  window.addEventListener('message',function(e){
    if(e.source!==f.contentWindow) return;
    var d; try{ d=JSON.parse(e.data); }catch(err){ return; }
    if(d.event==='onReady'){ Android.onReady(); }
    else if(d.event==='onStateChange' && typeof d.info==='number'){ Android.onState(''+d.info); }
    else if(d.event==='infoDelivery' && d.info){
      if(typeof d.info.playerState==='number'){ Android.onState(''+d.info.playerState); }
      if(typeof d.info.currentTime==='number'){ Android.onProgress(''+d.info.currentTime, ''+(d.info.duration||0)); }
    }
  });
})();
</script>
</body></html>
""".trimIndent()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val youtubeWebView: WebView = view.findViewById(R.id.youtubeWebView)
            var boundPosition = RecyclerView.NO_POSITION
            var loadedVideoId: String? = null
        }
    }

    companion object {
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_REEL_MODE = "reel_mode"

        /** How close to the end of the feed the next batch is fetched. */
        private const val REEL_PREFETCH_DISTANCE = 4

        /** How many empty pool pages to skip before giving up on an append. */
        private const val REEL_MAX_EMPTY_PAGES = 4
    }
}
