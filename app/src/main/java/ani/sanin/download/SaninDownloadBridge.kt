package ani.sanin.download

import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoType
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge that connects an already-resolved [Video] from the Sanin anime
 * pipeline to the existing CloudStream download infrastructure.
 *
 * This is a translation / integration layer only. It does not perform the
 * actual download and does not maintain a second queue.
 *
 * Phase 1 rules:
 *  - Only [VideoType.CONTAINER] is queued. [VideoType.M3U8] and
 *    [VideoType.DASH] are rejected cleanly.
 *  - The resolved URL and headers from [Video.file] are preserved exactly.
 *  - The existing [DownloadQueueManager] handles deduplication through
 *    a deterministic [Int] id derived from stable identity components.
 */
object SaninDownloadBridge {

    /**
     * Outcome of a bridge call. Keeps the integration clean and lets the
     * UI surface a sensible message without the bridge having to know
     * anything about toasts / snackbars.
     */
    sealed class Result {
        data class Enqueued(val id: Int) : Result()
        data class Rejected(val reason: Reason) : Result()

        enum class Reason {
            UNSUPPORTED_FORMAT_M3U8,
            UNSUPPORTED_FORMAT_DASH,
            INVALID_URL,
            ENQUEUE_FAILED,
        }
    }

    /**
     * Build a stable [Int] id for the (media, episode, source, quality) tuple.
     *
     * The id is derived purely from stable identity components so:
     *  - The same logical download always resolves to the same id.
     *  - Different episodes / qualities / sources resolve to different ids.
     *  - It survives service and app recreation.
     *  - It is not random and does not depend on timestamps.
     *  - It is not derived from the resolved URL.
     *
     * The CloudStream queue is shared with the existing result-page
     * downloads, so the id space is offset by [ID_NAMESPACE] to resist
     * collisions with the rest of the queue.
     */
    fun buildDownloadId(
        mediaId: Int,
        episodeNumber: Int,
        sourceKey: String,
        quality: Int?,
    ): Int {
        val components = buildString {
            append("sanin|")
            append("m=").append(mediaId).append('|')
            append("e=").append(episodeNumber).append('|')
            append("s=").append(sourceKey).append('|')
            append("q=").append(quality ?: Qualities.Unknown)
        }
        // Combine namespace with the 32-bit FNV-1a hash of the components.
        val hash = fnv1a(components)
        val mixed = (ID_NAMESPACE xor hash) and 0x7fffffff
        return mixed
    }

    /**
     * In-memory registry of re-resolver callbacks, keyed by the
     * deterministic Sanin download id. The re-resolver cannot be
     * persisted in JSON, so the caller is expected to provide one
     * each time it enqueues a download. If the app is killed, the
     * re-resolver is lost — on resume the supervisor will simply
     * report expired URL failures without re-resolving.
     */
    private val reResolvers = ConcurrentHashMap<Int, SaninSegmentedDownloader.UrlReResolver>()

