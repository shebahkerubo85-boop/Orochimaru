package ani.sanin.connections.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The list endpoints return a *wrapper* per row: the list itself is nested under `list`, and
 * there are two like counts at different levels, `like_count` on the wrapper and `likes` on
 * the list. Reading either of those at the wrong depth silently yields null, so the wrapper is
 * modelled explicitly rather than flattened.
 */
@Serializable
data class TraktListWrapper(
    @SerialName("like_count") val likeCount: Int? = null,
    @SerialName("list") val list: TraktList? = null,
)

@Serializable
data class TraktList(
    val name: String? = null,
    val description: String? = null,
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("sort_by") val sortBy: String? = null,
    @SerialName("sort_how") val sortHow: String? = null,
    val likes: Int? = null,
    val ids: TraktListIds? = null,
    val user: TraktUser? = null,
)

@Serializable
data class TraktListIds(
    val slug: String? = null,
    val trakt: Long? = null,
)

/**
 * One element of a Trakt list index, i.e. of `/lists/popular` or `/lists/trending`.
 *
 * The API does not return the list itself but a wrapper holding it alongside a copy of its
 * owner. The owner appears at both levels and is not reliably populated on the inner list, so
 * the wrapper's copy is the one worth reading.
 */
@Serializable
data class TraktListWrapper(
    val list: TraktList? = null,
    val user: TraktUser? = null,
)

@Serializable
data class TraktUser(
    val username: String? = null,
    val ids: TraktUserIds? = null,
)

@Serializable
data class TraktUserIds(
    val slug: String? = null,
)

@Serializable
data class TraktListItem(
    val rank: Int? = null,
    val type: String? = null,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
)

/**
 * A movie or show, minus the fields this client never reads.
 *
 * Only `ids`, title, year and images are kept. `extended=full` returns a much larger object
 * than the row needs, and decoding the whole thing would be wasted work on every item of
 * every list.
 */
@Serializable
data class TraktMovie(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: TraktImages? = null,
    /** Free text synopsis, shown as the teaser on the franchise screen. */
    val overview: String? = null,
    /** ISO release date, e.g. "1994-09-23". More precise than [year] where present. */
    val released: String? = null,
    /** Runtime in minutes. */
    val runtime: Int? = null,
    /**
     * Community rating on a 0-10 scale with a long fraction.
     *
     * Kept as the raw double and converted at the edge: the screen shows a 0-100 integer like
     * AniList's, and rounding here would lose the distinction between a 9.1 and a 9.2.
     */
    val rating: Double? = null,
    /**
     * "movie" or "show", as Trakt reports it.
     *
     * Kept on the media object so the pill can still tell the two apart if the list wrapper
     * omitted its own type, which it does on some endpoints.
     */
    val type: String? = null,
)

/** Same shape as [TraktMovie]; kept separate so a list's movie and show entries stay typed. */
@Serializable
data class TraktShow(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIds? = null,
    val images: TraktImages? = null,
    val overview: String? = null,
    /** ISO date of the first episode. */
    @SerialName("first_aired") val firstAired: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    val type: String? = null,
)

@Serializable
data class TraktIds(
    @SerialName("slug") val slug: String? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
    @SerialName("trakt") val trakt: Int? = null,
)

/**
 * A title's artwork, grouped by kind.
 *
 * Each group is a *list of URLs with the size baked into the path*, e.g.
 * `images: { poster: ["media.trakt.tv/images/movies/.../posters/medium/a.jpg.webp"] }` — not a
 * nested `{ full, medium, thumb }` object. That is the shape a *list's* user images use, and
 * mistaking one for the other decodes to a set of nulls rather than an error, so it is spelled
 * out here rather than left to look obvious.
 */
@Serializable
data class TraktImages(
    @SerialName("poster") val poster: List<String> = emptyList(),
    @SerialName("screenshot") val screenshot: List<String> = emptyList(),
    @SerialName("banner") val banner: List<String> = emptyList(),
    @SerialName("logo") val logo: List<String> = emptyList(),
    @SerialName("clearart") val clearart: List<String> = emptyList(),
) {
    /**
     * The first usable URL from one of this object's groups, as an absolute URL.
     *
     * Every group is a *list*, and the size is baked into the path rather than being a
     * sibling key: a response carries `posters/medium/foo.jpg.webp`, not a `medium` object.
     * Decoding this as a nested `full`/`medium`/`thumb` object yields nothing at all rather
     * than an error, which is why the list shape is written out explicitly here.
     *
     * @param prefer the substrings to look for, most wanted first. A response usually holds
     *   exactly one size, but a `medium` is preferred over a `thumb` when both appear, since
     *   a thumb is too small for a card.
     * @return an absolute URL, or null when the group is empty.
     */
    fun urlOf(group: List<String>, vararg prefer: String): String? {
        if (group.isEmpty()) return null
        val pick = prefer.firstNotNullOfOrNull { tag ->
            group.firstOrNull { it.contains("/$tag/") }
        } ?: group.first()
        // Relative as served, absolute as needed: the paths come back without a scheme or host.
        return if (pick.startsWith("http")) pick else "$IMAGE_BASE$pick"
    }
}

private const val IMAGE_BASE = "https://media.trakt.tv/"

internal val traktJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
