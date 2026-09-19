package ani.sanin.download

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the lifecycle of a Sanin segmented download: deciding whether
 * segmented mode is safe, running the segments, persisting resume
 * state, signaling fallback when it isn't, and orchestrating URL
 * re-resolution when the server reports the link is expired.
 */
object SaninDownloadSupervisor {

    /**
     * The set of [com.lagradost.cloudstream3.utils.ExtractorLinkType]
     * values the Sanin pipeline can handle. VIDEO → segmented
     * downloader; M3U8 → HLS downloader; DASH → DASH downloader.
     * Any other type is treated as a fallback to the normal
     * CloudStream download path.
     *
     * Exposed publicly so the CloudStream [DownloadManager] can
     * check whether to route a link through Sanin.
     */
    val SUPPORTED_LINK_TYPES_PUBLIC: Set<com.lagradost.cloudstream3.utils.ExtractorLinkType> =
        setOf(
            com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH,
        )

    sealed class Outcome {
        data class Success(val finalSize: Long) : Outcome()
        /**
         * The download could not continue. If [hasResumeState] is
         * true the persisted state is preserved and a future resume
         * will pick up where this left off. [expiredUrl] indicates
         * the failure was a 401/403/410, in which case the
         * integration layer may choose to re-resolve.
         */
        data class Failure(
            val reason: String,
            val hasResumeState: Boolean,
            val expiredUrl: Boolean = false,
        ) : Outcome()
        object Fallback : Outcome()
    }

    suspend fun run(
        context: Context,
        item: DownloadObjects.DownloadItem,
        reResolver: SaninSegmentedDownloader.UrlReResolver? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        val link = item.links.firstOrNull()
            ?: return@withContext Outcome.Failure(
                "no link",
                hasResumeState = false,
            )

        // Accept VIDEO, M3U8, and DASH link types. The format-specific
        // downloader is selected below based on the link type and URL.
        if (link.type !in SUPPORTED_LINK_TYPES_PUBLIC) {
            return@withContext Outcome.Fallback
        }
        if (!link.url.startsWith("http://") && !link.url.startsWith("https://")) {
            return@withContext Outcome.Fallback
        }

        val downloadId = item.ep.id
        SaninDownloadMarker.add(downloadId)

        val parallel = SaninSegmentedDownloader.getDefaultPerFileConnections(context)
        val folder = item.folder ?: ""
        val name = link.name.ifBlank { "download" }

        // Wi-Fi only: refuse to start the download when the user
        // is on cellular. We classify this as a hard failure with
        // hasResumeState=false so the queue manager keeps the
        // item pending and the user can retry manually later.
        if (SaninDownloadAutoManager.isWifiOnly() &&
            !SaninDownloadAutoManager.isOnWifi(context)
        ) {
            SaninDownloadMarker.remove(downloadId)
            return@withContext Outcome.Failure(
                "Wi-Fi only: download paused while on cellular",
                hasResumeState = false,
            )
        }

        // Detect the media format and dispatch to the right
        // downloader. The existing DIRECT / segmented downloader
        // is preserved unchanged for VIDEO-type links.
        val format = detectFormat(link)

        val result: FormatResult = try {
            when (format) {
                SaninMediaFormat.HLS -> runHls(
                    context = context,
                    downloadId = downloadId,
                    link = link,
                    name = name,
                    folder = folder,
                    parallel = parallel,
                )
                SaninMediaFormat.DASH -> runDash(
                    context = context,
                    downloadId = downloadId,
                    link = link,
                    name = name,
                    folder = folder,
                    parallel = parallel,
                )
                else -> runDirect(
                    context = context,
                    downloadId = downloadId,
                    link = link,
                    name = name,
                    folder = folder,
                    parallel = parallel,
                    reResolver = reResolver,
                )
            }
        } catch (e: CancellationException) {
            // Cancellation is not a failure. Let it propagate so the
            // existing per-instance cleanup runs unchanged. State is
            // preserved on disk for the next attempt.
            throw e
        } catch (e: Throwable) {
            logError(e)
            FormatResult.Failure("supervisor failure: ${e.message}")
        }

        when (result) {
            is FormatResult.Success -> {
                SaninDownloadMarker.remove(downloadId)
                Outcome.Success(result.finalSize)
            }
            is FormatResult.Fallback -> {
                SaninDownloadMarker.remove(downloadId)
                Outcome.Fallback
            }
            is FormatResult.Unsupported -> {
                SaninDownloadMarker.remove(downloadId)
                Outcome.Failure(
                    reason = result.reason,
                    hasResumeState = false,
                )
            }
            is FormatResult.Failure -> {
                val hasState = hasResumeState(context, downloadId)
                if (!hasState) SaninDownloadMarker.remove(downloadId)
                Outcome.Failure(
                    reason = result.reason,
                    hasResumeState = hasState,
                    expiredUrl = result.expiredUrl,
                )
            }
        }
    }

    /**
     * Detect the media format for a link. Uses the link type first
     * (from the resolver), then the URL extension as a fallback.
     */
    private fun detectFormat(
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
    ): SaninMediaFormat {
        return when (link.type) {
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 -> SaninMediaFormat.HLS
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH -> SaninMediaFormat.DASH
            else -> SaninMediaFormat.fromUrl(link.url)
        }
    }

