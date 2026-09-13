package ani.sanin.others.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ani.sanin.R
import ani.sanin.themes.ThemeManager
import ani.sanin.util.Logger

/**
 * Fullscreen WebView player for embed hosts whose CDNs block native
 * ExoPlayer clients (Flixcloud/Reanime, MegaPlay/AniKoto). The site's own
 * player runs inside a real Chromium WebView, which solves the CF
 * challenge and negotiates the real playlist the same way a browser does.
 */
class WebEmbedPlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_web_embed_player)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        webView = findViewById(R.id.webEmbedWebView)
        progress = findViewById(R.id.webEmbedProgress)
        errorText = findViewById(R.id.webEmbedError)
        fullscreenContainer = findViewById(R.id.webEmbedFullscreen)
        findViewById<ImageButton>(R.id.webEmbedClose).setOnClickListener { finish() }

        configureWebView()
        webView.loadUrl(url)
        Logger.log("WebEmbed: opening $url")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    progress.visibility = View.GONE
                    errorText.visibility = View.VISIBLE
                    errorText.text =
                        "Couldn't load the video player.\n${error?.errorCode ?: "unknown"} ${error?.description ?: ""}"
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.visibility = View.VISIBLE
                view?.let {
                    fullscreenContainer.addView(
                        it,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                webView.visibility = View.GONE
                hideSystemUi()
            }

            override fun onHideCustomView() {
                customView?.let { fullscreenContainer.removeView(it) }
                customView = null
                customViewCallback = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                showSystemUi()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient.onHideCustomView()
            return
        }
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun showSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.webChromeClient.onHideCustomView()
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "web_embed_url"
    }
}
