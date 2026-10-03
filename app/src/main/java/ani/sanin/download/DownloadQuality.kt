package ani.sanin.download

import ani.sanin.parsers.Video

/**
 * Picks the best video for an offline download.
 *
 * Prefers an exact quality match against [preferredResolutions] in order; falls back to a
 * fuzzy match on the note/URL, then to the highest advertised quality.
 */
fun findBestVideoForDownload(
    videos: List<Video>,
    preferredResolutions: List<String>
): Video? {
    if (videos.isEmpty()) return null
    if (preferredResolutions.isEmpty()) return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()

    for (preferred in preferredResolutions) {
        val resNumber = Regex("""\d+""").find(preferred)?.value?.toIntOrNull()
        if (resNumber != null) {
            val match = videos.firstOrNull { it.quality == resNumber }
            if (match != null) return match
        }
        val cleanPreferred = preferred.lowercase().replace("p", "").trim()
        val matchFallback = videos.firstOrNull { video ->
            val note = video.extraNote?.lowercase() ?: ""
            val url = video.file.url.lowercase()
            note.contains(preferred.lowercase()) || note.contains(cleanPreferred) ||
                url.contains("${cleanPreferred}p") || url.contains(cleanPreferred)
        }
        if (matchFallback != null) return matchFallback
    }

    return videos.maxByOrNull { it.quality ?: 0 } ?: videos.first()
}