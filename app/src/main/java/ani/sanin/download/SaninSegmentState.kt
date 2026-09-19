package ani.sanin.download

import kotlinx.serialization.Serializable

/**
 * Persistent state for a single Sanin segmented download.
 *
 * Supports three formats via [format]:
 *  - "CONTAINER": a single file fetched via HTTP byte ranges.
 *    `startByte`/`endByte` are the absolute byte range; `segmentUrl`
 *    is null.
 *  - "HLS": an HLS media playlist. Each segment is an independent
 *    `.ts` (or `.m4s` for fMP4) file. `startByte`/`endByte` are 0
 *    (meaningless); `segmentUrl` holds the absolute segment URL.
 *  - "DASH": a DASH MPD. Each segment is an independent `.m4s` file.
 *    `startByte`/`endByte` are 0; `segmentUrl` holds the absolute
 *    segment URL.
 *
 * The state is namespaced under the Sanin preferences folder as
 * `sanin_segment_state_<downloadId>` and reloaded by the supervisor
 * after process death.
 */
@Serializable
data class SaninSegmentState(
    val downloadId: Int,
    /** The original resource URL (master playlist URL for HLS, MPD URL for DASH, file URL for CONTAINER). */
    val url: String,
    val headers: Map<String, String>,
    val referer: String,
    /** Total expected size in bytes. For HLS this is computed after parsing the media playlist. For DASH it is the sum of segment sizes from the MPD. */
    val totalSize: Long,
    val segments: List<SegmentState>,
    /** Updated after each successful segment so the supervisor can show progress. */
    val bytesDownloaded: Long,
    /** "CONTAINER" (default), "HLS", or "DASH". */
    val format: String = "CONTAINER",
    /** For HLS/DASH: the selected media playlist URL (HLS) or representation id (DASH). Null for CONTAINER. */
    val variantId: String? = null,
    /** Total segment count for HLS/DASH (used to validate parsed playlist consistency on resume). */
    val variantSegmentCount: Int? = null,
) {
    @Serializable
    data class SegmentState(
        val index: Int,
        /**
         * For CONTAINER: the absolute start byte of this segment's range.
         * For HLS/DASH: 0 (meaningless; segments are independent files).
         */
        val startByte: Long,
        /**
         * For CONTAINER: the absolute end byte of this segment's range.
         * For HLS/DASH: 0 (meaningless; segments are independent files).
         */
        val endByte: Long,
        /** Bytes already written to the segment file. */
        val downloadedBytes: Long,
        val completed: Boolean,
        /**
         * For HLS/DASH: the absolute URL of this segment. Null for CONTAINER.
         */
        val segmentUrl: String? = null,
        /**
         * For HLS segments with `EXT-X-BYTERANGE`: "offset-length".
         * Null otherwise.
         */
        val byteRange: String? = null,
    ) {
        val size: Long get() = if (startByte >= 0L && endByte >= startByte) endByte - startByte + 1L else 0L
    }
}
