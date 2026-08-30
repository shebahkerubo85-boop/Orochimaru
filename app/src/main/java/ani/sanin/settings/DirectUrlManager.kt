package ani.sanin.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persists user-saved direct video page URLs. A config is either:
 *  - an auto-detected site config (slotIndex == null, name from the domain), or
 *  - a named slot (slotIndex 1..3 => "Link 1".."Link 3").
 */
object DirectUrlManager {

    data class DirectUrlConfig(
        val name: String,
        val url: String,
        val isActive: Boolean = true,
        val slotIndex: Int? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("url", url)
            put("isActive", isActive)
            slotIndex?.let { put("slotIndex", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): DirectUrlConfig = DirectUrlConfig(
                name = o.optString("name"),
                url = o.optString("url"),
                isActive = o.optBoolean("isActive", true),
                slotIndex = if (o.has("slotIndex")) o.optInt("slotIndex") else null
            )
        }
    }

    const val SLOT_COUNT = 3
    const val PREFS = "direct_url_prefs"
    const val KEY_CONFIGS = "configs"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfigs(ctx: Context): List<DirectUrlConfig> {
        val raw = prefs(ctx).getString(KEY_CONFIGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DirectUrlConfig.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveConfigs(ctx: Context, configs: List<DirectUrlConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    /** Active configs; slot configs first (fixed Link order), then auto configs. */
    fun getActiveConfigs(ctx: Context): List<DirectUrlConfig> =
        getConfigs(ctx)
            .filter { it.isActive && it.url.isNotBlank() }
            .sortedWith(compareBy<DirectUrlConfig> { it.slotIndex == null }.thenBy { it.slotIndex ?: Int.MAX_VALUE })

    fun getSlotConfig(ctx: Context, slotIndex: Int): DirectUrlConfig? =
        getConfigs(ctx).firstOrNull { it.slotIndex == slotIndex }

    /** Save or update a config, matched by name (auto) or by slot (Link slots). */
    fun saveConfig(ctx: Context, config: DirectUrlConfig) {
        val list = getConfigs(ctx).toMutableList()
        val idx = list.indexOfFirst {
            it.name == config.name || (config.slotIndex != null && it.slotIndex == config.slotIndex)
        }
        if (idx >= 0) list[idx] = config else list.add(config)
        saveConfigs(ctx, list)
    }

    fun removeConfig(ctx: Context, name: String) {
        saveConfigs(ctx, getConfigs(ctx).filterNot { it.name == name })
    }

    fun setActive(ctx: Context, name: String, active: Boolean) {
        val list = getConfigs(ctx).toMutableList()
        val idx = list.indexOfFirst { it.name == name }
        if (idx >= 0) list[idx] = list[idx].copy(isActive = active)
        saveConfigs(ctx, list)
    }

    /** e.g. https://anikoto.cz/home -> "Anikoto", https://www.9anime.to -> "9Anime". */
    fun extractSiteName(url: String): String {
        val cleaned = url.trim()
        val host = runCatching {
            java.net.URI(if (cleaned.contains("://")) cleaned else "https://$cleaned").host
        }.getOrNull() ?: return "Direct URL"
        val label = host
            .removePrefix("www.")
            .removePrefix("m.")
            .removePrefix("anime.")
            .substringBefore('.')
        if (label.isBlank()) return "Direct URL"
        return label.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    fun slotName(index: Int): String = "Link $index"

    fun isValidUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
