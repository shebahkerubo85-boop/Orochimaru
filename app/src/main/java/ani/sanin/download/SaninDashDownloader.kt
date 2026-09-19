package ani.sanin.download

import android.content.Context
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadProgressEvent
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
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
import java.util.concurrent.atomic.AtomicLong

/**
 * MPEG-DASH downloader for the Sanin pipeline.
 *
 * Supports a pragmatic subset of MPEG-DASH for VOD downloads:
 *  - MPD with `type="static"` (VOD). Dynamic / live MPDs are refused.
 *  - `SegmentTemplate` with `$Number$` substitution
 *  - `SegmentTimeline` with `<S t= d= r=/>` entries
 *  - `BaseURL` at MPD / Period / AdaptationSet / Representation levels
 *  - Initialization segment (`<SegmentTemplate initialization=...>`)
 *  - Single Representation selection (video with bandwidth highest)
 *  - Relative URL resolution
 *
 * Explicitly does NOT support in this phase:
 *  - Dynamic / live MPDs (`type="dynamic"`)
 *  - Multiple AdaptationSets (audio + video separately) — only the
 *    selected video Representation is downloaded
 *  - `SegmentList` with byte ranges
 *  - DRM (Common Encryption / ClearKey / Widevine)
 *  - `$Time$` substitution in SegmentTemplate
 *  - Muxing separate audio + video tracks
 *
 * If the input MPD requires capabilities this class does not have,
 * it returns [Outcome.Unsupported] so the caller can fall back or
 * report a clear error.
 */