    private suspend fun runDirect(
        context: Context,
        downloadId: Int,
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        name: String,
        folder: String,
        parallel: Int,
        reResolver: SaninSegmentedDownloader.UrlReResolver?,
    ): FormatResult {
        val result = SaninSegmentedDownloader(context, downloadId).run(
            initialUrl = link.url,
            initialHeaders = link.headers,
            initialReferer = link.referer,
            displayName = name,
            folder = folder,
            extension = "mp4",
            parallelConnections = parallel,
            reResolver = reResolver,
        )
        return when (result) {
            is SaninSegmentedDownloader.Outcome.Success -> FormatResult.Success(result.finalSize)
            is SaninSegmentedDownloader.Outcome.Fallback -> FormatResult.Fallback
            is SaninSegmentedDownloader.Outcome.Failure -> FormatResult.Failure(
                result.reason,
                expiredUrl = result.expiredUrl,
            )
        }
    }

    private suspend fun runHls(
        context: Context,
        downloadId: Int,
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        name: String,
        folder: String,
        parallel: Int,
    ): FormatResult {
        val result = SaninHlsDownloader(context, downloadId).run(
            initialUrl = link.url,
            initialHeaders = link.headers,
            initialReferer = link.referer,
            displayName = name,
            folder = folder,
            parallelConnections = parallel,
        )
        return when (result) {
            is SaninHlsDownloader.Outcome.Success -> FormatResult.Success(result.finalSize)
            SaninHlsDownloader.Outcome.Unsupported -> FormatResult.Unsupported("HLS not supported (live, DRM, or malformed)")
            is SaninHlsDownloader.Outcome.Failure -> FormatResult.Failure(
                result.reason,
                expiredUrl = result.expiredUrl,
            )
        }
    }

    private suspend fun runDash(
        context: Context,
        downloadId: Int,
        link: com.lagradost.cloudstream3.utils.ExtractorLink,
        name: String,
        folder: String,
        parallel: Int,
    ): FormatResult {
        val result = SaninDashDownloader(context, downloadId).run(
            initialUrl = link.url,
            initialHeaders = link.headers,
            initialReferer = link.referer,
            displayName = name,
            folder = folder,
            parallelConnections = parallel,
        )
        return when (result) {
            is SaninDashDownloader.Outcome.Success -> FormatResult.Success(result.finalSize)
            SaninDashDownloader.Outcome.Unsupported -> FormatResult.Unsupported("DASH not supported (live, DRM, or malformed)")
            is SaninDashDownloader.Outcome.Failure -> FormatResult.Failure(
                result.reason,
                expiredUrl = result.expiredUrl,
            )
        }
    }

    /**
     * Internal result type for the three format-specific downloaders,
     * normalised to a single shape before mapping to the public
     * [Outcome] sealed class.
     */
    private sealed class FormatResult {
        data class Success(val finalSize: Long) : FormatResult()
        data class Failure(val reason: String, val expiredUrl: Boolean = false) : FormatResult()
        data class Unsupported(val reason: String) : FormatResult()
        object Fallback : FormatResult()
    }

    /**
     * Clear all Sanin-specific state for a download id. Idempotent.
     * Call this when the user deletes a download so that a future
     * new download with the same id is not misrouted.
     */
    fun clear(context: Context, downloadId: Int) {
        SaninDownloadMarker.remove(downloadId)
        context.removeKey("sanin_segment_state", downloadId.toString())
    }

    /** True if the download id is owned by the Sanin pipeline. */
    fun isSaninDownload(id: Int): Boolean = SaninDownloadMarker.isMarked(id)

    /** True if there is persisted segment state for a Sanin download. */
    fun hasResumeState(context: Context, id: Int): Boolean =
        context.getKey<SaninSegmentState>(
            "sanin_segment_state",
            id.toString(),
        ) != null

    /**
     * Install a global Resume listener that, when the user clicks
     * Resume on a paused Sanin download, re-queues the item so the
     * service picks it up and the supervisor resumes from the
     * persisted segment state. The listener is idempotent: it is
     * safe to call [installResumeListener] multiple times.
     */
    @Volatile
    private var listenerInstalled = false

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun installResumeListener() {
        if (listenerInstalled) return
        listenerInstalled = true
        VideoDownloadManager.downloadEvent += { (id, action) ->
            if (action == VideoDownloadManager.DownloadActionType.Resume &&
                SaninDownloadMarker.isMarked(id)
            ) {
                listenerScope.launch {
                    runCatching {
                        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context
                            ?: return@runCatching
                        // Skip re-queue if the download is already
                        // running. The in-flight supervisor's
                        // `waitIfPaused` will return when the status
                        // flips to IsDownloading; an extra re-queue
                        // would just sit in the queue and never be
                        // popped.
                        val currentStatus = VideoDownloadManager.downloadStatus[id]
                        if (currentStatus == VideoDownloadManager.DownloadType.IsDownloading) {
                            return@runCatching
                        }
                        val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                        val queueWrapper = pkg?.toWrapper()
                            ?: VideoDownloadManager.getDownloadQueuePackage(ctx, id)
                        if (queueWrapper != null) {
                            com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
                                .addToQueue(queueWrapper)
                        }
                    }.onFailure { logError(it) }
                }
            }
        }
    }

    /**
     * Reconcile the persistent `sanin_active` set against the queue
     * state. Any Sanin-marked id that no longer has a queue entry
     * and no persisted segment state is removed from the set. This
     * prevents the marker from accumulating stale entries after the
     * user deletes a download.
     *
     * Idempotent and safe to call from anywhere (e.g. on app start).
     */
    fun reconcileMarker(context: Context) {
        val marked = SaninDownloadMarker.all()
        if (marked.isEmpty()) return
        for (id in marked) {
            val inQueue = VideoDownloadManager.getDownloadResumePackage(context, id) != null ||
                VideoDownloadManager.getDownloadQueuePackage(context, id) != null
            val hasState = hasResumeState(context, id)
            if (!inQueue && !hasState) {
                SaninDownloadMarker.remove(id)
            }
        }
    }
}
