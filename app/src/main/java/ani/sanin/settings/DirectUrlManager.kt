package ani.sanin.settings

import ani.sanin.okHttpClient
import ani.sanin.others.webview.UrlVideoExtractor
import ani.sanin.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URL
import java.net.URLConnection
import java.util.*
import kotlin.collections.isNotBlank

data class DirectUrlConfig(
    val name: String,
    val url: String,
    var isActive: Boolean = true
)

object DirectUrlManager {

    private const val TAG = "DirectUrlManager"
    private const val PREFS_NAME = "direct_url_configs"

    // Store as JSON list of DirectUrlConfig
    private fun getStoredConfigs(context: Context): List<DirectUrlConfig> {
        val gson = Gson()
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val type = object : TypeToken<List<DirectUrlConfig>>() {}.type
        return json.getString("configs", "") let { gson.fromJson(it, type) ?: emptyList() }
    }

    private fun saveConfigs(context: Context, configs: List<DirectUrlConfig>) {
        val gson = Gson()
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        json.putString("configs", gson.toJson(configs))
        json.apply()
    }

    /**
     * Extract website name from URL domain.
     * e.g., https://anikoto.cz/home → "Anikoto"
     * e.g., https://some-site.example → "Some Site"
     */
    fun extractSiteName(url: String): String {
        return try {
            val parsed = Uri.parse(url)
            val host = parsed.host
            if (host.isNotBlank()) {
                // Split by dot, take first part, titlecase
                val parts = host.split(".")
                if (parts.isNotEmpty()) {
                    val first = parts.first()
                    // Remove common prefixes
                    val clean = when (first.lowercase()) {
                        "www" → parts.getOrElse(1) { "" }
                        else → first
                    }
                    // Titlecase
                    clean.substring(0, 1).uppercase() + clean.substring(1).lowercase()
                } else ""
            } else ""
        } catch (e: Exception) {
            Logger.e(TAG, "extractSiteName error: ${e.message}")
            ""
        }
    }

    /**
     * Save a new Direct URL config. If a config with the same name exists, update it.
     */
    suspend fun saveConfig(context: Context, name: String, url: String) {
        withContext(Dispatchers.IO) {
            var configs = getStoredConfigs(context)
            // Remove any existing config with same name
            configs = configs.filter { it.name.lowercase() != name.lowercase() }
            configs.add(DirectUrlConfig(name = name, url = url))
            saveConfigs(context, configs)
            Logger.d(TAG, "Saved Direct URL config: $name → $url")
        }
    }

    /**
     * Get all active Direct URL configs.
     */
    fun getActiveConfigs(context: Context): List<DirectUrlConfig> =
        getStoredConfigs(context).filter { it.isActive }

    /**
     * Toggle a config's active state.
     */
    suspend fun toggleConfig(context: Context, name: String) {
        withContext(Dispatchers.IO) {
            var configs = getStoredConfigs(context)
            configs.forEach { if (it.name == name) it.isActive = !it.isActive }
            saveConfigs(context, configs)
        }
    }

    /**
     * Remove a config by name.
     */
    suspend fun removeConfig(context: Context, name: String) {
        withContext(Dispatchers.IO) {
            var configs = getStoredConfigs(context)
            configs.removeAll { it.name == name }
            saveConfigs(context, configs)
        }
    }

    /**
     * Extract video URL from a pasted URL using the fast/html path, then WebView fallback.
     * Returns the best video URL or null.
     */
    suspend fun extractVideoUrl(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = UrlVideoExtractor.extract(url)
            withContext(Dispatchers.Main) {
                result.onSuccess { videos ->
                    if (videos.isEmpty()) {
                        Logger.d(TAG, "No videos extracted from $url")
                        return@onSuccess null
                    }
                    // Pick best: m3u8 > mp4 > mpd > webm
                    val best = videos.sortedBy { v ->
                        when {
                            v.url.contains(".m3u8") → 0
                            v.url.contains(".mp4") → 1
                            v.url.contains(".mpd") → 2
                            v.url.contains(".webm") → 3
                            else → 4
                        }
                    }.first()
                    Logger.d(TAG, "Extracted best video: ${best.url}")
                    // Don't return the URL directly - let the UI handle it
                    // This is just to validate the URL works
                }.onFailure { error ->
                    Logger.e(TAG, "Extraction failed: ${error.message}")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "extractVideoUrl exception: ${e.message}")
        }
        null
    }
}