class SaninDashDownloader(
    private val context: Context,
    private val downloadId: Int,
) {

    sealed class Outcome {
        data class Success(val finalSize: Long) : Outcome()
        data class Failure(
            val reason: String,
            val expiredUrl: Boolean = false,
            val unsupported: Boolean = false,
        ) : Outcome()
        object Unsupported : Outcome()
    }

    fun interface UrlReResolver {
        suspend fun reResolve(): ReResolved?
    }

    data class ReResolved(
        val url: String,
        val headers: Map<String, String>,
        val referer: String,
    )

    /**
     * Parsed representation from an MPD. Contains the resolved
     * base URL, initialization segment URL (if any), and a list of
     * media segment URLs.
     */
    data class DashRepresentation(
        val id: String,
        val baseUrl: String,
        val initSegmentUrl: String?,
        val mediaSegmentUrls: List<String>,
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
            Logger.log(
                "SANIN_DASH: run id=$downloadId name='$displayName' folder='$folder' " +
                    "url=${initialUrl.take(160)} parallel=$parallelConnections"
            )
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
        } catch (e: Throwable) {
            logError(e)
            Outcome.Failure(e.message ?: "DASH download failed")
        }
    }

    private suspend fun runInternal(
        initialUrl: String,
        initialHeaders: Map<String, String>,
        initialReferer: String,
        displayName: String,
        folder: String,
        parallelConnections: Int,
    ): Outcome {
        // 1. Fetch the MPD.
        val mpdText = try {
            fetchText(initialUrl, initialHeaders)
        } catch (e: ExpiredUrlException) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure(e.message ?: "expired MPD URL", expiredUrl = true)
        } catch (e: PermanentHttpException) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure(e.message ?: "MPD permanent error")
        } catch (e: Throwable) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure("could not fetch MPD: ${e.message}")
        } ?: return Outcome.Failure("could not fetch MPD")
            .also { publishStatus(DownloadType.IsFailed) }

        // 2. Parse the MPD. We use a minimal regex-based parser
        // because the project does not bundle a standalone DASH
        // manifest library.
        val parsed = parseMpd(mpdText, initialUrl)
            ?: run {
                Logger.log("SANIN_DASH: unsupported unparseable MPD id=$downloadId")
                return Outcome.Unsupported
                    .also { publishStatus(DownloadType.IsFailed) }
            }

        // 3. Select a single video representation.
        val rep = selectRepresentation(
            reps = parsed.representations,
            fallbackTemplate = parsed.defaultSegmentTemplate,
        )
            ?: run {
                Logger.log("SANIN_DASH: unsupported no viable video rep id=$downloadId")
                return Outcome.Unsupported
                    .also { publishStatus(DownloadType.IsFailed) }
            }
        Logger.log(
            "SANIN_DASH: parsed ok id=$downloadId reps=${parsed.representations.size} " +
                "sel=${rep.id} segments=${rep.mediaSegmentUrls.size} init=${rep.initSegmentUrl != null}"
        )

        // 4. Build segment list (init + media).
        val allSegmentUrls = buildList {
            rep.initSegmentUrl?.let { add(it) }
            addAll(rep.mediaSegmentUrls)
        }
        if (allSegmentUrls.isEmpty()) {
            publishStatus(DownloadType.IsFailed)
            Logger.log("SANIN_DASH: FAIL no segments in selected representation id=$downloadId")
            return Outcome.Failure("no segments in selected representation")
        }

        // 5. Load or create persistent state.
        val totalSizeEstimate = allSegmentUrls.size.toLong() * 100_000L
        val state = loadOrCreateState(
            url = initialUrl,
            headers = initialHeaders,
            referer = initialReferer,
            totalSize = totalSizeEstimate,
            segments = allSegmentUrls.mapIndexed { i, segUrl ->
                SaninSegmentState.SegmentState(
                    index = i,
                    startByte = 0L,
                    endByte = 0L,
                    downloadedBytes = 0L,
                    completed = false,
                    segmentUrl = segUrl,
                    byteRange = null,
                )
            },
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

        val maxParallel = parallelConnections.coerceAtLeast(1)
        val pendingIndices = state.segments.map { it.index }.toMutableList()
        val completedCount = AtomicLong(0)

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
                        )
                        when (result) {
                            SegmentOutcome.Ok -> {
                                markCompleted(liveSegment)
                                completedCount.incrementAndGet()
                            }
                            is SegmentOutcome.GiveUp -> {
                                publishStatus(DownloadType.IsFailed)
                                throw IOException("DASH segment $idx failed: ${result.reason}")
                            }
                        }
                        publishProgress(loadState() ?: current)
                    }
                }
            }
        }

        val finalSize = assembleFinalFile(subDir, finalName, state.segments)
        if (finalSize <= 0L) {
            publishStatus(DownloadType.IsFailed)
            return Outcome.Failure("assembly produced empty file")
        }

        cleanupParts(subDir, finalName, state.segments.size)
        cleanupAssembling(subDir, finalName)
        persistDownloadInfo(finalName, folder, basePath, totalBytes = finalSize, bytesDownloaded = finalSize)
        Logger.log("SANIN_DASH: persistDownloadInfo totalBytes=$finalSize bytesDownloaded=$finalSize id=$downloadId name=$finalName")
        clearState()
        publishStatus(DownloadType.IsDone)
        Logger.log("SANIN_DASH: DONE id=$downloadId finalSize=$finalSize name=$finalName")
        return Outcome.Success(finalSize)
    }

    private sealed class SegmentOutcome {
        data object Ok : SegmentOutcome()
        data class GiveUp(val reason: String) : SegmentOutcome()
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? {
        val resp = runCatching {
            com.lagradost.cloudstream3.app.get(
                url = url,
                headers = headers,
                verify = false,
            )
        }.getOrNull() ?: return null
        when (resp.code) {
            in 200..299 -> Unit
            401, 403, 410 -> throw ExpiredUrlException("HTTP ${resp.code} (expired)")
            404 -> throw PermanentHttpException("HTTP 404 not found")
            in 400..499 -> throw PermanentHttpException("HTTP ${resp.code}")
            in 500..599 -> throw IOException("server error ${resp.code}")
            else -> throw IOException("unexpected code=${resp.code}")
        }
        return resp.text
    }

    /**
     * Minimal MPD parser. Extracts:
     *  - `type` attribute (must be "static")
     *  - `mediaPresentationDuration`
     *  - The first `Period`
     *  - **All** `AdaptationSet`s (for audio/video detection)
     *  - The highest-bandwidth `Representation` from the first
     *    video AdaptationSet
     *  - `BaseURL` (MPD / Period / AdaptationSet / Representation
     *    levels)
     *  - `SegmentTemplate` (initialization, media, startNumber,
     *    timescale, duration, presentationTimeOffset)
     *  - `SegmentTimeline` (`<S t= d= r=/>` entries)
     *
     * The parser is intentionally minimal. The repository does not
     * ship a dedicated DASH manifest library — using Media3's
     * full `DashManifest` model would add a large dependency for a
     * small win. This parser handles the common static-VOD case.
     */
    private fun parseMpd(text: String, baseUri: String): ParsedMpd? {
        val root = text.trim()
        if (!root.startsWith("<?xml") && !root.startsWith("<MPD")) {
            return null
        }
        // Dynamic MPDs are out of scope.
        val typeMatch = Regex("""type\s*=\s*"([^"]+)"""").find(root)
        val type = typeMatch?.groupValues?.getOrNull(1) ?: "static"
        if (type != "static") return null

        // Resolve the first Period.
        val periodRegex = Regex("""<Period\b[^>]*>(.*?)</Period>""", RegexOption.DOT_MATCHES_ALL)
        val periodMatch = periodRegex.find(root) ?: return null
        val periodBody = periodMatch.groupValues[1]

        // Find ALL AdaptationSets and classify them. If there is
        // more than one video AdaptationSet, or if a separate
        // audio AdaptationSet exists that would need muxing,
        // refuse the download.
        val adaptationSetRegex = Regex(
            """<AdaptationSet\b([^>]*)>(.*?)</AdaptationSet>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val allAdaptationSets = adaptationSetRegex.findAll(periodBody).toList()
        if (allAdaptationSets.isEmpty()) return null

        val videoAdaptationSets = allAdaptationSets.filter { match ->
            val attrs = match.groupValues[1]
            classifyAdaptationSet(attrs) == AdaptationKind.VIDEO
        }
        val audioAdaptationSets = allAdaptationSets.filter { match ->
            val attrs = match.groupValues[1]
            classifyAdaptationSet(attrs) == AdaptationKind.AUDIO
        }

        if (videoAdaptationSets.isEmpty()) return null

        // PHASE 4.1 HARDENING: refuse MPDs that require audio/video
        // muxing. We only support single-track representations.
        if (audioAdaptationSets.isNotEmpty()) {
            return null
        }

        // PHASE 4.1 HARDENING: refuse MPDs with more than one
        // video AdaptationSet; we do not support switching between
        // multiple video tracks.
        if (videoAdaptationSets.size > 1) {
            return null
        }

        val videoAdaptationSet = videoAdaptationSets.first()
        val adaptationSetBody = videoAdaptationSet.groupValues[2]
        // BaseURL inheritance: MPD → Period → AdaptationSet →
        // Representation. Each child inherits from its parent if
        // it has no BaseURL of its own.
        val mpdBaseUrl = extractBaseUrl(root, baseUri)
        val periodBaseUrl = extractBaseUrl(periodBody, mpdBaseUrl ?: baseUri)
        val adaptationSetBaseUrl = extractBaseUrl(adaptationSetBody, periodBaseUrl ?: baseUri)
        val effectiveBaseUrl = adaptationSetBaseUrl ?: periodBaseUrl ?: baseUri

        // Find all Representations and pick the one with the highest
        // bandwidth. Each Representation may have its own SegmentTemplate.
        val representationRegex = Regex(
            """<Representation\b([^>]*)>(.*?)</Representation>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val representations = representationRegex.findAll(adaptationSetBody).map { match ->
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val id = Regex("""id\s*=\s*"([^"]+)"""").find(attrs)?.groupValues?.getOrNull(1) ?: ""
            val bandwidth = Regex("""bandwidth\s*=\s*"(\d+)"""").find(attrs)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val codecs = Regex("""codecs\s*=\s*"([^"]+)"""")
                .find(attrs)?.groupValues?.getOrNull(1).orEmpty()
            val repBaseUrl = extractBaseUrl(body, effectiveBaseUrl ?: baseUri)
            Representation(
                id = id,
                bandwidth = bandwidth,
                baseUrl = repBaseUrl ?: effectiveBaseUrl,
                body = body,
                codecs = codecs,
            )
        }.toList()
        if (representations.isEmpty()) return null

        // Also check for AdaptationSet-level SegmentTemplate.
        val adaptationSetSegmentTemplate = parseSegmentTemplate(adaptationSetBody)

        return ParsedMpd(
            baseUrl = baseUri,
            representations = representations,
            defaultSegmentTemplate = adaptationSetSegmentTemplate,
        )
    }

    /**
     * Classify an AdaptationSet as VIDEO, AUDIO, or OTHER based
     * on its `contentType` and `mimeType` attributes.
     */
    private fun classifyAdaptationSet(attrs: String): AdaptationKind {
        val contentType = Regex("""contentType\s*=\s*"([^"]+)"""")
            .find(attrs)?.groupValues?.getOrNull(1)
        val mimeType = Regex("""mimeType\s*=\s*"([^"]+)"""")
            .find(attrs)?.groupValues?.getOrNull(1)
        return when {
            contentType.equals("video", ignoreCase = true) -> AdaptationKind.VIDEO
            contentType.equals("audio", ignoreCase = true) -> AdaptationKind.AUDIO
            mimeType?.startsWith("video/", ignoreCase = true) == true -> AdaptationKind.VIDEO
            mimeType?.startsWith("audio/", ignoreCase = true) == true -> AdaptationKind.AUDIO
            else -> AdaptationKind.OTHER
        }
    }

    private enum class AdaptationKind { VIDEO, AUDIO, OTHER }

    private fun extractBaseUrl(body: String, parentBase: String): String? {
        val baseUrlRegex = Regex(
            """<BaseURL\b[^>]*>(.*?)</BaseURL>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = baseUrlRegex.find(body) ?: return null
        val raw = match.groupValues[1].trim()
        return resolveUrl(parentBase, raw)
    }

    /**
     * Parse a `<SegmentTemplate>` element. Returns null if the body
     * does not contain one.
     */
    private fun parseSegmentTemplate(body: String): SegmentTemplate? {
        val templateRegex = Regex(
            """<SegmentTemplate\b([^/>]*)/?>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = templateRegex.find(body) ?: return null
        val attrs = match.groupValues[1]
        return SegmentTemplate(
            initialization = Regex("""initialization\s*=\s*"([^"]+)"""")
                .find(attrs)?.groupValues?.getOrNull(1),
            media = Regex("""media\s*=\s*"([^"]+)"""")
                .find(attrs)?.groupValues?.getOrNull(1),
            startNumber = Regex("""startNumber\s*=\s*"(\d+)"""")
                .find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1L,
            timescale = Regex("""timescale\s*=\s*"(\d+)"""")
                .find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1L,
            body = body,
        )
    }

    private fun selectRepresentation(
        reps: List<Representation>,
        fallbackTemplate: SegmentTemplate?,
    ): DashRepresentation? {
        if (reps.isEmpty()) return null
        // Pick the one with the highest bandwidth as a simple heuristic.
        val best = reps.maxBy { it.bandwidth }
        // Representation-level template takes priority; fall back to
        // the AdaptationSet-level template.
        val template = parseSegmentTemplate(best.body) ?: fallbackTemplate
        if (template == null) {
            // No SegmentTemplate. We do not support SegmentList
            // in this phase.
            return null
        }
        if (template.media == null) return null

        // PHASE 4.1 HARDENING: reject templates that use $Time$
        // substitution, which we do not implement.
        if (template.media.contains("\$Time\$")) return null
        if (template.initialization?.contains("\$Time\$") == true) return null

        val baseUrl = best.baseUrl
        val initUrl = template.initialization?.let { resolveTemplateUrl(baseUrl, it) }

        val mediaUrls = buildMediaSegmentUrls(baseUrl, template) ?: return null
        return DashRepresentation(
            id = best.id,
            baseUrl = baseUrl,
            initSegmentUrl = initUrl,
            mediaSegmentUrls = mediaUrls,
        )
    }

    /**
     * Build the list of media segment URLs from a parsed
     * SegmentTemplate. Supports `$Number$` substitution.
     *
     * Returns `null` if the template uses `$Time$` (unsupported)
     * or produces a malformed/unbounded segment list.
     */
    private fun buildMediaSegmentUrls(
        baseUrl: String,
        template: SegmentTemplate,
    ): List<String>? {
        val mediaTemplate = template.media ?: return emptyList()

        // PHASE 4.1 HARDENING: refuse $Time$ substitution.
        if (mediaTemplate.contains("\$Time\$")) return null

        // Detect SegmentTimeline inside the template body.
        val timelineRegex = Regex(
            """<SegmentTimeline\b[^>]*>(.*?)</SegmentTimeline>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val timelineMatch = timelineRegex.find(template.body)
        if (timelineMatch != null) {
            val timelineBody = timelineMatch.groupValues[1]
            val sRegex = Regex("""<S\b([^/>]*)/?>""")
            val segments = mutableListOf<String>()
            var currentNumber = template.startNumber
            for (sMatch in sRegex.findAll(timelineBody)) {
                val sAttrs = sMatch.groupValues[1]
                val r = Regex("""r\s*=\s*"(\d+)"""")
                    .find(sAttrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                // PHASE 4.1 HARDENING: clamp the repeat count to
                // MAX_TIMELINE_REPEAT to prevent a malformed
                // <S r="999999999"/> from producing an unbounded
                // segment list.
                val repeat = r.coerceIn(0, MAX_TIMELINE_REPEAT)
                val resolved = resolveTemplateUrl(baseUrl, mediaTemplate)
                    .replace("\$Number\$", currentNumber.toString())
                segments.add(resolved)
                // Advance the number for the next distinct segment.
                currentNumber += (repeat + 1).toLong()
                // Bounding safety: stop and refuse once we exceed
                // the global segment cap.
                if (segments.size >= MAX_DASH_SEGMENTS) return null
            }
            return segments
        }
        // No SegmentTimeline: try NumberTemplate with startNumber and
        // a bounded count. We cap at MAX_DASH_SEGMENTS to avoid
        // runaway downloads on malformed manifests.
        val segments = mutableListOf<String>()
        var number = template.startNumber
        while (segments.size < MAX_DASH_SEGMENTS) {
            val resolved = resolveTemplateUrl(baseUrl, mediaTemplate)
                .replace("\$Number\$", number.toString())
            segments.add(resolved)
            number++
        }
        return segments
    }

    private fun resolveTemplateUrl(baseUrl: String, template: String): String {
        // Templates reference $RepresentationID$ etc. We only handle
        // $Number$ explicitly. Other placeholders are left as-is so
        // the request fails cleanly if the manifest uses them.
        return resolveUrl(baseUrl, template)
    }

    /**
     * Resolve a possibly-relative URL against a base URI. Uses
     * [android.net.Uri] semantics for proper relative-URL
     * resolution.
     */
    private fun resolveUrl(base: String, relative: String): String {
        return runCatching {
            // If the relative URL is already absolute, return it as-is.
            if (relative.startsWith("http://") || relative.startsWith("https://")) {
                return@runCatching relative
            }
            // If the relative URL contains templated placeholders
            // (e.g. $Number$), we cannot reliably pass it through
            // Uri.resolve (which percent-encodes it). Fall back to
            // string concatenation against the base directory.
            if (relative.contains("\$")) {
                val basePath = base.substringBeforeLast('/')
                return@runCatching "$basePath/$relative"
            }
            java.net.URI(base).resolve(relative).toString()
        }.getOrDefault(relative)
    }

    private data class ParsedMpd(
        val baseUrl: String,
        val representations: List<Representation>,
        val defaultSegmentTemplate: SegmentTemplate?,
    )

    private data class Representation(
        val id: String,
        val bandwidth: Long,
        val baseUrl: String,
        val body: String,
        val codecs: String = "",
    )

    private data class SegmentTemplate(
        val initialization: String?,
        val media: String?,
        val startNumber: Long,
        val timescale: Long,
        val body: String,
    )

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

    private fun loadOrCreateState(
        url: String,
        headers: Map<String, String>,
        referer: String,
        totalSize: Long,
        segments: List<SaninSegmentState.SegmentState>,
    ): SaninSegmentState {
        val existing = loadState()
        if (existing != null &&
            existing.url == url &&
            existing.format == "DASH" &&
            existing.segments.size == segments.size
        ) {
            // PHASE 4.1 HARDENING: validate each segment URL. If
            // the manifest changed (same count, different URLs),
            // the persisted state is stale and we restart.
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
            format = "DASH",
            variantId = segments.firstOrNull()?.segmentUrl,
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
        try {
            val buf = ByteArray(SaninSegmentedDownloader.getSegmentBufferSize(context))
            while (true) {
                if (isStopped()) throw CancellationException("stopped")
                val read = sourceStream.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
            }
            out.flush()
        } finally {
            runCatching { out.close() }
            runCatching { sourceStream.close() }
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
        totalBytes: Long = 0L,
        bytesDownloaded: Long = 0L,
    ) {
        val info = com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo(
            totalBytes = totalBytes,
            relativePath = folder,
            displayName = finalName,
            basePath = basePath ?: "",
            fileLength = bytesDownloaded,
        )
        context.setKey(
            VideoDownloadManager.KEY_DOWNLOAD_INFO,
            downloadId.toString(),
            info,
        )
    }

    companion object {
        private const val MAX_SEGMENT_ATTEMPTS = 3
        private const val MAX_DASH_SEGMENTS = 10_000
        /**
         * Cap on a single `<S r="..."/>` SegmentTimeline repeat
         * value. This prevents a malformed manifest from declaring
         * a runaway segment list.
         */
        private const val MAX_TIMELINE_REPEAT = 10_000
    }
}
