package ani.sanin.download

/**
 * Pure segment / byte-range math.
 *
 * Rules:
 *  - total size > 0, otherwise the result is empty.
 *  - non-overlapping ranges
 *  - no missing bytes
 *  - first range begins at 0
 *  - final range ends at `totalSize - 1`
 *  - never create a zero-length range
 *  - "Long" for byte positions
 *  - if the file is smaller than the connection count, use fewer segments
 *  - overflow-safe arithmetic
 */
object SegmentMath {

    /** Inclusive `[start, end]` byte range for a single segment. */
    data class Range(val start: Long, val end: Long) {
        init {
            require(start >= 0L) { "start must be >= 0, was $start" }
            require(end >= start) { "end must be >= start, was start=$start end=$end" }
        }

        val size: Long get() = end - start + 1L
    }

    /**
     * Build a list of non-overlapping ranges that exactly cover
     * `[0, totalSize - 1]`.
     *
     * @param totalSize total file size in bytes. If `<= 0` the result is empty.
     * @param connections desired connection count. The result never has
     *                    more ranges than `connections` and never more
     *                    than is useful for a small file.
     */
    fun buildRanges(totalSize: Long, connections: Int): List<Range> {
        if (totalSize <= 0L) return emptyList()
        val wanted = connections.coerceAtLeast(1)

        // Never produce more segments than the file can meaningfully
        // hold — for a 2-byte file with 4 connections, 2 segments is
        // already "1 segment per byte". Capping at `totalSize` itself
        // guarantees no zero-length ranges.
        val segmentCount = if (totalSize < wanted.toLong()) totalSize.toInt() else wanted
        if (segmentCount <= 0) return emptyList()

        // Ceiling division, overflow-safe even for totalSize near
        // Long.MAX_VALUE: divide first, then add (totalSize % count)
        // and re-divide if non-zero.
        val count = segmentCount.toLong()
        val baseChunk = totalSize / count
        val chunk = if (totalSize % count == 0L) baseChunk else baseChunk + 1L

        val out = ArrayList<Range>(segmentCount)
        var cursor = 0L
        for (i in 0 until segmentCount) {
            if (cursor >= totalSize) break
            val end = (cursor + chunk - 1L).coerceAtMost(totalSize - 1L)
            if (end < cursor) break
            out.add(Range(cursor, end))
            cursor = end + 1L
        }
        return out
    }

    /**
     * Compute the range still needed to finish a segment that already
     * has `[segmentStart, resumeAt - 1]` bytes on disk. Returns null
     * if the segment is already fully covered.
     */
    fun resumeRange(segmentStart: Long, segmentEnd: Long, resumeAt: Long): Range? {
        if (resumeAt <= segmentStart) return Range(segmentStart, segmentEnd)
        if (resumeAt > segmentEnd + 1L) return null
        if (resumeAt == segmentEnd + 1L) return null
        return Range(resumeAt, segmentEnd)
    }
}
