package ani.sanin.media.model

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnore
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromLanguageToTagIETF
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data-only models shared between the Exo player and the rest of the app.
 * These used to live inside the CS3 player tree; they are plain carriers with
 * no player behaviour, so they live here now that CS3 is gone.
 */
data class ExtractorUri(
    val uri: Uri,
    val name: String,

    val basePath: String? = null,
    val relativePath: String? = null,
    val displayName: String? = null,

    val id: Int? = null,
    val parentId: Int? = null,
    val episode: Int? = null,
    val season: Int? = null,
    val headerName: String? = null,
    val tvType: TvType? = null,
)

enum class SubtitleOrigin {
    URL,
    DOWNLOADED_FILE,
    EMBEDDED_IN_VIDEO
}

/**
 * @param originalName the start of the name to be displayed in the player
 * @param nameSuffix An extra suffix added to the subtitle to make sure it is unique
 * @param url Url for the subtitle, when EMBEDDED_IN_VIDEO this variable is used as the real backend id
 * @param headers if empty it will use the base onlineDataSource headers else only the specified headers
 * @param languageCode usually, tags such as "en", "es-mx", or "zh-hant-TW". But it could be something like "English 4"
 */
@Serializable
data class SubtitleData(
    @SerialName("originalName") val originalName: String,
    @SerialName("nameSuffix") val nameSuffix: String,
    @SerialName("url") val url: String,
    @SerialName("origin") val origin: SubtitleOrigin,
    @SerialName("mimeType") val mimeType: String,
    @SerialName("headers") val headers: Map<String, String>,
    @SerialName("languageCode") val languageCode: String?,
    @SerialName("source") val source: String? = null,
) {
    /** Internal ID for media3, unique for each link. */
    @JsonIgnore
    fun getId(): String {
        return if (origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) url
        else "$url|$name"
    }

    /** Returns true if langCode is the same as the IETF tag */
    fun matchesLanguageCode(langCode: String): Boolean {
        return getIETF_tag() == langCode
    }

    /** Tries hard to figure out a valid IETF tag based on language code and name. Will return null if not found. */
    @JsonIgnore
    fun getIETF_tag(): String? {
        return fromLanguageToTagIETF(this.languageCode)
            ?: fromLanguageToTagIETF(this.originalName, halfMatch = true)
    }

    @SerialName("name") val name = "$originalName $nameSuffix"

    /**
     * Gets the URL, but tries to fix it if it is malformed.
     */
    @JsonIgnore
    fun getFixedUrl(): String {
        // Some extensions fail to include the protocol, this helps with that.
        val fixedSubUrl = if (this.url.startsWith("//")) {
            "https:${this.url}"
        } else this.url
        return fixedSubUrl
    }
}

/**
 * Future proofed way to mark episodes as watched
 **/
enum class VideoWatchState {
    /** Default value when no key is set */
    None,
    Watched
}

@Serializable
data class ResultEpisode(
    @SerialName("headerName") val headerName: String,
    @SerialName("name") val name: String?,
    @SerialName("poster") val poster: String?,
    @SerialName("episode") val episode: Int,
    @SerialName("seasonIndex") val seasonIndex: Int?, // this is the "season" index used season names
    @SerialName("season") val season: Int?, // this is the display
    @SerialName("data") val data: String,
    @SerialName("apiName") val apiName: String,
    @SerialName("id") val id: Int,
    @SerialName("index") val index: Int,
    @SerialName("position") val position: Long, // time in MS
    @SerialName("duration") val duration: Long, // duration in MS
    @SerialName("score") val score: Score?,
    @SerialName("description") val description: String?,
    @SerialName("isFiller") val isFiller: Boolean?,
    @SerialName("tvType") val tvType: TvType,
    @SerialName("parentId") val parentId: Int,
    /** Conveys if the episode itself is marked as watched. */
    @SerialName("videoWatchState") val videoWatchState: VideoWatchState,
    /** Sum of all previous season episode counts + episode. */
    @SerialName("totalEpisodeIndex") val totalEpisodeIndex: Int? = null,
    @SerialName("airDate") val airDate: Long? = null,
    @SerialName("runTime") val runTime: Int? = null,
    @SerialName("seasonData") val seasonData: SeasonData? = null,
)
