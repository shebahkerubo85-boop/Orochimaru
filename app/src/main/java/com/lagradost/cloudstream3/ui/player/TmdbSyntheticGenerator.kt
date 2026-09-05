package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.util.Log
import ani.sanin.cloudstream.TmdbStreamResolver
import ani.sanin.media.Media
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newAudioFile
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import eu.kanade.tachiyomi.animesource.model.Track

/**
 * CS3-compatible generator for TMDB movie-mode content.
 *
 * The anime-side [Media] holds the episode shells (with per-episode extractors
 * resolved lazily through [TmdbStreamResolver.populateSyntheticEpisode]),
 * while the episode rail and next/prev logic in [VideoGenerator] work off the
 * [ResultEpisode] rows — the same shape `RepoLinkGenerator` uses for the
 * cloudstream result page. Each `offset` resolves that episode's plugin links.
 */
class TmdbSyntheticGenerator(
    context: Context,
    private val media: Media,
    rows: List<ResultEpisode>,
    private val episodeKeys: List<String>,
) : VideoGenerator<ResultEpisode>(rows) {

    private val appContext: Context = context.applicationContext

    override val hasCache = false
    override val canSkipLoading = false

    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val key = episodeKeys.getOrNull(offset) ?: return false
        val episode = media.anime.episodes?.get(key) ?: return false
        if (!TmdbStreamResolver.populateSyntheticEpisode(appContext, media, episode)) return false

        var emitted = 0
        episode.extractors.orEmpty().forEach { extractor ->
            val serverName = extractor.server.name.ifBlank { "Server ${emitted + 1}" }
            extractor.videos.forEach { video ->
                val link = videoToLink(serverName, video, extractor.audioTracks)
                if (link != null && sourceTypes.contains(link.type)) {
                    callback(link to null)
                    emitted++
                }
            }
        }
        Log.i("TmdbSynthetic", "generated $emitted links for '${episode.number}' at offset=$offset")
        return emitted > 0
    }

    private suspend fun videoToLink(
        serverName: String,
        video: Video,
        audioTracks: List<Track>,
    ): ExtractorLink? {
        val url = video.file.url
        if (url.isBlank()) return null
        val hdrs = HashMap(video.file.headers)
        val ref = hdrs["Referer"] ?: ""
        val type = when (video.format) {
            VideoType.M3U8 -> ExtractorLinkType.M3U8
            VideoType.DASH -> ExtractorLinkType.DASH
            VideoType.CONTAINER -> when {
                url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
        }
        val audio = audioTracks.map { newAudioFile(it.url) }
        val drm = video.drm
        return if (drm?.uuid != null) {
            @Suppress("DEPRECATION_ERROR")
            newDrmExtractorLink(
                source = serverName,
                name = serverName,
                url = url,
                type = type,
                uuid = drm.uuid,
            ) {
                referer = ref
                headers = hdrs
                quality = video.quality ?: 0
                audioTracks = audio
                licenseUrl = drm.licenseUrl
                keyRequestParameters = drm.keyRequestParameters
                kid = drm.kid
                key = drm.key
                kty = drm.kty
            }
        } else {
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = url,
                type = type,
            ) {
                referer = ref
                headers = hdrs
                quality = video.quality ?: 0
                audioTracks = audio
            }
        }
    }
}
