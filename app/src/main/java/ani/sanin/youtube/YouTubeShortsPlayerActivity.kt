package ani.sanin.youtube

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.themes.ThemeManager

class YouTubeShortsPlayerActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ShortsAdapter

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
        val isReddit = (intent.getStringArrayListExtra(EXTRA_IS_REDDIT) ?: arrayListOf()).map { it.toBoolean() }
        val redditVideos = intent.getStringArrayListExtra(EXTRA_REDDIT_VIDEO) ?: arrayListOf()

        if (videoIds.isEmpty()) { finish(); return }

        recycler = findViewById(R.id.shortsRecyclerView)
        adapter = ShortsAdapter(videoIds, titles, isReddit, redditVideos)
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recycler.adapter = adapter

        LinearSnapHelper().attachToRecyclerView(recycler)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = (rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                    adapter.setCurrent(pos)
                    adapter.playCurrent()
                }
            }
        })

        recycler.post {
            recycler.scrollToPosition(startIdx)
            adapter.setCurrent(startIdx)
            adapter.playCurrent()
        }
        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener { finish() }
    }

    override fun onPause() {
        super.onPause()
        adapter.pauseCurrent()
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.destroyAll()
    }

    inner class ShortsAdapter(
        private val videoIds: List<String>,
        private val titles: List<String>,
        private val isReddit: List<Boolean>,
        private val redditVideos: List<String>
    ) : RecyclerView.Adapter<ShortsAdapter.VH>() {

        private val exoPlayers = mutableMapOf<Int, ExoPlayer>()
        private val webViews = mutableMapOf<Int, WebView>()
        private var currentPos = -1
        private val liked = mutableSetOf<Int>()
        private val disliked = mutableSetOf<Int>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_youtube_short_player, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videoIds.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val videoId = videoIds[position]
            val reddit = isReddit.getOrElse(position) { false }
            holder.titleText.text = titles.getOrElse(position) { "" }
            holder.channelText.text = if (reddit) "r/Animeedits" else "Aniphex"

            holder.btnLike.setColorFilter(
                if (position in liked) android.graphics.Color.parseColor("#FF4CAF50")
                else android.graphics.Color.WHITE
            )
            holder.btnDislike.setColorFilter(
                if (position in disliked) android.graphics.Color.parseColor("#FFE53935")
                else android.graphics.Color.WHITE
            )

            holder.btnLike.setOnClickListener { toggleLike(position, holder) }
            holder.btnDislike.setOnClickListener { toggleDislike(position, holder) }
            holder.btnComments.setOnClickListener {
                holder.commentsPanel.visibility =
                    if (holder.commentsPanel.isVisible) View.GONE else View.VISIBLE
            }
            holder.btnShare.setOnClickListener {
                val intent = if (reddit) {
                    Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.reddit.com${redditVideos.getOrElse(position) { "" }}"))
                } else {
                    Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                }
                startActivity(intent)
            }

            // Show/hide player types
            if (reddit) {
                holder.youtubeWebView.visibility = View.GONE
                holder.redditPlayer.visibility = View.VISIBLE
                setupRedditPlayer(holder, position)
            } else {
                holder.redditPlayer.visibility = View.GONE
                holder.youtubeWebView.visibility = View.VISIBLE
                setupWebView(holder, videoId, position)
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            val pos = holder.adapterPosition
            exoPlayers[pos]?.release()
            exoPlayers.remove(pos)
            webViews[pos]?.destroy()
            webViews.remove(pos)
        }

        private fun toggleLike(position: Int, holder: VH) {
            if (position in liked) {
                liked.remove(position)
            } else {
                liked.add(position)
                disliked.remove(position)
            }
            notifyItemChanged(position)
        }

        private fun toggleDislike(position: Int, holder: VH) {
            if (position in disliked) {
                disliked.remove(position)
            } else {
                disliked.add(position)
                liked.remove(position)
            }
            notifyItemChanged(position)
        }

        private fun setupRedditPlayer(holder: VH, position: Int) {
            val url = redditVideos.getOrElse(position) { "" }
            if (url.isBlank()) {
                holder.loading.visibility = View.GONE
                return
            }
            holder.loading.visibility = View.VISIBLE
            val player = ExoPlayer.Builder(holder.itemView.context).build()
            holder.redditPlayer.player = player
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        holder.loading.visibility = View.GONE
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    holder.loading.visibility = View.GONE
                }
            })
            player.prepare()
            exoPlayers[position] = player
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
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    holder.loading.visibility = View.GONE
                    if (position == currentPos) {
                        playCurrent()
                    }
                }
            }
            webView.setBackgroundColor(android.graphics.Color.BLACK)

            val html = """
                <!DOCTYPE html>
                <html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>*{margin:0;padding:0;box-sizing:border-box;}
                    html,body{width:100%;height:100%;background:#000;overflow:hidden;}
                    iframe{width:100%;height:100%;border:none;display:block;}</style>
                </head><body>
                    <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=0&rel=0&modestbranding=1&controls=1&fs=0&playsinline=1&enablejsapi=1"
                    allow="autoplay; encrypted-media; picture-in-picture" frameborder="0"></iframe>
                </body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            webViews[position] = webView
        }

        fun setCurrent(position: Int) { currentPos = position }

        fun playCurrent() {
            if (currentPos < 0 || currentPos >= videoIds.size) return
            val position = currentPos
            // Pause all ExoPlayers except current
            exoPlayers.forEach { (pos, player) ->
                if (pos != position) player.pause()
            }
            // Pause all WebViews except current
            webViews.forEach { (pos, webView) ->
                if (pos != position) {
                    webView.evaluateJavascript(
                        "document.querySelector('iframe')?.contentWindow?.postMessage('{\"event\":\"command\",\"func\":\"pauseVideo\",\"args\":[]}', '*')",
                        null
                    )
                }
            }
            // Play current
            exoPlayers[position]?.let {
                if (it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_ENDED) {
                    it.play()
                }
            }
            webViews[position]?.let {
                it.evaluateJavascript(
                    "document.querySelector('iframe')?.contentWindow?.postMessage('{\"event\":\"command\",\"func\":\"playVideo\",\"args\":[]}', '*')",
                    null
                )
            }
        }

        fun pauseCurrent() {
            if (currentPos < 0) return
            exoPlayers[currentPos]?.pause()
            webViews[currentPos]?.evaluateJavascript(
                "document.querySelector('iframe')?.contentWindow?.postMessage('{\"event\":\"command\",\"func\":\"pauseVideo\",\"args\":[]}', '*')",
                null
            )
        }

        fun destroyAll() {
            exoPlayers.values.forEach { it.release() }
            exoPlayers.clear()
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val youtubeWebView: WebView = view.findViewById(R.id.youtubeWebView)
            val redditPlayer: PlayerView = view.findViewById(R.id.redditPlayer)
            val titleText: TextView = view.findViewById(R.id.shortTitleText)
            val channelText: TextView = view.findViewById(R.id.shortChannelText)
            val btnLike: ImageButton = view.findViewById(R.id.btnLike)
            val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
            val btnComments: ImageButton = view.findViewById(R.id.btnComments)
            val btnShare: ImageButton = view.findViewById(R.id.btnShare)
            val loading: ProgressBar = view.findViewById(R.id.playerLoading)
            val commentsPanel: LinearLayout = view.findViewById(R.id.commentsPanel)
        }
    }

    companion object {
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_IS_REDDIT = "is_reddit"
        const val EXTRA_REDDIT_VIDEO = "reddit_video"
    }
}
