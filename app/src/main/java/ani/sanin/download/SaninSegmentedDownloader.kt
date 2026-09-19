package ani.sanin.download

import android.content.Context
import androidx.core.net.toUri
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadProgressEvent
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Container downloader that uses HTTP byte ranges to download a file
 * in parallel segments, each persisted in a separate temporary file
 * until the final assembly step.
 *
 * Single-purpose, no queue, no UI. The caller (supervisor) decides
 * whether to use this path or fall back to
 * [VideoDownloadManager.downloadThing].
 */
internal class SaninSegmentedDownloader(
    private val context: Context,
    private val downloadId: Int,
) {

    sealed class Outcome {
        data class Success(val finalSize: Long) : Outcome()
        data class Failure(val reason: String, val expiredUrl: Boolean = false) : Outcome()
        /** Range/streaming not viable; caller should fall back. */
        object Fallback : Outcome()
    }

    /**
     * Optional callback invoked when an HTTP failure indicates the
     * resolved URL is no longer valid. Returning a non-null
     * (url, headers, referer) tuple re-resolves and the download
     * continues with the new values. Returning null signals that
     * re-resolution is not possible and the failure should propagate.
     */
    fun interface UrlReResolver {
        suspend fun reResolve(): ReResolved?
    }

    data class ReResolved(
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
    )

    suspend fun run(
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        displayName: String,
        folder: String,
        extension: String,
        parallelConnections: Int,
        reResolver: UrlReResolver? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            runInternal(
                initialUrl = initialUrl,
                initialHeaders = initialHeaders,
                initialReferer = initialReferer,
                displayName = displayName,
                folder = folder,
                extension = extension,
                parallelConnections = parallelConnections,
                reResolver = reResolver,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logError(e)
            Outcome.Failure(e.message ?: "segmented download failed")
        }
    }

    private suspend fun runInternal(
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        displayName: String,
        folder: String,
        extension: String,
        parallelConnections: Int,
        reResolver: UrlReResolver?,
    ): Outcome {
        var url = initialUrl
        var headers = initialHeaders
        var referer = initialReferer

        var probe = probe(url, headers, referer)
        if (probe == null && reResolver != null) {
            // Server may have rejected the very first HEAD probe
            // because of an expired URL. Try to re-resolve before
            // giving up on segmented mode entirely.
            val reresolved = reResolver.reResolve()
            if (reresolved != null) {
                url = reresolved.url
                headers = reresolved.headers
                referer = reresolved.referer
                probe = probe(url, headers, referer)
            }
        }
        if (probe == null || probe.totalSize <= 0L || !probe.rangeSupported) {
            return Outcome.Fallback
        }

        val state = loadOrCreateState(
            url = url,
            headers = headers,
            referer = referer,
            totalSize = probe.totalSize,
            desiredSegments = parallelConnections,
        )

        val (baseFile, basePath) = context.getBasePath()
            ?: return Outcome.Failure("No base path")
        val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
            ?: return Outcome.Failure("Cannot create download folder")
        val finalName = "$displayName.$extension"
        cleanupStaleParts(subDir, finalName, state.segments.size)
        // If the state was just created fresh (no existing state or
        // existing state was unrecoverable), the part files on disk
        // from any prior attempt are stale. Delete them so the new
        // download does not append to garbage from a different
        // segmentation.
        if (state.bytesDownloaded == 0L) {
            cleanupAllParts(subDir, finalName)
        }

        publishStatus(DownloadType.IsDownloading)

        var i = 0
        while (i < state.segments.size) {
            waitIfPaused()
            // Re-read the segment from persisted state each iteration
            // so that an invalidation (e.g. SegmentCorrupt) takes
            // effect on the next attempt without restarting the whole
            // download.
            val current = loadState() ?: state
            val liveSegment = current.segments.getOrNull(i) ?: state.segments[i]
            if (liveSegment.completed) {
                validateCompletedSegment(liveSegment, subDir, finalName)
                i++
                continue
            }
            val outcome = downloadSegment(
                segment = liveSegment,
                initialUrl = url,
                initialHeaders = headers,
                initialReferer = referer,
                subDir = subDir,
                finalName = finalName,
                reResolver = reResolver,
                onUrlChanged = { newUrl, newHeaders, newReferer ->
                    url = newUrl
                    headers = newHeaders
                    referer = newReferer
                },
            )
            when (outcome) {
                is SegmentOutcome.Ok -> {
                    i++
                }
                is SegmentOutcome.GiveUp -> {
                    publishStatus(DownloadType.IsFailed)
                    return Outcome.Failure(
                        outcome.reason,
                        expiredUrl = outcome.expiredUrl,
                    )
                }
                SegmentOutcome.SegmentCorrupt -> {
                    // Segment was invalidated. Re-attempt the same
                    // segment index without advancing, bounded by
                    // a per-segment counter to avoid infinite loops
                    // when the server persistently returns wrong
                    // ranges.
                    val perSegmentCorruptRetries = perSegmentCorruptCount(i) + 1
                    recordPerSegmentCorrupt(i, perSegmentCorruptRetries)
                    if (perSegmentCorruptRetries > MAX_PER_SEGMENT_CORRUPT_RETRIES) {
                        publishStatus(DownloadType.IsFailed)
                        return Outcome.Failure(
                            "segment $i corrupted beyond retry limit",
                        )
                    }
                    // Do not advance `i`; the while loop will retry
                    // this segment.
                }
            }
            publishProgress(loadState() ?: current)
        }

        val finalSize = assembleFinalFile(subDir, finalName, state.segments)
        if (finalSize != state.totalSize) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure(
                "size mismatch: got $finalSize, expected ${state.totalSize}"
            )
        }

        cleanupParts(subDir, finalName, state.segments.size)
        cleanupAssembling(subDir, finalName)
        persistDownloadInfo(finalName, folder, basePath)
        clearState()
        publishStatus(DownloadType.IsDone)
        return Outcome.Success(finalSize)
    }

    // ---------- Pause handling ----------

    /**
     * Pause does not return from the supervisor. The in-flight
     * coroutine stays alive and waits for the user to resume. This
     * is the key behavioral difference from the Phase 2
     * implementation: pause is no longer interpreted as a network
     * failure that triggers auto-retry.
     */
    private suspend fun waitIfPaused() {
        while (true) {
            when (downloadStatus[downloadId]) {
                DownloadType.IsPaused -> {
                    ensureActive()
                    delay(PAUSE_POLL_MS)
                }
                DownloadType.IsStopped, DownloadType.IsFailed -> {
                    throw CancellationException("stopped")
                }
                else -> return
            }
        }
    }

    // ---------- Probing ----------

    private data class Probe(val totalSize: Long, val rangeSupported: Boolean)

    private suspend fun probe(url: String, headers: Map<String, String>, referer: String): Probe? {
        val head = runCatching {
            com.lagradost.cloudstream3.app.head(
                url = url,
                headers = headers,
                referer = referer,
                verify = false,
            )
        }.getOrNull() ?: return null

        val contentLength = head.size?.takeIf { it > 0L }
        val acceptRanges = head.headers["Accept-Ranges"]?.lowercase()?.trim()
        val rangeFromHead = when (acceptRanges) {
            "none" -> false
            "bytes" -> true
            null, "" -> if (contentLength != null) probeRange(url, headers, referer, contentLength) else null
            else -> if (contentLength != null) probeRange(url, headers, referer, contentLength) else null
        }
        if (contentLength == null) return null
        val supportsRange = rangeFromHead ?: acceptRanges == "bytes"
        return Probe(totalSize = contentLength, rangeSupported = supportsRange)
    }

    private suspend fun probeRange(
        url: String,
        headers: Map<String, String>,
        referer: String,
        totalSize: Long,
    ): Boolean? = runCatching {
        val probeEnd = minOf(maxOf(totalSize - 1L, 3L), 1023L)
        val resp = com.lagradost.cloudstream3.app.get(
            url = url,
            headers = headers + mapOf("Range" to "bytes=0-$probeEnd"),
            referer = referer,
            verify = false,
        )
        resp.code == 206
    }.getOrNull()

    // ---------- State ----------

    private fun stateKey() = "sanin_segment_state"

    private fun loadState(): SaninSegmentState? =
        runCatching { context.getKey<SaninSegmentState>(stateKey(), downloadId.toString()) }
            .getOrNull()

    /**
     * Loads existing state if compatible with the current probe,
     * otherwise creates fresh state. Validates each segment against
     * physical impossibility rules (negative size, end < start, out
     * of range, index conflict) before trusting the JSON.
     *
     * If validation removes segments (corrupt or impossible state),
     * the state is considered unrecoverable and fresh state is
     * created from scratch. The spec requires not blindly trusting
     * persisted JSON, and an empty segments list would cause
     * assembly to fail with no path forward.
     */
    private fun loadOrCreateState(
        url: String,
        headers: Map<String, String>,
        referer: String,
        totalSize: Long,
        desiredSegments: Int,
    ): SaninSegmentState {
        val existing = loadState()
        if (existing != null && existing.totalSize == totalSize && existing.url == url) {
            val sanitized = sanitizeState(existing, totalSize)
            if (sanitized.segments.size != existing.segments.size) {
                // Validation removed segments; treat as unrecoverable.
                return createFreshState(url, headers, referer, totalSize, desiredSegments)
            }
            if (sanitized != existing) persistState(sanitized)
            return sanitized
        }
        return createFreshState(url, headers, referer, totalSize, desiredSegments)
    }

    private fun createFreshState(
        url: String,
        headers: Map<String, String>,
        referer: String,
        totalSize: Long,
        desiredSegments: Int,
    ): SaninSegmentState {
        val ranges = SegmentMath.buildRanges(totalSize, desiredSegments)
        val segments = ranges.mapIndexed { i, r ->
            SaninSegmentState.SegmentState(
                index = i,
                startByte = r.start,
                endByte = r.end,
                downloadedBytes = 0L,
                completed = false,
            )
        }
        val fresh = SaninSegmentState(
            downloadId = downloadId,
            url = url,
            headers = headers,
            referer = referer,
            totalSize = totalSize,
            segments = segments,
            bytesDownloaded = 0L,
        )
        persistState(fresh)
        return fresh
    }

    private fun sanitizeState(
        state: SaninSegmentState,
        totalSize: Long,
    ): SaninSegmentState {
        val seen = HashSet<Int>()
        val sanitized = state.segments.mapNotNull { seg ->
            // Reject impossible values.
            if (seg.index < 0) return@mapNotNull null
            if (!seen.add(seg.index)) return@mapNotNull null
            if (seg.startByte < 0) return@mapNotNull null
            if (seg.endByte < seg.startByte) return@mapNotNull null
            if (seg.endByte >= totalSize) return@mapNotNull null
            val size = seg.endByte - seg.startByte + 1L
            if (size <= 0L) return@mapNotNull null
            val downloaded = seg.downloadedBytes.coerceIn(0L, size)
            val completed = seg.completed && downloaded >= size
            seg.copy(downloadedBytes = downloaded, completed = completed)
        }.sortedBy { it.index }
        val totalBytes = sanitized.sumOf { it.downloadedBytes }
        return state.copy(segments = sanitized, bytesDownloaded = totalBytes)
    }

    private fun persistState(state: SaninSegmentState) {
        context.setKey(stateKey(), downloadId.toString(), state)
    }

    private fun clearState() {
        context.removeKey(stateKey(), downloadId.toString())
    }

    /**
     * Validates a segment that the persisted state claims is complete
     * by re-checking the part file on disk. If the file is missing
     * or shorter than the expected segment size, the segment is
     * invalidated (only that one) so it will be re-downloaded without
     * restarting the whole download.
     */
    private fun validateCompletedSegment(
        segment: SaninSegmentState.SegmentState,
        subDir: SafeFile,
        finalName: String,
    ) {
        val partName = partNameFor(finalName, segment.index)
        val part = runCatching { subDir.findFile(partName) }.getOrNull()
        val onDisk = part?.let { runCatching { it.lengthOrThrow() }.getOrDefault(0L) } ?: 0L
        if (onDisk < segment.size) {
            // Truncate so the next attempt starts clean.
            runCatching {
                part?.openOutputStreamOrThrow(false)?.use { /* truncate */ }
            }
            val current = loadState() ?: return
            val newSegments = current.segments.toMutableList()
            val idx = newSegments.indexOfFirst { it.index == segment.index }
            if (idx >= 0) {
                newSegments[idx] = newSegments[idx].copy(
                    completed = false,
                    downloadedBytes = 0L,
                )
                persistState(
                    current.copy(
                        segments = newSegments,
                        bytesDownloaded = newSegments.sumOf { it.downloadedBytes },
                    )
                )
            }
        }
    }

    // ---------- Segment I/O ----------

    private sealed class SegmentOutcome {
        data object Ok : SegmentOutcome()
        data class GiveUp(val reason: String, val expiredUrl: Boolean = false) : SegmentOutcome()
        data object SegmentCorrupt : SegmentOutcome()
    }

    private suspend fun downloadSegment(
        segment: SaninSegmentState.SegmentState,
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        subDir: SafeFile,
        finalName: String,
        reResolver: UrlReResolver?,
        onUrlChanged: (String, Map<String, String>, String) -> Unit,
    ): SegmentOutcome = coroutineScope {
        val partName = partNameFor(finalName, segment.index)
        val partFile = runCatching { subDir.findFile(partName) }.getOrNull()
            ?: runCatching { subDir.createFileOrThrow(partName) }
                .getOrElse { return@coroutineScope SegmentOutcome.GiveUp("cannot create part: ${it.message}") }

        val onDisk = runCatching { partFile.lengthOrThrow() }.getOrDefault(0L)
        // If the on-disk file is larger than the segment size, the
        // previous attempt wrote garbage. Invalidate this segment by
        // truncating to zero so the next attempt starts clean.
        var existingOnDisk = if (onDisk > segment.size) {
            runCatching { partFile.openOutputStreamOrThrow(false).use { /* truncate */ } }
            0L
        } else onDisk
        var effectiveStart = segment.startByte + existingOnDisk
        var resume = SegmentMath.resumeRange(
            segmentStart = segment.startByte,
            segmentEnd = segment.endByte,
            resumeAt = effectiveStart,
        )
        if (resume == null) {
            markCompleted(segment)
            return@coroutineScope SegmentOutcome.Ok
        }
        var rangeHeader = "bytes=${resume.start}-${resume.end}"

        // Mutable holder so re-resolution can update the URL/headers
        // used by the next attempt within this segment loop.
        var currentUrl = initialUrl
        var currentHeaders = initialHeaders
        var currentReferer = initialReferer

        var attempt = 0
        var lastError: Throwable? = null
        var lastExpired = false
        while (attempt < MAX_SEGMENT_ATTEMPTS) {
            attempt++
            ensureActive()
            waitIfPaused()
            // Acquire OUTSIDE runCatching so that an exception thrown
            // while waiting for a permit (typically CancellationException
            // when the user stops the download) does not cause the
            // permit to leak. The `try/finally` below guarantees the
            // permit is released on every exit path: success,
            // exception from fetch, cancellation, or pause.
            SaninConnectionBudget.acquire()
            val outcome = try {
                runCatching {
                    fetchRangeToFile(
                        url = currentUrl,
                        headers = currentHeaders,
                        referer = currentReferer,
                        rangeHeader = rangeHeader,
                        expectedStart = resume.start,
                        expectedEnd = resume.end,
                        target = partFile,
                        resumeFromAbsolute = resume.start,
                        partStartAbsolute = segment.startByte,
                    )
                }
            } finally {
                SaninConnectionBudget.release()
            }
            if (outcome.isSuccess) {
                markCompleted(segment)
                return@coroutineScope SegmentOutcome.Ok
            }
            val err = outcome.exceptionOrNull()
            if (err is CancellationException) throw err

            // Classify: was this an expired URL?
            val expired = err is ExpiredUrlException
            lastExpired = lastExpired || expired
            // Permanent HTTP errors are not retried.
            val permanent = err is PermanentHttpException
            lastError = err
            logError(err)
            if (permanent) break

            // Try re-resolving once if the URL is expired AND
            // the user has not disabled this behavior in settings.
            if (expired && reResolver != null && attempt == 1 &&
                SaninDownloadAutoManager.shouldRetryExpiredUrls()) {
                val r = reResolver.reResolve()
                if (r != null) {
                    currentUrl = r.url
                    currentHeaders = r.headers
                    currentReferer = r.referer
                    onUrlChanged(r.url, r.headers, r.referer)
                    // Re-resolution produces a fresh URL. We cannot
                    // safely establish that the new resource is byte-
                    // for-byte identical to the old one, so the
                    // safe choice is to invalidate all segment state
                    // AND delete the on-disk part files. Leaving the
                    // old bytes on disk would cause the next attempt
                    // to append new-resource bytes onto the old
                    // resource's bytes, producing a corrupt file.
                    val current = loadState()
                    if (current != null) {
                        val invalidated = current.copy(
                            url = r.url,
                            headers = r.headers,
                            referer = r.referer,
                            segments = current.segments.map { seg ->
                                seg.copy(
                                    downloadedBytes = 0L,
                                    completed = false,
                                )
                            },
                            bytesDownloaded = 0L,
                        )
                        persistState(invalidated)
                    }
                    // Delete part files belonging to the old resource.
                    for (seg in current?.segments ?: emptyList()) {
                        runCatching {
                            subDir.findFile(partNameFor(finalName, seg.index))?.delete()
                        }
                    }
                    // Reset the in-memory existingOnDisk so this
                    // segment's current attempt restarts from byte
                    // zero. Without this the fetch would resume from
                    // `segment.startByte + existingOnDisk` and append
                    // new bytes to the (now-deleted) old bytes,
                    // producing a corrupt file.
                    runCatching {
                        partFile.openOutputStreamOrThrow(false).use { /* truncate */ }
                    }
                    existingOnDisk = 0L
                    // Recompute resume range from zero.
                    val newResume = SegmentMath.resumeRange(
                        segmentStart = segment.startByte,
                        segmentEnd = segment.endByte,
                        resumeAt = segment.startByte,
                    ) ?: run {
                        markCompleted(segment)
                        return@coroutineScope SegmentOutcome.Ok
                    }
                    resume = newResume
                    rangeHeader = "bytes=${newResume.start}-${newResume.end}"
                } else {
                    break
                }
            }
        }
        if (lastError is SegmentCorruptException) {
            // Invalidate just this segment. Truncate the part file
            // so the next attempt starts clean (otherwise the
            // existing garbage would be appended to).
            runCatching {
                partFile.openOutputStreamOrThrow(false).use { /* truncate */ }
            }
            val current = loadState()
            if (current != null) {
                val newSegments = current.segments.toMutableList()
                val idx = newSegments.indexOfFirst { it.index == segment.index }
                if (idx >= 0) {
                    newSegments[idx] = newSegments[idx].copy(
                        downloadedBytes = 0L,
                        completed = false,
                    )
                    persistState(
                        current.copy(
                            segments = newSegments,
                            bytesDownloaded = newSegments.sumOf { it.downloadedBytes },
                        )
                    )
                }
            }
            return@coroutineScope SegmentOutcome.SegmentCorrupt
        }
        SegmentOutcome.GiveUp(lastError?.message ?: "segment failed", expiredUrl = lastExpired)
    }

    private fun markCompleted(segment: SaninSegmentState.SegmentState) {
        val current = loadState() ?: return
        val newSegments = current.segments.toMutableList()
        val idx = newSegments.indexOfFirst { it.index == segment.index }
        if (idx >= 0) {
            newSegments[idx] = newSegments[idx].copy(
                downloadedBytes = newSegments[idx].size,
                completed = true,
            )
        }
        val bytes = newSegments.sumOf { it.downloadedBytes }
        persistState(current.copy(segments = newSegments, bytesDownloaded = bytes))
    }

    // ---------- HTTP error classification ----------

    private class ExpiredUrlException(msg: String) : IOException(msg)
    private class PermanentHttpException(msg: String) : IOException(msg)
    private class SegmentCorruptException(msg: String) : IOException(msg)

    private fun fetchRangeToFile(
        url: String,
        headers: Map<String, String>,
        referer: String,
        rangeHeader: String,
        expectedStart: Long,
        expectedEnd: Long,
        target: SafeFile,
        resumeFromAbsolute: Long,
        partStartAbsolute: Long,
    ) {
        val expectedSize = expectedEnd - expectedStart + 1L
        val response = com.lagradost.cloudstream3.app.get(
            url = url,
            headers = headers + mapOf("Range" to rangeHeader),
            referer = referer,
        )
            verify = false,
        )
        when (response.code) {
            206 -> Unit // expected
            401, 403, 410 -> throw ExpiredUrlException("HTTP ${response.code} (expired)")
            404 -> throw PermanentHttpException("HTTP 404 not found")
            in 400..499 -> throw PermanentHttpException("HTTP ${response.code}")
            in 500..599 -> throw IOException("server error ${response.code}")
            else -> throw IOException("Range not honored, code=${response.code}")
        }
        val contentRange = response.headers["Content-Range"]
        if (contentRange != null) {
            val parsed = parseContentRange(contentRange)
            if (parsed != null) {
                if (parsed.start != expectedStart || parsed.end != expectedEnd) {
                    throw SegmentCorruptException(
                        "Content-Range mismatch: got ${parsed.start}-${parsed.end}, " +
                            "expected $expectedStart-$expectedEnd"
                    )
                }
            }
        }
        val body = response.okhttpResponse.body ?: throw IOException("Empty body")
        val sourceStream: InputStream = body.byteStream()
        val append = (resumeFromAbsolute > partStartAbsolute)
        val out: OutputStream = target.openOutputStreamOrThrow(append)
        val buf = ByteArray(getSegmentBufferSize(context))
        var written = 0L
        try {
            while (true) {
                if (isStopped()) throw CancellationException("stopped")
                val toRead = minOf(buf.size.toLong(), expectedSize - written).toInt()
                if (toRead <= 0) break
                val read = sourceStream.read(buf, 0, toRead)
                if (read <= 0) {
                    if (written < expectedSize) {
                        throw IOException("Premature EOF at $written/$expectedSize")
                    }
                    break
                }
                out.write(buf, 0, read)
                written += read.toLong()
            }
            out.flush()
        } finally {
            runCatching { out.close() }
            runCatching { sourceStream.close() }
        }
        if (written != expectedSize) {
            throw IOException("Short segment read: $written of $expectedSize")
        }
        val length = runCatching { target.lengthOrThrow() }.getOrDefault(0L)
        val required = (resumeFromAbsolute - partStartAbsolute) + expectedSize
        if (length < required) {
            throw SegmentCorruptException("Truncated part file: $length < $required")
        }
    }

    private data class ContentRange(val start: Long, val end: Long)

    private fun parseContentRange(headerValue: String): ContentRange? {
        // Format: "bytes 0-249/1000" or "bytes 0-249/*"
        val space = headerValue.indexOf(' ')
        if (space < 0) return null
        val rangePart = headerValue.substring(space + 1)
        val dash = rangePart.indexOf('-')
        if (dash < 0) return null
        val slash = rangePart.indexOf('/')
        val endStr = if (slash > 0) rangePart.substring(dash + 1, slash) else rangePart.substring(dash + 1)
        val start = rangePart.substring(0, dash).toLongOrNull() ?: return null
        val end = endStr.trim().toLongOrNull() ?: return null
        return ContentRange(start, end)
    }

    // ---------- Assembly ----------

    /**
     * Assemble in two steps to avoid exposing a partial file as the
     * final file:
     *  1. write everything into `<finalName>.assembling`
     *  2. verify the assembled length equals the expected total size
     *  3. atomically rename `<finalName>.assembling` → `<finalName>`
     *
     * If the rename is not supported by the SafeFile API we fall
     * back to writing into the final file directly and validating,
     * which is the pre-Phase-3 behavior.
     */
    private fun assembleFinalFile(
        subDir: SafeFile,
        finalName: String,
        segments: List<SaninSegmentState.SegmentState>,
    ): Long {
        if (segments.isEmpty()) {
            // Defensive: should never happen because loadOrCreateState
            // rebuilds fresh state when validation removes segments.
            // Treat as size-mismatch failure.
            return 0L
        }
        val assemblingName = "$finalName.assembling"
        // Clear any previous leftover from a prior interrupted attempt.
        runCatching { subDir.findFile(assemblingName)?.delete() }

        val total = writeAssembled(subDir, assemblingName, segments)
        if (total <= 0L) return total

        val expected = segments.sumOf { it.size }
        if (total != expected) return total

        // Move .assembling → final. If the rename fails (some SAF
        // backends don't allow cross-name renames), copy bytes
        // instead, which preserves the "no partial file visible as
        // complete" guarantee.
        val target = runCatching { subDir.findFile(finalName) }.getOrNull()
        val renamed = runCatching {
            val src = subDir.findFile(assemblingName) ?: return@runCatching false
            val dst = target ?: subDir.createFileOrThrow(finalName)
            copyBytes(src, dst)
            src.delete()
            true
        }.getOrDefault(false)
        if (!renamed) {
            // Fallback: ensure the final file contains the assembled
            // bytes even if rename failed.
            val dst = target ?: subDir.createFileOrThrow(finalName)
            val src = subDir.findFile(assemblingName) ?: return total
            copyBytes(src, dst)
            runCatching { src.delete() }
        }
        return total
    }

    private fun writeAssembled(
        subDir: SafeFile,
        assemblingName: String,
        segments: List<SaninSegmentState.SegmentState>,
    ): Long {
        val outFile = subDir.createFileOrThrow(assemblingName)
        val sink: OutputStream = outFile.openOutputStreamOrThrow(false)
        var total = 0L
        try {
            for (seg in segments.sortedBy { it.index }) {
                val partName = partNameFor(finalName = assemblingName.removeSuffix(".assembling"), seg.index)
                val part = subDir.findFile(partName)
                    ?: throw IOException("missing part $partName")
                val expected = seg.size
                openPartInput(part).use { input ->
                    val buf = ByteArray(getSegmentBufferSize(context))
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        total += read.toLong()
                    }
                }
                val onDisk = runCatching { part.lengthOrThrow() }.getOrDefault(0L)
                if (onDisk < expected) {
                    throw IOException("Part $partName truncated: $onDisk < $expected")
                }
            }
            sink.flush()
        } finally {
            runCatching { sink.close() }
        }
        return total
    }

    /**
     * Returns a stream that reads from the part file. Uses
     * `SafeFile.filePath()` to get the underlying path; for direct
     * filesystem paths we open via `java.io.File`, for SAF
     * `content://` URIs we open via the Android `ContentResolver`.
     * This avoids depending on a SafeFile input-stream method that
     * the vendored library does not necessarily expose.
     */
    private fun openPartInput(part: SafeFile): InputStream {
        val path = runCatching { part.filePath() }.getOrNull()
            ?: throw IOException("Cannot read part: SafeFile.filePath() returned null")
        if (path.startsWith("content://")) {
            val uri = path.toUri()
            val cr = context.contentResolver
            return cr.openInputStream(uri)
                ?: throw IOException("ContentResolver returned null stream for $path")
        }
        return FileInputStream(File(path))
    }

    private fun copyBytes(src: SafeFile, dst: SafeFile) {
        openPartInput(src).use { input ->
            val out: OutputStream = dst.openOutputStreamOrThrow(false)
            try {
                val buf = ByteArray(getSegmentBufferSize(context))
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.flush()
            } finally {
                runCatching { out.close() }
            }
        }
    }

    private fun cleanupParts(subDir: SafeFile, finalName: String, segmentCount: Int) {
        for (i in 0 until segmentCount) {
            runCatching { subDir.findFile(partNameFor(finalName, i))?.delete() }
        }
    }

    private fun cleanupAssembling(subDir: SafeFile, finalName: String) {
        runCatching { subDir.findFile("$finalName.assembling")?.delete() }
    }

    private fun cleanupStaleParts(subDir: SafeFile, finalName: String, segmentCount: Int) {
        val current = (0 until segmentCount).map { partNameFor(finalName, it) }.toSet()
        runCatching {
            val all = subDir.listFiles() ?: return@runCatching
            for (f in all) {
                val n = f.name() ?: continue
                if ((n.startsWith("$finalName.part") && n !in current) ||
                    n == "$finalName.assembling"
                ) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    /**
     * Delete ALL part files for the given final name, regardless of
     * the current segment count. Used when the state was rebuilt
     * fresh (e.g. after a re-resolution that changed the total size,
     * or when persisted state was unrecoverable).
     */
    private fun cleanupAllParts(subDir: SafeFile, finalName: String) {
        runCatching {
            val all = subDir.listFiles() ?: return@runCatching
            for (f in all) {
                val n = f.name() ?: continue
                if (n.startsWith("$finalName.part")) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    private fun partNameFor(finalName: String, index: Int): String =
        "$finalName.part$index"

    // ---------- Progress / status ----------

    private fun publishProgress(state: SaninSegmentState) {
        val bytes = state.segments.sumOf { it.downloadedBytes }
        downloadProgressEvent.invoke(Triple(downloadId, bytes, state.totalSize))
    }

    private fun publishStatus(type: DownloadType) {
        downloadStatus[downloadId] = type
        VideoDownloadManager.downloadStatusEvent.invoke(downloadId to type)
    }

    private fun isStopped(): Boolean {
        val type = downloadStatus[downloadId]
        return type == DownloadType.IsStopped || type == DownloadType.IsFailed
    }

    // In-memory per-segment corrupt retry counters. Not persisted:
    // a fresh `run` resets the budget, which is the right behavior
    // because the persisted segment state is the source of truth
    // and a corrupt segment will simply be re-attempted on next run.
    private val perSegmentCorrupt = HashMap<Int, Int>()

    private fun perSegmentCorruptCount(index: Int): Int =
        perSegmentCorrupt[index] ?: 0

    private fun recordPerSegmentCorrupt(index: Int, value: Int) {
        perSegmentCorrupt[index] = value
    }

    private fun persistDownloadInfo(displayName: String, folder: String, basePath: String?) {
        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 0L,
            relativePath = folder,
            displayName = displayName,
            basePath = basePath,
        )
        context.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, downloadId.toString(), info)
    }

    companion object {
        const val MAX_SEGMENT_ATTEMPTS = 3
        const val DEFAULT_SEGMENT_BUFFER = 64 * 1024
        const val PAUSE_POLL_MS = 250L
        const val MAX_PER_SEGMENT_CORRUPT_RETRIES = 2

        fun getDefaultPerFileConnections(context: Context): Int {
            val raw = ani.sanin.settings.saving.PrefManager
                .getVal<Int>(ani.sanin.settings.saving.PrefName.DownloadConnectionsPerFile, 4)
            return raw.coerceIn(1, 8)
        }

        /** User-configurable read/write buffer for the segment copy loop. */
        fun getSegmentBufferSize(context: Context): Int {
            val kb = ani.sanin.settings.saving.PrefManager
                .getVal<Int>(ani.sanin.settings.saving.PrefName.DownloadSegmentBufferKb, 64)
            return (kb.coerceIn(16, 1024)) * 1024
        }
    }
}
