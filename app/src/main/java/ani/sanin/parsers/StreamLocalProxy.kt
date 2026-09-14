package ani.sanin.parsers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.sanin.App
import ani.sanin.defaultHeaders
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tiny loopback HTTP proxy so HLS streams whose CDNs encrypt or block
 * non-browser clients can still play inside Sanin's ExoPlayer.
 *
 * ExoPlayer only ever talks to `http://127.0.0.1:<port>/stream?...`; every
 * upstream request is made by this proxy through the app's network stack
 * (shared cookie jar + Cloudflare challenge solver, when Cloudflare answers
 * with a detectable 403/503), so cookies/clearances stay consistent.
 *
 * Endpoint params:
 *  - url     original upstream resource (master/variant playlist, segment, subtitle)
 *  - ref     Referer to send upstream
 *  - origin  Origin to send upstream
 *  - pk      base64 32-byte playlist XOR key (flixcloud `_c()`), optional
 *  - mask    base64 16-byte segment XOR mask (flixcloud), optional
 *  - sub     "1" for subtitle pass-through
 *  - warm    "1" to seed a hidden WebView against the host once (CF/JS challenge)
 */
object StreamLocalProxy {

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-local-proxy").apply { isDaemon = true }
    }

    private val warmedHosts = ConcurrentHashMap.newKeySet<String>()

    private val acceptThread: Thread by lazy {
        Thread({
            val sock = server
            if (sock == null || sock.isClosed) return@Thread
            Logger.log(Log.WARN, "StreamLocalProxy: listening on 127.0.0.1:${sock.localPort}")
            while (true) {
                try {
                    val client = sock.accept()
                    client.soTimeout = 60_000
                    executor.execute { handle(client) }
                } catch (e: Exception) {
                    if (sock.isClosed) break
                }
            }
        }, "stream-local-proxy-accept").apply { isDaemon = true }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = -1

    /** Client used for playlists: inherits the app client (cookie jar + Cloudflare solve). */
    private val cfClient: OkHttpClient get() = okHttpClient

    /** Client used for segments/subtitles: no Cloudflare interceptor (no 30s stalls). */
    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(okHttpClient.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun ensureStarted() {
        synchronized(this) {
            if (server == null || server!!.isClosed) {
                val sock = ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"))
                server = sock
                port = sock.localPort
                acceptThread.start()
            }
        }
    }

    private fun baseUrl(): String {
        ensureStarted()
        return "http://127.0.0.1:$port/stream"
    }

    /** Build a proxied URL for an HLS playlist / segment / subtitle. */
    fun proxyUrl(
        original: String,
        referer: String,
        origin: String = referer.removeSuffix("/"),
        pk: String? = null,
        mask: String? = null,
        subtitle: Boolean = false,
        warm: Boolean = false,
    ): String {
        val params = mutableListOf(
            "url=" + URLEncoder.encode(original, "UTF-8"),
            "ref=" + URLEncoder.encode(referer, "UTF-8"),
        )
        if (origin.isNotBlank()) params += "origin=" + URLEncoder.encode(origin, "UTF-8")
        pk?.let { params += "pk=" + URLEncoder.encode(it, "UTF-8") }
        mask?.let { params += "mask=" + URLEncoder.encode(it, "UTF-8") }
        if (subtitle) params += "sub=1"
        if (warm) params += "warm=1"
        return baseUrl() + "?" + params.joinToString("&")
    }

    /* ================================================================
       HTTP handling
       ================================================================ */

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    writeResponse(s, "400 Bad Request", "text/plain", "Bad request", null)
                    return
                }
                // Drain headers
                var len = 0
                while (len < 32 * 1024) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    len += line.length
                }

                val path = parts[1].substringBefore("?")
                val query = parts[1].substringAfter("?", "")
                if (path != "/stream") {
                    writeResponse(s, "404 Not Found", "text/plain", "Not found", null)
                    return
                }
                val params = parseQuery(query)
                val url = params["url"] ?: run {
                    writeResponse(s, "400 Bad Request", "text/plain", "Missing url", null)
                    return
                }
                val referer = params["ref"] ?: ""
                val origin = params["origin"] ?: ""
                val pk = params["pk"]?.takeIf { it.isNotBlank() }
                val mask = params["mask"]?.takeIf { it.isNotBlank() }
                val subtitle = params["sub"] == "1"
                val warm = params["warm"] == "1"

                val pkBytes = pk?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }
                val maskBytes = mask?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }

                if (warm) warmHost(url, referer)

                if (subtitle || !isManifest(url)) {
                    serveStream(s, url, referer, origin, maskBytes)
                } else {
                    serveManifest(s, url, referer, origin, pkBytes, maskBytes)
                }
            }
        } catch (e: Exception) {
            Logger.log(Log.WARN, "StreamLocalProxy handle: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun isManifest(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    /* ================================================================
       Manifest path (flixcloud XOR/base64 wrapped playlists)
       ================================================================ */

    private fun serveManifest(
        socket: Socket,
        url: String,
        referer: String,
        origin: String,
        pk: ByteArray?,
        mask: ByteArray?,
    ) {
        val request = Request.Builder().url(url)
            .header("User-Agent", ua())
            .header("Accept", "*/*")
            .apply {
                if (referer.isNotBlank()) header("Referer", referer)
                if (origin.isNotBlank()) header("Origin", origin)
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "same-site")
            }
            .get()
            .build()

        val owner = if (referer.contains("flixcloud", true)) "flixcloud" else "hls"
        cfClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.peekBody(512).string() }.getOrDefault("")
                Logger.log(
                    Log.WARN,
                    "StreamLocalProxy: $owner manifest ${resp.code} for $url body=${body.take(300)} " +
                        "srv=${resp.header("Server")} ray=${resp.header("cf-ray")}"
                )
                writeResponse(socket, "${resp.code}", "text/plain", body.ifBlank { "${resp.code}" }, null)
                return
            }
            val raw = resp.body?.string().orEmpty()
            val bodyText = decryptPlaylistIfNeeded(raw, pk)
            if (bodyText == null || !bodyText.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
                Logger.log(Log.WARN, "StreamLocalProxy: $owner manifest decrypt failed url=$url pk=${pk != null} size=${raw.length}")
                writeResponse(socket, "502 Bad Gateway", "text/plain", "Manifest decrypt failed", null)
                return
            }

            val base = runCatching { URI(url) }.getOrNull()
            val rewritten = rewritePlaylist(bodyText, base, referer, origin, pkByteArray = pk, maskByteArray = mask)
            writeResponse(socket, "200 OK", "application/vnd.apple.mpegurl", rewritten, rewritten.toByteArray().size.toLong())
        }
    }

    private fun decryptPlaylistIfNeeded(raw: String, pk: ByteArray?): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) return raw
        if (pk == null || pk.isEmpty()) return null
        return runCatching {
            val cipher = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            val plain = ByteArray(cipher.size)
            for (i in cipher.indices) plain[i] = (cipher[i].toInt() xor pk[i % pk.size].toInt()).toByte()
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun rewritePlaylist(
        body: String,
        base: URI?,
        referer: String,
        origin: String,
        pkByteArray: ByteArray?,
        maskByteArray: ByteArray?,
    ): String {
        val pkB64 = pkByteArray?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val maskB64 = maskByteArray?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        val out = StringBuilder(body.length + 512)
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
                continue
            }
            if (trimmed.startsWith("#")) {
                val uriMatch = Regex("""URI="([^"]*)"""").find(trimmed)
                if (uriMatch != null) {
                    val resolved = resolveUrl(base, uriMatch.groupValues[1], referer)
                    val proxied = proxyUrl(
                        ensureToken(resolved, referer), referer, origin,
                        pk = pkB64, mask = maskB64
                    )
                    out.append(trimmed.replace(uriMatch.groupValues[1], proxied)).append('\n')
                } else {
                    out.append(trimmed).append('\n')
                }
            } else {
                val resolved = resolveUrl(base, trimmed, referer)
                out.append(proxyUrl(
                    ensureToken(resolved, referer), referer, origin,
                    pk = pkB64, mask = maskB64
                )).append('\n')
            }
        }
        return out.toString()
    }

    private fun resolveUrl(base: URI?, value: String, referer: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        base?.let {
            return runCatching { base.resolve(value).toString() }.getOrDefault(value)
        }
        return runCatching { URI(referer).resolve(value).toString() }.getOrDefault(value)
    }

    /** Variant/segment URLs usually carry their own token; re-add the parent's if absent. */
    private fun ensureToken(url: String, parentParam: String): String {
        if (url.contains("token=")) return url
        val parent = firstUrlWithToken(parentParam) ?: return url
        val token = parseQuery(parent.substringAfter("?", "")).getFirst("token") ?: return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "token=" + token
    }

    private fun firstUrlWithToken(param: String): String? {
        // The referer param is a plain URL; a nested token may live in a proxied URL.
        val tryUrls = buildList {
            add(param)
            val q = param.substringAfter("?", "")
            if (q.isNotEmpty()) {
                val u = parseQuery(q).getFirst("url")
                if (u != null) add(u)
            }
        }
        return tryUrls.firstOrNull { parseQuery(it.substringAfter("?", "")).getFirst("token") != null }
    }

    /* ================================================================
       Segment / subtitle / byte pass-through
       ================================================================ */

    private fun serveStream(
        socket: Socket,
        url: String,
        referer: String,
        origin: String,
        mask: ByteArray?,
    ) {
        val request = Request.Builder().url(url)
            .header("User-Agent", ua())
            .header("Accept", "*/*")
            .apply {
                if (referer.isNotBlank()) header("Referer", referer)
                if (origin.isNotBlank()) header("Origin", origin)
            }
            .get()
            .build()

        streamClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.peekBody(512).string() }.getOrDefault("")
                Logger.log(
                    Log.WARN,
                    "StreamLocalProxy: segment/subtitle ${resp.code} for $url body=${body.take(200)} " +
                        "srv=${resp.header("Server")} ray=${resp.header("cf-ray")}"
                )
                writeResponse(socket, "${resp.code}", "text/plain", body.ifBlank { "${resp.code}" }, null)
                return
            }

            val contentType = when {
                url.endsWith(".ass", true) -> "text/x-ass"
                url.endsWith(".srt", true) -> "application/x-subrip"
                url.endsWith(".vtt", true) -> "text/vtt"
                url.contains("/subtitles/", true) -> "text/vtt"
                else -> "video/mp2t"
            }

            val upstream = resp.body?.byteStream() ?: return
            val isText = contentType.startsWith("text/") || contentType.contains("subrip")
            if (isText || mask == null) {
                val bytes = upstream.readBytes()
                writeResponse(socket, "200 OK", contentType, null, bytes.size.toLong())
                socket.getOutputStream().use { out -> out.write(bytes) }
                return
            }

            // Segment: peek fake WebP/PNG header, strip it, XOR the payload with the mask.
            val head = ByteArray(13)
            var got = 0
            while (got < head.size) {
                val n = upstream.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            val headerSize = detectFakeHeader(head, got)
            val shouldXor = headerSize > 0 && got > headerSize && (head[headerSize].toInt() and 0xFF) != 0x47
            if (shouldXor) {
                val first = head[headerSize].toInt() and 0xFF
                val dec = first xor (mask[0].toInt() and 0xFF)
                if (dec != 0x47) {
                    Logger.log(Log.WARN, "StreamLocalProxy: segment XOR mask mismatch (first=$first dec=$dec)")
                }
            }

            val out = socket.getOutputStream()
            writeHead(out, "200 OK", contentType, null, chunked = true)
            val xorSource = XorInputStream(upstream, head, got, headerSize, if (shouldXor) mask else null)
            streamChunked(out, xorSource)
        }
    }

    private fun detectFakeHeader(head: ByteArray, len: Int): Int = when {
        len >= 12 &&
            head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
            head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
            head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
            head[10] == 0x42.toByte() && head[11] == 0x50.toByte() -> 12 // RIFF....WEBP
        len >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() &&
            head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() &&
            head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte() -> 8 // PNG sig
        else -> 0
    }

    /* ================================================================
       Low-level HTTP writers
       ================================================================ */

    private fun writeResponse(
        socket: Socket,
        status: String,
        contentType: String,
        body: String?,
        contentLength: Long?,
    ) {
        val out = socket.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        if (contentLength != null) sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        body?.let { out.write(it.toByteArray(Charsets.UTF_8)) }
        out.flush()
    }

    private fun writeHead(out: java.io.OutputStream, status: String, contentType: String, contentLength: Long?, chunked: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Connection: close\r\n")
        if (contentLength != null) sb.append("Content-Length: ").append(contentLength).append("\r\n")
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun streamChunked(out: java.io.OutputStream, input: InputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(Integer.toHexString(n).toByteArray(Charsets.US_ASCII))
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(buf, 0, n)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                }
            }
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        } finally {
            out.flush()
            runCatching { input.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
            }
            buf.write(b)
            prev = b
        }
        return buf.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.isEmpty() || kv[0].isBlank()) null
            else kv[0] to runCatching { URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8") }.getOrDefault(kv.getOrElse(1) { "" })
        }.toMap()
    }

    private fun Map<String, String>.getFirst(name: String): String? = this[name]

    private fun ua(): String = defaultHeaders["User-Agent"] ?: FALLBACK_UA

    /* ================================================================
       Hidden WebView warm-up (cookie seeding for CF / JS challenges)
       ================================================================ */

    private fun warmHost(url: String, referer: String) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        if (!warmedHosts.add(host)) return
        val hasClearance = runCatching {
            okHttpClient.cookieJar.loadForRequest(url.toHttpUrl())
                .any { it.name.equals("cf_clearance", true) || it.name.equals("__cf_bm", true) }
        }.getOrDefault(false)
        if (hasClearance) return
        warmHostLocked(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun warmHostLocked(url: String) {
        val ctx = App.context ?: return
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            var webView: WebView? = null
            try {
                webView = WebView(ctx.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = ua()
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        latch.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        latch.countDown()
                    }
                }
                webView.loadUrl(url)
            } catch (e: Exception) {
                Logger.log(Log.WARN, "StreamLocalProxy: webview warm failed: ${e.message}")
                latch.countDown()
            }
            // Give the WebView a moment to write cookies, then release it.
            mainHandler.postDelayed({
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
            }, 1_500)
        }
        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * InputStream that first emits the already-read head bytes (minus a fake image
 * header), then streams upstream, XORing the payload with a 16-byte mask when
 * one is supplied.
 */
private class XorInputStream(
    private val upstream: InputStream,
    private val head: ByteArray,
    private val headLen: Int,
    private val skipBytes: Int,
    private val mask: ByteArray?,
) : InputStream() {

    private var headPos = 0
    private var xorIndex = 0

    private fun transformByte(v: Int): Int =
        if (mask == null) v else (v xor (mask[xorIndex % mask.size].toInt() and 0xFF)) and 0xFF

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (headPos < headLen) {
            if (headPos >= skipBytes) {
                b[off] = transformByte(head[headPos].toInt() and 0xFF).toByte()
                headPos++
                xorIndex++
                return 1
            }
            headPos++
        }
        val n = upstream.read(b, off, len)
        if (n < 0) return -1
        for (i in 0 until n) {
            b[off + i] = transformByte(b[off + i].toInt() and 0xFF).toByte()
            xorIndex++
        }
        return n
    }
}
