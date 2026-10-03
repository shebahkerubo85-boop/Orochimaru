package ani.sanin.media.anime.player

import ani.sanin.util.Logger
import java.net.URI

/**
 * Subtitle URL resolution shared by the player and the offline downloader.
 *
 * Kept separate from the player's subtitle manager so the downloader can resolve relative
 * subtitle URLs without pulling in the whole playback stack.
 */
object PlayerSubtitleManager {

    fun resolveSubtitleUrl(subtitleUrl: String, vararg baseUrls: String): String {
        val subtitleUri = runCatching { URI(subtitleUrl) }.getOrElse {
            Logger.log("Failed to parse subtitle URL '$subtitleUrl': ${it.message}")
            return subtitleUrl
        }
        if (subtitleUri.isAbsolute) return subtitleUri.toString()

        baseUrls.forEach { baseUrl ->
            val resolved = runCatching {
                if (baseUrl.isBlank()) null else URI(baseUrl).resolve(subtitleUri).takeIf { it.isAbsolute }?.toString()
            }.getOrNull()
            if (!resolved.isNullOrBlank()) return resolved
        }
        return subtitleUrl
    }

    fun buildSubtitleId(index: Int, language: String, url: String): String {
        val normalizedLanguage = language.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
        val normalizedUrlTail = runCatching { URI(url).path.substringAfterLast('/').ifBlank { "track" } }
            .getOrDefault("track")
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
        return "ext_sub_${index}_${normalizedLanguage}_${normalizedUrlTail}"
    }
}