    /**
     * Enqueue a [Video] that has already been resolved by the Sanin
     * pipeline. This call does not re-resolve the URL and does not
     * perform any network request.
     *
     * @param video The resolved video. The bridge only accepts
     *              [VideoType.CONTAINER]; M3U8 and DASH are rejected.
     * @param mediaId Stable anime/media id (e.g. AniList id, or the
     *                absolute source id for synthetic TMDB media).
     * @param episodeNumber Stable episode number within the media.
     * @param sourceKey Stable source/extension identity (server name or
     *                  extension save name).
     * @param apiName Source API name (the [apiName] stored on the
     *                existing queue item).
     * @param titleName Media title used for the download metadata.
     * @param currentPoster Poster URL for the media.
     * @param tvType Mapped [TvType] for the media.
     * @param reResolver Optional callback the supervisor may invoke
     *                   when the server reports the URL is expired.
     *                   The same [reResolver] must produce a new
     *                   [Video] for the same episode/source; the
     *                   bridge does not change identity on re-resolve.
     */
    fun enqueue(
        video: Video,
        mediaId: Int,
        episodeNumber: Int,
        sourceKey: String,
        apiName: String,
        titleName: String,
        currentPoster: String?,
        tvType: TvType,
        reResolver: SaninSegmentedDownloader.UrlReResolver? = null,
    ): Result {
        var enqueuedId: Int? = null
        return try {
            Logger.log(
                "SANIN_BRIDGE: enqueue mediaId=$mediaId ep=$episodeNumber src=$sourceKey " +
                    "api=$apiName fmt=${video.format} quality=${video.quality} " +
                    "url=${video.file.url.take(160)}"
            )
            // Map the Sanin VideoType to the corresponding ExtractorLinkType
            // so the supervisor can route to the correct format-specific
            // downloader. M3U8 and DASH are now supported in Phase 4.
            val linkType: ExtractorLinkType = when (video.format) {
                VideoType.M3U8 -> ExtractorLinkType.M3U8
                VideoType.DASH -> ExtractorLinkType.DASH
                VideoType.CONTAINER -> ExtractorLinkType.VIDEO
            }

            val url = video.file.url
            if (url.isBlank()) {
                Logger.log("SANIN_BRIDGE: rejected INVALID_URL mediaId=$mediaId ep=$episodeNumber")
                return Result.Rejected(Result.Reason.INVALID_URL)
            }

            val headers = video.file.headers
            val referer = headers.entries.firstOrNull {
                it.key.equals("referer", ignoreCase = true)
            }?.value.orEmpty()
            val qualityValue = video.quality ?: Qualities.Unknown

            @Suppress("DEPRECATION_ERROR")
            val link = ExtractorLink(
                source = apiName,
                name = titleName,
                url = url,
                referer = referer,
                quality = qualityValue,
                type = linkType,
                headers = headers,
            )

            val id = buildDownloadId(mediaId, episodeNumber, sourceKey, video.quality)
            enqueuedId = id
            val episode = buildResultEpisode(
                id = id,
                episodeNumber = episodeNumber,
                mediaId = mediaId,
                poster = currentPoster,
                apiName = apiName,
                tvType = tvType,
            )

            // Reuse the existing CloudStream helper so filename sanitization,
            // poster fallback, and the isMovie branch mirror the rest of the
            // queue exactly. Named arguments are deliberate to avoid
            // accidentally swapping title / api / poster.
            VideoDownloadManager.getDownloadEpisodeMetadata(
                episode = episode,
                titleName = titleName,
                apiName = apiName,
                currentPoster = currentPoster,
                currentIsMovie = tvType.isMovieType(),
                tvType = tvType,
            )

            val downloadItem = DownloadObjects.DownloadQueueItem(
                episode = episode,
                isMovie = tvType.isMovieType(),
                resultName = titleName,
                resultType = tvType,
                resultPoster = currentPoster,
                apiName = apiName,
                resultId = mediaId,
                resultUrl = "",
                links = listOf(link),
                subs = null,
            )

            // Mark this id as a Sanin download so the queue service
            // routes it through the Phase 2 supervisor. Marker is
            // cleared by the supervisor on success / fallback.
            SaninDownloadMarker.add(id)
            SaninDownloadSupervisor.installResumeListener()
            if (reResolver != null) reResolvers[id] = reResolver
            // Opportunistic marker hygiene. Cheap; iterates a small
            // list. Drops stale marker entries whose queue item and
            // segment state have both been removed (e.g. user
            // deleted the download).
            runCatching {
                SaninDownloadSupervisor.reconcileMarker(
                    com.lagradost.cloudstream3.CloudStreamApp.context
                        ?: return@runCatching
                )
            }
            DownloadQueueManager.addToQueue(downloadItem.toWrapper())
            Logger.log("SANIN_BRIDGE: enqueued id=$id linkType=$linkType")
            Result.Enqueued(id)
        } catch (t: Throwable) {
            Logger.log(
                "SANIN_BRIDGE: enqueue FAILED mediaId=$mediaId ep=$episodeNumber " +
                    "src=$sourceKey: ${t.message}"
            )
            enqueuedId?.let { enqueued ->
                runCatching { SaninDownloadMarker.remove(enqueued) }
                reResolvers.remove(enqueued)
            }
            Result.Rejected(Result.Reason.ENQUEUE_FAILED)
        }
    }

    /**
     * Look up the re-resolver for an in-flight Sanin download id.
     * Returns null if the bridge has no record of one (e.g. after
     * process death — the in-memory registry is cleared).
     */
    internal fun getReResolver(downloadId: Int): SaninSegmentedDownloader.UrlReResolver? =
        reResolvers[downloadId]

    /**
     * Drop the in-memory re-resolver for a download id. Called by
     * the supervisor once re-resolution has succeeded or is no longer
     * useful.
     */
    internal fun clearReResolver(downloadId: Int) {
        reResolvers.remove(downloadId)
    }

    private object Qualities {
        const val Unknown = 400
    }

    /**
     * Construct a minimal [ResultEpisode] for the queue. The queue only
     * needs [ResultEpisode.id] and [ResultEpisode.parentId] for id
     * propagation, plus the fields surfaced to the user.
     */
    private fun buildResultEpisode(
        id: Int,
        episodeNumber: Int,
        mediaId: Int,
        poster: String?,
        apiName: String,
        tvType: TvType,
    ): ResultEpisode = ResultEpisode(
        headerName = "",
        name = null,
        poster = poster,
        episode = episodeNumber,
        seasonIndex = null,
        season = null,
        data = "",
        apiName = apiName,
        id = id,
        index = episodeNumber,
        position = 0L,
        duration = 0L,
        score = null,
        description = null,
        isFiller = null,
        tvType = tvType,
        parentId = mediaId,
        videoWatchState = VideoWatchState.None,
        totalEpisodeIndex = null,
        airDate = null,
        runTime = null,
        seasonData = null,
    )

    private const val ID_NAMESPACE: Int = 0x534e_4e01 // 'S','N','N',1

    private fun fnv1a(input: String): Int {
        var hash = -0x61c8864680b583ebL // -2128831035L, FNV offset basis
        val prime = 0x100000001b3L // 1099511628211, FNV prime
        for (c in input) {
            hash = hash xor (c.code.toLong() and 0xffL)
            hash *= prime
        }
        return (hash and 0xffffffffL).toInt()
    }
}
