package ani.sanin.connections.tmdb

import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Serializable
data class TmdbMedia(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("media_type") val mediaType: String? = null
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val type: String get() = mediaType ?: if (title != null) "movie" else "tv"
}

@Serializable
data class TmdbPage<T>(val page: Int = 1, val results: List<T> = emptyList())

@Serializable
data class TmdbDetail(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
        val genres: List<TmdbGenre> = emptyList(),
        @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
        @SerialName("networks") val networks: List<TmdbCompany> = emptyList(),
        val images: TmdbImages? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreatedBy> = emptyList(),
    val recommendations: TmdbPage<TmdbMedia>? = null,
    @SerialName("videos") val videos: TmdbVideoPage? = null,
    @SerialName("keywords") val keywords: TmdbKeywordPage? = null,
    val seasons: List<TmdbSeason> = emptyList(),
    @SerialName("belongs_to_collection") val collection: TmdbCollection? = null
) {
    val displayTitle: String get() = title ?: name ?: ""
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
}

@Serializable
data class TmdbCollection(
    val id: Int,
    val name: String? = null,
    val parts: List<TmdbMedia> = emptyList()
)

@Serializable
data class TmdbImages(
    val logos: List<TmdbImage> = emptyList(),
    val backdrops: List<TmdbImage> = emptyList(),
    val posters: List<TmdbImage> = emptyList()
)

@Serializable
data class TmdbImage(@SerialName("file_path") val filePath: String? = null)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbKeyword(val id: Int, val name: String)

@Serializable
data class TmdbCompany(
    val id: Int = 0,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null
)

@Serializable
data class TmdbVideo(
    @SerialName("key") val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val type: String? = null
)

@Serializable
data class TmdbVideoPage(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbKeywordPage(val keywords: List<TmdbKeyword> = emptyList())

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("facebook_id") val facebookId: String? = null,
    @SerialName("instagram_id") val instagramId: String? = null,
    @SerialName("twitter_id") val twitterId: String? = null
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList()
)

@Serializable
data class TmdbCast(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0
)

