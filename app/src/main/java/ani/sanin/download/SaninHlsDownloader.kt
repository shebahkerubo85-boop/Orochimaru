package ani.sanin.download

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadProgressEvent
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
 * HLS / M3U8 downloader for the Sanin pipeline.
 *
 * Supports VOD-style HLS playlists. Handles:
 *  - Master playlist → media playlist resolution via the existing
 *    `HlsPlaylistParser` + `M3u8Helper2.hslLazy` for variant selection
 *  - Single playlist fetch: the master resolution, the ENDLIST
 *    check, and the media-segment parse all operate on a single
 *    in-memory playlist body (no duplicate HTTP requests)
 *  - AES-128 segment decryption via the existing [M3u8Helper2]
 *  - `EXT-X-MAP` initialization segment handling for fMP4 streams
 *  - Per-segment persistence
 *  - Pause/resume/cancel
 *  - Bounded retries
 *
 * Explicitly does NOT support:
 *  - Live / event-style playlists (no `#EXT-X-ENDLIST`)
 *  - DRM-protected streams (Widevine / FairPlay / PlayReady)
 *  - Byte-range HLS segments (`#EXT-X-BYTERANGE`) — detected and
 *    rejected with [Outcome.Unsupported] to avoid producing wrong
 *    output
 *  - Separate audio + video renditions
 *
 * The downloader follows the same lifecycle as
 * [SaninSegmentedDownloader]:
 *  - Pause via [waitIfPaused] (delay-and-wait, no auto-retry)
 *  - Cancel via `CancellationException` propagation
 *  - Bounded retries per segment
 *  - Persistent state in [SaninSegmentState] under the "HLS" format
 */
