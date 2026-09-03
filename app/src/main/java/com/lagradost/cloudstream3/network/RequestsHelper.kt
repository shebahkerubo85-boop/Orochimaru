package com.lagradost.cloudstream3.network

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.Prerelease
import ani.sanin.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
// import org.conscrypt.Conscrypt // removed to reduce APK size
import java.io.File
import java.security.Security

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}


// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    // Conscrypt removed to reduce APK size
    
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
                else -> dns(FallbackDns)
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

/**
 * Fallback DNS resolver: uses system DNS first, falls back to Cloudflare DoH
 * when system DNS fails (e.g. UnknownHostException for Amazon DRM license servers).
 */
private object FallbackDns : Dns {
    private val dohUrl = "https://cloudflare-dns.com/dns-query".toHttpUrl()
    // Hardcoded IPs for cloudflare-dns.com so we don't need DNS to reach the DoH server
    private val cloudflareIps = listOf("1.1.1.1", "1.0.0.1")

    private val dohClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return when (hostname) {
                    "cloudflare-dns.com" -> cloudflareIps.map { InetAddress.getByName(it) }
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }
        })
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        // Try system DNS first
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (_: UnknownHostException) { }
        // Fall back to Cloudflare DoH
        return try {
            val url = dohUrl.newBuilder()
                .addQueryParameter("name", hostname)
                .addQueryParameter("type", "A")
                .build()
            val request = Request.Builder().url(url)
                .header("accept", "application/dns-json")
                .build()
            val response = dohClient.newCall(request).execute()
            val body = response.body?.string() ?: throw UnknownHostException("Empty DoH response")
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: throw UnknownHostException("No DNS answer for $hostname")
            val addresses = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optString("type") == "1") {
                    addresses.add(InetAddress.getByName(answer.getString("data")))
                }
            }
            if (addresses.isEmpty()) throw UnknownHostException("No A records for $hostname")
            addresses
        } catch (e: Exception) {
            throw UnknownHostException("DoH fallback failed for $hostname: ${e.message}")
        }
    }
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}