@Serializable
data class TmdbCrew(
    val id: Int,
    val name: String,
    val job: String? = null,
    val department: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbCreatedBy(
    val id: Int,
    val name: String? = null,
    @SerialName("profile_path") val profilePath: String? = null
)

@Serializable
data class TmdbSeason(
    val id: Int,
    val name: String? = null,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null
)

@Serializable
data class TmdbEpisode(
    val id: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0
)

@Serializable
private data class TmdbGenrePage(val genres: List<TmdbGenre> = emptyList())

@Serializable
private data class TmdbSeasonDetail(val episodes: List<TmdbEpisode> = emptyList())

object Tmdb {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = Injekt.get<NetworkHelper>().client
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    val apiKey: String get() = PrefManager.getVal(PrefName.TmdbApiKey)

    private fun url(path: String, vararg query: Pair<String, String>): String {
        val q = buildString {
            append("api_key=").append(apiKey)
            for ((k, v) in query) append("&").append(k).append("=").append(v)
        }
        return "$BASE$path?$q"
    }

    internal suspend fun get(path: String, vararg query: Pair<String, String>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url(path, *query)).build()
                client.newCall(request).execute().use { it.body?.string() }
            }.getOrNull()
        }

    suspend fun search(query: String, page: Int = 1): List<TmdbMedia> {
        val body = get("/search/multi", "query" to query, "page" to page.toString())
            ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
            .filter { it.mediaType != "person" }
    }

    suspend fun trending(mediaType: String = "all", timeWindow: String = "week"): List<TmdbMedia> {
        val body = get("/trending/$mediaType/$timeWindow") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun discover(
        mediaType: String = "movie",
        genres: String? = null,
        sort: String? = null,
        year: Int? = null,
        keywords: String? = null,
        page: Int = 1,
        extra: List<Pair<String, String>> = emptyList()
    ): List<TmdbMedia> {
        val query = mutableListOf("page" to page.toString())
        genres?.let { query.add("with_genres" to it) }
        sort?.let { query.add("sort_by" to it) }
        year?.let { query.add("year" to it.toString()) }
        keywords?.let { query.add("with_keywords" to it) }
        query.addAll(extra)
        val body = get("/discover/$mediaType", *query.toTypedArray()) ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun popular(page: Int = 1): List<TmdbMedia> {
        val movies = get("/movie/popular", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        val shows = get("/tv/popular", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        return movies + shows
    }

    suspend fun topRated(page: Int = 1): List<TmdbMedia> {
        val movies = get("/movie/top_rated", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        val shows = get("/tv/top_rated", "page" to page.toString())
            ?.let { runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(it).results }.getOrDefault(emptyList()) }
            ?: emptyList()
        return movies + shows
    }

    /** Today as "yyyy-MM-dd" — ceiling so "latest" never includes unreleased entries. */
    private fun todayIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    suspend fun latestMovies(page: Int = 1): List<TmdbMedia> =
        discover(
            sort = "primary_release_date.desc",
            page = page,
            extra = listOf("primary_release_date.lte" to todayIso())
        )

    suspend fun latestSeries(page: Int = 1): List<TmdbMedia> =
        discover(
            "tv",
            sort = "first_air_date.desc",
            page = page,
            extra = listOf("first_air_date.lte" to todayIso())
        )

    suspend fun searchKeywords(query: String): List<TmdbKeyword> {
        val body = get("/search/keyword", "query" to query) ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbKeyword>>(body).results }
            .getOrDefault(emptyList())
    }

    suspend fun genres(): List<TmdbGenre> {
        val movieGenres = get("/genre/movie/list")
            ?.let { runCatching { json.decodeFromString<TmdbGenrePage>(it).genres }.getOrDefault(emptyList()) }
            ?: emptyList()
        val tvGenres = get("/genre/tv/list")
            ?.let { runCatching { json.decodeFromString<TmdbGenrePage>(it).genres }.getOrDefault(emptyList()) }
            ?: emptyList()
        return (movieGenres + tvGenres).distinctBy { it.id }.sortedBy { it.name }
    }

    suspend fun detail(mediaType: String, id: Int): TmdbDetail? {
        val body = get(
            "/$mediaType/$id",
            "append_to_response" to "images,external_ids,credits,recommendations,videos,keywords"
        ) ?: return null
        return runCatching { json.decodeFromString<TmdbDetail>(body) }.getOrNull()
    }

    suspend fun seasons(mediaType: String, id: Int): List<TmdbSeason> {
        val detail = detail(mediaType, id) ?: return emptyList()
        return detail.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
    }

    suspend fun episodes(mediaType: String, id: Int, season: Int): List<TmdbEpisode> {
        val body = get("/$mediaType/$id/season/$season") ?: return emptyList()
        return runCatching {
            json.decodeFromString<TmdbSeasonDetail>(body).episodes
        }.getOrDefault(emptyList())
    }

    /** All movies in a collection, sorted by release date (earliest first). */
    suspend fun collection(id: Int): List<TmdbMedia> {
        val body = get("/collection/$id") ?: return emptyList()
        return runCatching {
            json.decodeFromString<TmdbCollection>(body).parts
                .sortedBy { it.releaseDate }
        }.getOrDefault(emptyList())
    }

    /** Best backdrop/poster for a genre, via a one-off discover call. */
    suspend fun genreBannerUrl(genreId: Int): String? {
        val res = discover(
            mediaType = "movie",
            genres = genreId.toString(),
            sort = "popularity.desc",
            page = 1
        )
        return res.firstNotNullOfOrNull {
            imageUrl(it.backdropPath, 780) ?: imageUrl(it.posterPath, 342)
        }
    }


    fun imageUrl(path: String?, width: Int = 500): String? {
        if (path.isNullOrBlank()) return null
        // Plugin (CloudStream) content hands us full image URLs — pass those
        // through untouched; TMDB paths are always relative fragments.
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val slash = if (path.startsWith("/")) "" else "/"
        return "$IMG/w$width$slash$path"
    }

    fun logoUrl(detail: TmdbDetail): String? = pickLogo(detail.images)

    /** Fetches the best logo for a card directly from the TMDB images endpoint. */
    suspend fun logoUrl(mediaType: String, id: Int): String? {
        val body = get("/$mediaType/$id/images", "include_image_language" to "en,null") ?: return null
        val images = runCatching { json.decodeFromString<TmdbImages>(body) }.getOrNull() ?: return null
        return pickLogo(images)
    }

    private fun pickLogo(images: TmdbImages?): String? {
        val logos = images?.logos.orEmpty().filter { !it.filePath.isNullOrBlank() && !it.filePath!!.endsWith(".svg", true) }
        val chosen = logos.minByOrNull { abs(it.filePath!!.hashCode()) } ?: return null
        return imageUrl(chosen.filePath, 780)
    }

    private fun abs(i: Int): Int = if (i == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(i)
}