class SaninHlsDownloader(
    private val context: Context,
    private val downloadId: Int,
) {

    /**
     * Outcome of an HLS download attempt, mirroring
     * [SaninSegmentedDownloader.Outcome].
     */
    sealed class Outcome {
        data class Success(val finalSize: Long) : Outcome()
        data class Failure(
            val reason: String,
            val expiredUrl: Boolean = false,
            val unsupported: Boolean = false,
        ) : Outcome()
        /** Could not handle this playlist (e.g. live, DRM, malformed, byterange). */
        object Unsupported : Outcome()
    }

    /**
     * Callback that the supervisor provides for re-resolving an
     * expired URL. Currently accepted but the top-level integration
     * layer performs re-resolution; the HLS downloader reports
     * `Failure(expiredUrl = true)` on 401/403/410 and lets the
     * supervisor re-resolve.
     */
    fun interface UrlReResolver {
        suspend fun reResolve(): ReResolved?
    }

    data class ReResolved(
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
    )

    /**
     * Parsed media playlist. [initSegmentUrl] is set when the
     * playlist contains `#EXT-X-MAP`. [hasByteRange] is true when
     * the playlist contains `#EXT-X-BYTERANGE`; the downloader
     * refuses such playlists because safe byterange assembly is
     * out of scope for this phase.
     */
    private data class ParsedMediaPlaylist(
        val mediaPlaylistUrl: String,
        val segmentUrls: List<String>,
        val initSegmentUrl: String?,
        val isEncrypted: Boolean,
        val encryptionData: ByteArray,
        val encryptionIv: ByteArray,
        val hasByteRange: Boolean,
        val hasEndList: Boolean,
    )

    suspend fun run(
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        displayName: String,
        folder: String,
        parallelConnections: Int,
        @Suppress("UNUSED_PARAMETER") reResolver: UrlReResolver? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            runInternal(
                initialUrl = initialUrl,
                initialHeaders = initialHeaders,
                initialReferer = initialReferer,
                displayName = displayName,
                folder = folder,
                parallelConnections = parallelConnections,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedPlaylistException) {
            publishStatus(DownloadType.IsFailed)
            Outcome.Unsupported
        } catch (e: Throwable) {
            logError(e)
            Outcome.Failure(e.message ?: "HLS download failed")
        }
    }

    /**
     * Internal exception used to signal a clean unsupported exit
     * without spamming the log with a stack trace.
     */
    private class UnsupportedPlaylistException(msg: String) : IOException(msg)

    private suspend fun runInternal(
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        displayName: String,
        folder: String,
        parallelConnections: Int,
    ): Outcome {
        // ----- Step 1: Resolve to a media playlist, fetching
        //                the playlist body exactly once. -----
        val parsed = try {
            fetchAndParseMediaPlaylist(initialUrl, initialHeaders)
        } catch (e: ExpiredUrlException) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure(e.message ?: "expired playlist URL", expiredUrl = true)
        } catch (e: PermanentHttpException) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure(e.message ?: "playlist permanent error")
        } catch (e: Throwable) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure("could not fetch HLS playlist: ${e.message}")
        } ?: return Outcome.Failure("could not fetch HLS playlist", expiredUrl = false)
            .also { publishStatus(DownloadType.IsFailed) }

        // ----- Step 2: Reject unsafe playlists. -----
        if (!parsed.hasEndList) {
            publishStatus(DownloadType.IsFailed)
            throw UnsupportedPlaylistException("live / event playlist (no ENDLIST)")
        }
        if (parsed.hasByteRange) {
            publishStatus(DownloadType.IsFailed)
            throw UnsupportedPlaylistException("playlist uses EXT-X-BYTERANGE (not supported)")
        }
        if (parsed.segmentUrls.isEmpty()) {
            publishStatus(DownloadType.IsFailed)
            throw UnsupportedPlaylistException("playlist has no media segments")
        }

        // ----- Step 3: Build the segment list, including the init
        //                segment as segment 0 (so it is downloaded
        //                and assembled first). -----
        val orderedSegments: List<SaninSegmentState.SegmentState> = buildList {
            parsed.initSegmentUrl?.let { initUrl ->
                add(
                    SaninSegmentState.SegmentState(
                        index = 0,
                        startByte = 0L,
                        endByte = 0L,
                        downloadedBytes = 0L,
                        completed = false,
                        segmentUrl = initUrl,
                        byteRange = null,
                    )
                )
            }
            parsed.segmentUrls.forEachIndexed { mediaIdx, segUrl ->
                add(
                    SaninSegmentState.SegmentState(
                        index = (if (parsed.initSegmentUrl != null) 1 else 0) + mediaIdx,
                        startByte = 0L,
                        endByte = 0L,
                        downloadedBytes = 0L,
                        completed = false,
                        segmentUrl = segUrl,
                        byteRange = null,
                    )
                )
            }
        }

        val totalSizeEstimate: Long = orderedSegments.size.toLong() * 100_000L

        val state = loadOrCreateState(
            url = initialUrl,
            headers = initialHeaders,
            referer = initialReferer,
            totalSize = totalSizeEstimate,
            segments = orderedSegments,
            initSegmentUrl = parsed.initSegmentUrl,
        )

        val (baseFile, basePath) = context.getBasePath()
        val subDir = baseFile?.gotoDirectory(folder, createMissingDirectories = true)
            ?: return Outcome.Failure(
                if (baseFile == null) "No base path" else "Cannot create download folder"
            ).also { publishStatus(DownloadType.IsFailed) }
        val finalName = "$displayName.mp4"
        cleanupStaleParts(subDir, finalName, state.segments.size)
        if (state.bytesDownloaded == 0L) {
            cleanupAllParts(subDir, finalName)
        }

        publishStatus(DownloadType.IsDownloading)

        // ----- Step 4: Download all segments in order. -----
        val maxParallel = parallelConnections.coerceAtLeast(1)
        val pendingIndices = state.segments.map { it.index }.toMutableList()

        while (pendingIndices.isNotEmpty()) {
            waitIfPaused()
            val batch = pendingIndices.take(maxParallel).toList()
            pendingIndices.subList(0, batch.size).clear()

            coroutineScope {
                batch.forEach { idx ->
                    launch {
                        // Always read the live persisted state so
                        // an external invalidation (e.g. user
                        // re-resolve) takes effect on the next
                        // attempt without restarting the whole
                        // download.
                        val current = loadState() ?: state
                        val liveSegment = current.segments.getOrNull(idx) ?: return@launch
                        val result = downloadSegment(
                            segment = liveSegment,
                            headers = initialHeaders,
                            referer = initialReferer,
                            subDir = subDir,
                            finalName = finalName,
                            encryptionKey = parsed.encryptionData.takeIf { parsed.isEncrypted },
                            encryptionIv = parsed.encryptionIv.takeIf { parsed.isEncrypted },
                        )
                        when (result) {
                            SegmentOutcome.Ok -> markCompleted(liveSegment)
                            is SegmentOutcome.GiveUp -> {
                                publishStatus(DownloadType.IsFailed)
                                throw IOException("HLS segment $idx failed: ${result.reason}")
                            }
                        }
                        publishProgress(loadState() ?: current)
                    }
                }
            }
        }

        // ----- Step 5: Assemble the final file. -----
        val finalSize = assembleFinalFile(subDir, finalName, state.segments)
        if (finalSize <= 0L) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure("assembly produced empty file")
        }

        cleanupParts(subDir, finalName, state.segments.size)
        cleanupAssembling(subDir, finalName)
        persistDownloadInfo(finalName, folder, basePath)
        clearState()
        publishStatus(DownloadType.IsDone)
        return Outcome.Success(finalSize)
    }

    private sealed class SegmentOutcome {
        data object Ok : SegmentOutcome()
        data class GiveUp(val reason: String) : SegmentOutcome()
    }

    // ---------- Playlist fetch + parse (single HTTP request) ----------

    /**
     * Fetch the playlist exactly once and return a [ParsedMediaPlaylist].
     *
     * If the initial URL is a master playlist, the function uses
     * the existing [HlsPlaylistParser] (already in the project) to
     * select the best standalone variant, then recursively resolves
     * the chosen media playlist URL. The recursion cap matches the
     * existing [M3u8Helper2.hslLazy] depth check.
     */
    private suspend fun fetchAndParseMediaPlaylist(
        url: String,
        headers: Map<String, String>,
        depth: Int = 3,
    ): ParsedMediaPlaylist? {
        if (depth < 0) return null
        val resp = runCatching {
            com.lagradost.cloudstream3.app.get(
                url = url,
                headers = headers,
                verify = false,
            )
        }.getOrNull()
        if (resp == null) return null
        when (resp.code) {
            in 200..299 -> Unit
            401, 403, 410 -> throw ExpiredUrlException("HTTP ${resp.code} (expired)")
            404 -> throw PermanentHttpException("HTTP 404 not found")
            in 400..499 -> throw PermanentHttpException("HTTP ${resp.code}")
            in 500..599 -> throw IOException("server error ${resp.code}")
            else -> throw IOException("unexpected code=${resp.code}")
        }
        val body = resp.text

        val master = com.lagradost.cloudstream3.utils.HlsPlaylistParser.parse(url, body)
        if (master != null) {
            // Master playlist: pick the best standalone variant
            // (one that already contains audio) using the same
            // rule as [M3u8Helper2.hslLazy]. Fall back to the
            // first non-trick-play variant if no standalone exists.
            val variants = master.variants
            val candidates = variants.filter { it.isPlayableStandalone(master) }
                .ifEmpty { variants.filter { !it.isTrickPlay() } }
            val best = candidates.maxByOrNull {
                (it.format.width.coerceAtLeast(1).toLong() *
                    it.format.height.coerceAtLeast(1).toLong()) * 1000L +
                    it.format.averageBitrate.toLong()
            } ?: return null
            return fetchAndParseMediaPlaylist(best.url.toString(), headers, depth - 1)
        }

        // Media playlist. Parse it ourselves so we can detect
        // ENDLIST, BYTERANGE, MAP, KEY, and segment URLs without
        // making another request.
        return parseMediaPlaylist(url, body, headers)
    }

    /**
     * Parse a media playlist (not master) into a [ParsedMediaPlaylist].
     *
     * Detects:
     *  - `#EXT-X-ENDLIST` (VOD end marker)
     *  - `#EXT-X-BYTERANGE` (rejected)
     *  - `#EXT-X-MAP:URI="..."` (init segment)
     *  - `#EXT-X-KEY:METHOD=...,URI="...",IV=...` (AES-128)
     *  - `#EXTINF:<dur>\n<url>` (segment list)
     */
    private suspend fun parseMediaPlaylist(
        playlistUrl: String,
        body: String,
        headers: Map<String, String>,
    ): ParsedMediaPlaylist? {
        val lines = body.lines()
        if (lines.isEmpty() || !body.trimStart().startsWith("#EXTM3U")) return null

        var hasEndList = false
        var initSegmentUrl: String? = null
        var isEncrypted = false
        var encryptionData = byteArrayOf()
        var encryptionIv = byteArrayOf()
        var hasByteRange = false

        // Segment URL list in playlist order.
        val segmentUrls = mutableListOf<String>()
        val baseDir = urlParent(playlistUrl)

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line == "#EXT-X-ENDLIST" -> hasEndList = true
                line.startsWith("#EXT-X-BYTERANGE") -> hasByteRange = true
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = extractAttr(line, "URI")
                    if (uri != null) {
                        initSegmentUrl = resolveAgainst(baseDir, uri)
                    }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = extractAttr(line, "METHOD")
                    if (method == "AES-128") {
                        val keyUri = extractAttr(line, "URI")
                        val iv = extractAttr(line, "IV")
                        if (keyUri != null) {
                            val resolved = resolveAgainst(baseDir, keyUri)
                            val keyResp = runCatching {
                                com.lagradost.cloudstream3.app.get(
                                    url = resolved,
                                    headers = headers,
                                    verify = false,
                                )
                            }.getOrNull()
                            if (keyResp != null) {
                                when (keyResp.code) {
                                    in 200..299 -> {
                                        val bytes = runCatching {
                                            keyResp.okhttpResponse.body?.bytes()
                                        }.getOrNull()
                                        if (bytes != null && bytes.isNotEmpty()) {
                                            encryptionData = bytes
                                            isEncrypted = true
                                        }
                                    }
                                    401, 403, 410 ->
                                        throw ExpiredUrlException("HTTP ${keyResp.code} (expired) for key $resolved")
                                    else -> {
                                        // Treat any other key-fetch
                                        // error as an unsupported
                                        // playlist (encrypted stream
                                        // without a usable key).
                                        return null
                                    }
                                }
                            }
                            if (iv != null) {
                                encryptionIv = iv.encodeToByteArray()
                            }
                        }
                    } else if (method != null && method != "NONE") {
                        // SAMPLE-AES / Widevine / FairPlay / PlayReady
                        // are out of scope.
                        return null
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    // Next non-comment, non-blank line is the segment URL.
                    var j = i + 1
                    while (j < lines.size) {
                        val seg = lines[j].trim()
                        if (seg.isNotEmpty() && !seg.startsWith("#")) {
                            segmentUrls.add(resolveAgainst(baseDir, seg))
                            i = j // skip ahead
                            break
                        }
                        j++
                    }
                }
            }
            i++
        }

        return ParsedMediaPlaylist(
            mediaPlaylistUrl = playlistUrl,
            segmentUrls = segmentUrls,
            initSegmentUrl = initSegmentUrl,
            isEncrypted = isEncrypted,
            encryptionData = encryptionData,
            encryptionIv = encryptionIv,
            hasByteRange = hasByteRange,
            hasEndList = hasEndList,
        )
    }

    /**
     * Extract a quoted attribute value from a line like
     * `TAG:KEY="value",KEY2="value2"`. Returns null if the
     * attribute is missing.
     */
    private fun extractAttr(line: String, name: String): String? {
        val regex = Regex("""\b""" + Regex.escape(name) + """="([^"]+)"""")
        return regex.find(line)?.groupValues?.getOrNull(1)
    }

    private fun urlParent(url: String): String {
        val qIdx = url.indexOf('?')
        val hashIdx = url.indexOf('#')
        val end = when {
            qIdx >= 0 && hashIdx >= 0 -> minOf(qIdx, hashIdx)
            qIdx >= 0 -> qIdx
            hashIdx >= 0 -> hashIdx
            else -> url.length
        }
        val noQuery = url.substring(0, end)
        val lastSlash = noQuery.lastIndexOf('/')
        return if (lastSlash >= 0) noQuery.substring(0, lastSlash) else noQuery
    }

    private fun resolveAgainst(baseDir: String, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        // Use Android's Uri.resolve for proper relative-URL
        // resolution that handles nested paths correctly.
        return runCatching {
            val resolved = java.net.URI(
                if (baseDir.endsWith("/")) baseDir else "$baseDir/"
            ).resolve(value)
            resolved.toString()
        }.getOrDefault("$baseDir/$value")
    }

    // ---------- Pause handling ----------

    private suspend fun waitIfPaused() {
        while (true) {
            when (downloadStatus[downloadId]) {
                DownloadType.IsPaused -> {
                    currentCoroutineContext().ensureActive()
                    delay(250L)
                }
                DownloadType.IsStopped, DownloadType.IsFailed -> {
                    throw CancellationException("stopped")
                }
                else -> return
            }
        }
    }

    // ---------- State ----------

    private fun stateKey() = "sanin_segment_state"

    private fun loadState(): SaninSegmentState? =
        runCatching {
            context.getKey<SaninSegmentState>(stateKey(), downloadId.toString())
        }.getOrNull()

    /**
     * Reuse persisted state only when the *current* playlist
     * matches the persisted one in format, segment count, and
     * segment URL identity. If any segment URL changed, all
     * completed segments are invalidated.
     */
    private fun loadOrCreateState(
        url: String,
        headers: Map<String, String>,
        referer: String,
        totalSize: Long,
        segments: List<SaninSegmentState.SegmentState>,
        initSegmentUrl: String?,
    ): SaninSegmentState {
        val existing = loadState()
        if (existing != null &&
            existing.url == url &&
            existing.format == "HLS" &&
            existing.segments.size == segments.size
        ) {
            // Compare each segment URL. If anything changed, the
            // persisted state is stale and we start fresh.
            val urlsMatch = existing.segments.zip(segments).all { (a, b) ->
                a.segmentUrl == b.segmentUrl
            }
            if (urlsMatch) {
                return existing
            }
        }
        val fresh = SaninSegmentState(
            downloadId = downloadId,
            url = url,
            headers = headers,
            referer = referer,
            totalSize = totalSize,
            segments = segments,
            bytesDownloaded = 0L,
            format = "HLS",
            variantId = initSegmentUrl,
            variantSegmentCount = segments.size,
        )
        persistState(fresh)
        return fresh
    }

    private fun persistState(state: SaninSegmentState) {
        context.setKey(stateKey(), downloadId.toString(), state)
    }

    private fun clearState() {
        context.removeKey(stateKey(), downloadId.toString())
    }

    private fun markCompleted(segment: SaninSegmentState.SegmentState) {
        val current = loadState() ?: return
        val newSegments = current.segments.toMutableList()
        val idx = newSegments.indexOfFirst { it.index == segment.index }
        if (idx >= 0) {
            newSegments[idx] = newSegments[idx].copy(
                downloadedBytes = 1L,
                completed = true,
            )
        }
        val bytes = newSegments.count { it.completed }.toLong()
        persistState(current.copy(segments = newSegments, bytesDownloaded = bytes))
    }

    // ---------- Segment download ----------

    private suspend fun downloadSegment(
        segment: SaninSegmentState.SegmentState,
        headers: Map<String, String>,
        referer: String,
        subDir: SafeFile,
        finalName: String,
        encryptionKey: ByteArray?,
        encryptionIv: ByteArray?,
    ): SegmentOutcome {
        val segUrl = segment.segmentUrl ?: return SegmentOutcome.GiveUp("no segment URL")
        if (segment.completed) return SegmentOutcome.Ok

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_SEGMENT_ATTEMPTS) {
            attempt++
            currentCoroutineContext().ensureActive()
            waitIfPaused()
            SaninConnectionBudget.acquire()
            val outcome = try {
                runCatching {
                    fetchSegmentToFile(
                        url = segUrl,
                        headers = headers,
                        referer = referer,
                        subDir = subDir,
                        finalName = finalName,
                        segmentIndex = segment.index,
                        encryptionKey = encryptionKey,
                        encryptionIv = encryptionIv,
                    )
                }
            } finally {
                SaninConnectionBudget.release()
            }
            if (outcome.isSuccess) return SegmentOutcome.Ok
            val err = outcome.exceptionOrNull()
            if (err is CancellationException) throw err
            lastError = err
            logError(err ?: Throwable("unknown segment error"))
            if (err is PermanentHttpException) break
        }
        return SegmentOutcome.GiveUp(lastError?.message ?: "segment failed")
    }

    private suspend fun fetchSegmentToFile(
        url: String,
        headers: Map<String, String>,
        referer: String,
        subDir: SafeFile,
        finalName: String,
        segmentIndex: Int,
        encryptionKey: ByteArray?,
        encryptionIv: ByteArray?,
    ) {
        val partName = partNameFor(finalName, segmentIndex)
        val partFile = runCatching { subDir.findFile(partName) }.getOrNull()
            ?: runCatching { subDir.createFileOrThrow(partName) }
                .getOrElse { throw IOException("cannot create part: ${it.message}") }

        val response = com.lagradost.cloudstream3.app.get(
            url = url,
            headers = headers,
            referer = referer,
            verify = false,
        )
        when (response.code) {
            in 200..299 -> Unit
            401, 403, 410 -> throw ExpiredUrlException("HTTP ${response.code} (expired)")
            404 -> throw PermanentHttpException("HTTP 404 not found")
            in 400..499 -> throw PermanentHttpException("HTTP ${response.code}")
            in 500..599 -> throw IOException("server error ${response.code}")
            else -> throw IOException("unexpected code=${response.code}")
        }
        val body = response.okhttpResponse.body ?: throw IOException("Empty body")
        val sourceStream: InputStream = body.byteStream()
        val out: OutputStream = partFile.openOutputStreamOrThrow(false)
        val decrypted: InputStream = if (encryptionKey != null) {
            val raw = readAllBytes(sourceStream)
            sourceStream.close()
            val iv = encryptionIv?.takeIf { it.isNotEmpty() } ?: computeHlsIv(segmentIndex)
            val plain = M3u8Helper2.getDecrypted(encryptionKey, raw, iv, segmentIndex)
            java.io.ByteArrayInputStream(plain)
        } else {
            sourceStream
        }
        try {
            val buf = ByteArray(SaninSegmentedDownloader.getSegmentBufferSize(context))
            while (true) {
                if (isStopped()) throw CancellationException("stopped")
                val read = decrypted.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
            }
            out.flush()
        } finally {
            runCatching { out.close() }
            runCatching { decrypted.close() }
        }
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val buf = ByteArray(SaninSegmentedDownloader.getSegmentBufferSize(context))
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Compute the default HLS AES-128 IV for a segment index. This
     * matches [M3u8Helper2.defaultIv] which is `private`. The IV is
     * the 128-bit big-endian representation of `index + 1`.
     */
    private fun computeHlsIv(index: Int): ByteArray {
        val n = (index + 1).toLong() and -1L
        return ByteArray(16) { i ->
            val shift = (15 - i) * 8
            ((n shr shift) and 0xFFL).toByte()
        }
    }

    private class ExpiredUrlException(msg: String) : IOException(msg)
    private class PermanentHttpException(msg: String) : IOException(msg)

    // ---------- Assembly ----------

    private fun assembleFinalFile(
        subDir: SafeFile,
        finalName: String,
        segments: List<SaninSegmentState.SegmentState>,
    ): Long {
        if (segments.isEmpty()) return 0L
        val assemblingName = "$finalName.assembling"
        runCatching { subDir.findFile(assemblingName)?.delete() }

        val outFile = subDir.createFileOrThrow(assemblingName)
        val sink: OutputStream = outFile.openOutputStreamOrThrow(false)
        var total = 0L
        try {
            for (seg in segments.sortedBy { it.index }) {
                val partName = partNameFor(finalName, seg.index)
                val part = subDir.findFile(partName)
                    ?: throw IOException("missing part $partName")
                openPartInput(part).use { input ->
                    val buf = ByteArray(SaninSegmentedDownloader.getSegmentBufferSize(context))
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        total += read.toLong()
                    }
                }
            }
            sink.flush()
        } finally {
            runCatching { sink.close() }
        }
        if (total <= 0L) return total

        val target = runCatching { subDir.findFile(finalName) }.getOrNull()
        val renamed = runCatching {
            val src = subDir.findFile(assemblingName) ?: return@runCatching false
            val dst = target ?: subDir.createFileOrThrow(finalName)
            copyBytes(src, dst)
            src.delete()
            true
        }.getOrDefault(false)
        if (!renamed) {
            val dst = target ?: subDir.createFileOrThrow(finalName)
            val src = subDir.findFile(assemblingName) ?: return total
            copyBytes(src, dst)
            runCatching { src.delete() }
        }
        return total
    }

    private fun copyBytes(src: SafeFile, dst: SafeFile) {
        openPartInput(src).use { input ->
            val out: OutputStream = dst.openOutputStreamOrThrow(false)
            try {
                val buf = ByteArray(SaninSegmentedDownloader.getSegmentBufferSize(context))
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

    // ---------- Part-file IO ----------

    private fun openPartInput(part: SafeFile): InputStream {
        val path = part.filePath()
        return if (path == null) {
            val uri = android.net.Uri.parse(path ?: error("part has no path"))
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open part: resolver returned null")
        } else if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(android.net.Uri.parse(path))
                ?: throw IOException("cannot open part: resolver returned null")
        } else {
            FileInputStream(File(path))
        }
    }

    private fun partNameFor(finalName: String, index: Int): String =
        "$finalName.part$index"

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

    // ---------- Status / progress ----------

    private fun publishStatus(type: DownloadType) {
        downloadStatus[downloadId] = type
    }

    private fun isStopped(): Boolean =
        downloadStatus[downloadId] == DownloadType.IsStopped ||
            downloadStatus[downloadId] == DownloadType.IsFailed

    private fun publishProgress(state: SaninSegmentState) {
        val completed = state.segments.count { it.completed }
        val total = state.segments.size
        downloadProgressEvent.invoke(Triple(downloadId, completed.toLong(), total.toLong()))
    }

    private fun persistDownloadInfo(
        finalName: String,
        folder: String,
        basePath: String?,
    ) {
        val info = com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo(
            totalBytes = 0L,
            relativePath = folder,
            displayName = finalName,
            basePath = basePath ?: "",
        )
        context.setKey(
            com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_DOWNLOAD_INFO,
            downloadId.toString(),
            info,
        )
    }

    companion object {
        private const val MAX_SEGMENT_ATTEMPTS = 3
    }
}
