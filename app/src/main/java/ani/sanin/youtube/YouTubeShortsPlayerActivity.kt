package ani.sanin.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import androidx.lifecycle.lifecycleScope
import ani.sanin.themes.ThemeManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

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

        if (videoIds.isEmpty()) { finish(); return }

        recycler = findViewById(R.id.shortsRecyclerView)
        adapter = ShortsAdapter(videoIds, titles)
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recycler.adapter = adapter

        // Smooth snap-to-full like YouTube Shorts
        LinearSnapHelper().attachToRecyclerView(recycler)

        // Auto-play current page when snappped
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val pos = (recyclerView.layoutManager as LinearLayoutManager)
                        .findFirstVisibleItemPosition()
                    adapter.setCurrent(pos)
                }
            }
        })

        recycler.post { recycler.scrollToPosition(startIdx); adapter.setCurrent(startIdx) }
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
        private val titles: List<String>
    ) : RecyclerView.Adapter<ShortsAdapter.VH>() {

        private val webViews = mutableMapOf<Int, WebView>()
        private var currentPos = 0
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
            holder.titleText.text = titles.getOrElse(position) { "" }
            holder.channelText.text = "Aniphex"

            // Like / dislike state
            holder.btnLike.setColorFilter(if (position in liked) android.graphics.Color.parseColor("#FF4CAF50") else android.graphics.Color.WHITE)
            holder.btnDislike.setColorFilter(if (position in disliked) android.graphics.Color.parseColor("#FFE53935") else android.graphics.Color.WHITE)

            holder.btnLike.setOnClickListener {
                if (position in liked) liked.remove(position) else { liked.add(position); disliked.remove(position) }
                holder.btnLike.setColorFilter(if (position in liked) android.graphics.Color.parseColor("#FF4CAF50") else android.graphics.Color.WHITE)
                holder.btnDislike.setColorFilter(if (position in disliked) android.graphics.Color.parseColor("#FFE53935") else android.graphics.Color.WHITE)
            }
            holder.btnDislike.setOnClickListener {
                if (position in disliked) disliked.remove(position) else { disliked.add(position); liked.remove(position) }
                holder.btnLike.setColorFilter(if (position in liked) android.graphics.Color.parseColor("#FF4CAF50") else android.graphics.Color.WHITE)
                holder.btnDislike.setColorFilter(if (position in disliked) android.graphics.Color.parseColor("#FFE53935") else android.graphics.Color.WHITE)
            }

            // Comments toggle
            holder.btnComments.setOnClickListener {
                val show = holder.commentsPanel.visibility != View.VISIBLE
                holder.commentsPanel.visibility = if (show) View.VISIBLE else View.GONE
                if (show) loadComments(holder, videoId)
            }

            // Share
            holder.btnShare.setOnClickListener {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
                }
                startActivity(android.content.Intent.createChooser(shareIntent, "Share"))
            }

            // WebView — exact trailer pattern
            setupWebView(holder, videoId, position)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun setupWebView(holder: VH, videoId: String, position: Int) {
            val webView = holder.webView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    holder.loading.visibility = View.GONE
                    if (position == currentPos) {
                        // Keep playing current
                    }
                }
            }
            webView.setBackgroundColor(android.graphics.Color.BLACK)

            val html = """
                <!DOCTYPE html>
                <html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
                    html,body{width:100%;height:100%;background:#000;overflow:hidden;}
                    iframe{width:100%;height:100%;border:none;display:block;}</style>
                </head><body>
                    <iframe src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0&playsinline=1&enablejsapi=1"
                    allow="accelerometer;autoplay;clipboard-write;encrypted-media;gyroscope;picture-in-picture" frameborder="0"></iframe>
                </body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "utf-8", null)
            webViews[position] = webView
        }

        private fun loadComments(holder: VH, videoId: String) {
            holder.commentsRecycler.layoutManager = LinearLayoutManager(this@YouTubeShortsPlayerActivity)
            this@YouTubeShortsPlayerActivity.lifecycleScope.launch {
                val comments = YouTubeApi.fetchComments(videoId)
                holder.commentsRecycler.adapter = CommentsAdapter(comments)
                holder.commentsRecycler.adapter?.notifyDataSetChanged()
            }
        }

        fun setCurrent(position: Int) {
            currentPos = position
        }

        fun pauseCurrent() {
            webViews[currentPos]?.let { webView ->
                webView.evaluateJavascript("document.querySelector('iframe')?.contentWindow?.postMessage('{\"event\":\"command\",\"func\":\"pauseVideo\",\"args\":[]}', '*')", null)
            }
        }

        fun destroyAll() {
            webViews.values.forEach { it.destroy() }
            webViews.clear()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val webView: WebView = view.findViewById(R.id.youtubeWebView)
            val titleText: TextView = view.findViewById(R.id.shortTitleText)
            val channelText: TextView = view.findViewById(R.id.shortChannelText)
            val btnLike: ImageButton = view.findViewById(R.id.btnLike)
            val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
            val btnComments: ImageButton = view.findViewById(R.id.btnComments)
            val btnShare: ImageButton = view.findViewById(R.id.btnShare)
            val loading: ProgressBar = view.findViewById(R.id.playerLoading)
            val commentsPanel: LinearLayout = view.findViewById(R.id.commentsPanel)
            val commentsRecycler: RecyclerView = view.findViewById(R.id.commentsRecycler)
        }
    }

    companion object {
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_CHANNELS = "channels"
    }
}
