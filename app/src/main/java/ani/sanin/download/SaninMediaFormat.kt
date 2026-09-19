package ani.sanin.download

/**
 * Media format detection for the Sanin download pipeline.
 *
 * This enum is the single point where the supervisor decides which
 * format-specific downloader to dispatch to. The values are kept
 * separate from the Sanin [ani.sanin.parsers.VideoType] because the
 * detection happens before we have a parsed [ani.sanin.parsers.Video]
 * — at this stage we only have a raw URL and optionally a small
 * content probe.
 */
enum class SaninMediaFormat {
    /** Direct HTTP/HTTPS media file. Supports byte-range downloads. */
    DIRECT,

    /** HLS / M3U8 playlist. */
    HLS,

    /** MPEG-DASH / MPD manifest. */
    DASH,

    /** Format could not be determined. The supervisor should fall back. */
    UNKNOWN;

    companion object {
        /**
         * Detect the media format from a URL alone. This is a fast
         * path used by the enqueue path before any network request.
         * It only uses the URL extension / path. If the URL is
         * ambiguous, returns [UNKNOWN] and the caller is expected to
         * perform a bounded probe.
         */
        fun fromUrl(url: String): SaninMediaFormat {
            val lower = url.lowercase().substringBefore('?').substringBefore('#')
            return when {
                lower.endsWith(".m3u8") -> HLS
                lower.endsWith(".mpd") -> DASH
                // Common direct media extensions used by anime sources.
                lower.endsWith(".mp4") -> DIRECT
                lower.endsWith(".mkv") -> DIRECT
                lower.endsWith(".webm") -> DIRECT
                // No extension — caller must probe.
                else -> UNKNOWN
            }
        }

        /**
         * Detect the media format from an HTTP response's
         * [Content-Type] header. Returns [UNKNOWN] if the header
         * is missing or does not match a known type.
         */
        fun fromContentType(contentType: String?): SaninMediaFormat {
            if (contentType == null) return UNKNOWN
            val ct = contentType.lowercase()
            return when {
                ct.contains("application/vnd.apple.mpegurl") -> HLS
                ct.contains("application/x-mpegurl") -> HLS
                ct.contains("audio/mpegurl") -> HLS
                ct.contains("application/dash+xml") -> DASH
                ct.contains("video/") -> DIRECT
                ct.contains("audio/") -> DIRECT
                ct.contains("application/octet-stream") -> UNKNOWN
                else -> UNKNOWN
            }
        }

        /**
         * Detect the media format by inspecting the first few bytes
         * of a response body. Used as a last resort when neither the
         * URL nor the Content-Type is conclusive.
         *
         * Returns [UNKNOWN] for empty or unrecognised payloads.
         */
        fun fromBodyPrefix(body: String): SaninMediaFormat {
            val trimmed = body.trimStart()
            return when {
                trimmed.startsWith("#EXTM3U") -> HLS
                trimmed.startsWith("<?xml") || trimmed.startsWith("<MPD") -> DASH
                else -> UNKNOWN
            }
        }
    }
}
