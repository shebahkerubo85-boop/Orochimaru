package ani.sanin.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.sanin.R
import ani.sanin.themes.ThemeManager

class YouTubeShortsPlayerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        setContentView(R.layout.activity_youtube_shorts_player)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        val videoIds = intent.getStringArrayListExtra(EXTRA_VIDEO_IDS) ?: arrayListOf()
        val startIdx = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
        val channels = intent.getStringArrayListExtra(EXTRA_CHANNELS) ?: arrayListOf()

        if (videoIds.isEmpty()) {
            Toast.makeText(this, "No videos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewPager = findViewById(R.id.shortsViewPager)
        viewPager.adapter = ShortsPagerAdapter(videoIds, titles, channels)
        viewPager.setCurrentItem(startIdx, false)
        viewPager.offscreenPageLimit = 1

        // Sync WebView playback with page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val adapter = viewPager.adapter as? ShortsPagerAdapter
                adapter?.currentPage = position
                notifyPageChanged(position)
            }
        })

        findViewById<ImageButton>(R.id.closeBtn).setOnClickListener { finish() }
    }

    private fun notifyPageChanged(position: Int) {
        // Pause all, play current
        val adapter = viewPager.adapter as? ShortsPagerAdapter ?: return
        adapter.pauseAll()
        adapter.playPage(position)
    }

    override fun onPause() {
        super.onPause()
        (viewPager.adapter as? ShortsPagerAdapter)?.pauseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        (viewPager.adapter as? ShortsPagerAdapter)?.destroyAll()
    }

    inner class ShortsPagerAdapter(
        private val videoIds: List<String>,
        private val titles: List<String>,
        private val channels: List<String>
    ) : RecyclerView.Adapter<ShortsPagerAdapter.VH>() {

        private val webViews = mutableMapOf<Int, WebView>()
        private val viewHolders = mutableMapOf<Int, VH>()
        private var playingPos = -1
        var currentPage = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = layoutInflater.inflate(R.layout.item_youtube_short_player, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videoIds.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val videoId = videoIds[position]
            val title = titles.getOrElse(position) { "" }
            val channel = channels.getOrElse(position) { "" }

            holder.titleText.text = title
            holder.channelText.text = channel

            holder.btnLike.setOnClickListener {
                Toast.makeText(this@YouTubeShortsPlayerActivity, "Liked!", Toast.LENGTH_SHORT).show()
            }

            holder.btnShare.setOnClickListener {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
                }
                startActivity(android.content.Intent.createChooser(shareIntent, "Share"))
            }

            viewHolders[position] = holder

            // WebView YouTube embed
            setupWebView(holder.webView, videoId, position)
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun setupWebView(webView: WebView, videoId: String, position: Int) {
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
                userAgentString = null
            }
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    viewHolders[position]?.loading?.visibility = View.GONE
                }
            }
            webView.setBackgroundColor(android.graphics.Color.BLACK)

            // Exact same pattern as the working trailer WebView
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

        fun playPage(position: Int) {
            playingPos = position
            // Direct iframe autoplay handles playback
        }

        fun pauseAll() {
            // Direct iframes handle their own lifecycle
            playingPos = -1
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
            val btnShare: ImageButton = view.findViewById(R.id.btnShare)
            val loading: ProgressBar = view.findViewById(R.id.playerLoading)

            init {
                view.tag = "page_$adapterPosition"
                loading.isVisible = true
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_IDS = "video_ids"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_CHANNELS = "channels"
    }
}
