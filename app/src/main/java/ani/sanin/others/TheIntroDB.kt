package ani.sanin.others

import ani.sanin.Mapper
import ani.sanin.client
import ani.sanin.tryWithSuspend
import ani.sanin.util.Logger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

// api.theintrodb.org v4 — resolves by tmdb/imdb/tvdb id directly, so it also
// covers TMDB-mode content that has no MAL id. Reads are anonymous; a Bearer
// key is only required for submissions.
object TheIntroDB {

    suspend fun getResult(
        imdbId: String? = null,
        tmdbId: Int? = null,
        season: Int?,
        episode: Int?,
        isMovie: Boolean = false,
        durationSec: Long = 0
    ): List<AniSkip.Stamp>? {
        val idParam = when {
            tmdbId != null && tmdbId > 0 -> "tmdb_id=$tmdbId"
            !imdbId.isNullOrBlank() -> "imdb_id=$imdbId"
            else -> {
                Logger.log("TheIntroDB: no usable id, skipping")
                return null
            }
        }
        var url = "https://api.theintrodb.org/v4/media?$idParam"
        if (!isMovie && season != null && episode != null) {
            url += "&season=$season&episode=$episode"
        }
        Logger.log("TheIntroDB: getResult start url=$url durationSec=$durationSec")
        return tryWithSuspend {
            val started = java.lang.System.currentTimeMillis()
            val a = withTimeoutOrNull(10_000L) { client.get(url) }
            val elapsed = java.lang.System.currentTimeMillis() - started
            if (a == null) {
                Logger.log("TheIntroDB: TIMEOUT after ${elapsed}ms for $url")
                return@tryWithSuspend null
            }
            Logger.log("TheIntroDB: response code=${a.code} in ${elapsed}ms for $url")
            if (a.code != 200 || !a.text.trim().startsWith("{")) {
                Logger.log("TheIntroDB: non-200 or unexpected body, skipping")
                return@tryWithSuspend null
            }
            val res = try {
                Mapper.json.decodeFromString<Response>(a.text)
            } catch (e: Exception) {
                Logger.log("TheIntroDB: parse error: ${e.message}")
                return@tryWithSuspend null
            }
            val stamps = buildList {
                res.intro?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { add(it.toStamp("op", "theintrodb-intro-$i")) } }
                res.recap?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { add(it.toStamp("recap", "theintrodb-recap-$i")) } }
                res.credits?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { add(it.toStamp("ed", "theintrodb-credits-$i")) } }
                (res.preview ?: res.previews)?.forEachIndexed { i, seg -> valid(seg, durationSec)?.let { add(it.toStamp("other", "theintrodb-preview-$i")) } }
            }
            Logger.log(
                "TheIntroDB: FOUND ${stamps.size} stamps for $url " +
                    "intro=${res.intro?.size ?: "null"} recap=${res.recap?.size ?: "null"} " +
                    "credits=${res.credits?.size ?: "null"}"
            )
            if (stamps.isEmpty()) null else stamps
        }
    }

    private fun valid(seg: Segment, durationSec: Long = 0): Segment? {
        val ok = seg.startMs >= 0 && seg.endMs > seg.startMs &&
            (durationSec <= 0 || seg.endMs <= durationSec * 1000 + 5_000)
        if (!ok) {
            Logger.log(
                "TheIntroDB: dropped implausible segment " +
                    "${seg.startMs}-${seg.endMs}ms (durationSec=$durationSec)"
            )
        }
        return if (ok) seg else null
    }

    private fun Segment.toStamp(skipType: String, skipId: String) = AniSkip.Stamp(
        interval = AniSkip.AniSkipInterval(startTime = startMs / 1000.0, endTime = endMs / 1000.0),
        skipType = skipType,
        skipId = skipId,
        // episodeLength represents total episode duration; use 0.0 as a sentinel since
        // TheIntroDB segments don't carry the full episode length
        episodeLength = 0.0
    )

    @Serializable
    data class Segment(val start_ms: Long, val end_ms: Long)

    @Serializable
    data class Response(
        val tvdb_id: Long? = null,
        val tmdb_id: Long? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val intro: List<Segment>? = null,
        val recap: List<Segment>? = null,
        val credits: List<Segment>? = null,
        val preview: List<Segment>? = null,
        val previews: List<Segment>? = null
    )
}
