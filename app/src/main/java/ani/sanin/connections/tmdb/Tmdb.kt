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

/**
 * A streaming service as TMDB knows it in a given country, from the watch-provider
 * endpoints.
 *
 * The logo is TMDB's own `logo_path`, served from its image CDN like any poster —
 * nothing brand-owned is shipped in the app. [logoUrl] is resolved by [Tmdb.imageUrl]
 * at the standard provider-logo size.
 */
@Serializable
data class TmdbProvider(
    @SerialName("provider_id") val id: Int = 0,
    @SerialName("provider_name") val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val priority: Int = 9999
) {
    val displayName: String get() = name.orEmpty()
}

/**
 * The region the streaming-services rail is pinned to until a region setting exists.
 * Hardcoded like every other TMDB locale in the app (`en-US`).
 */
private const val DEFAULT_PROVIDER_REGION = "US"

/** TMDB genre 16 — the animation shelf the third chip selects. */
private const val ANIMATION_GENRE = "16"

private const val TYPE_MOVIE = "movie"
private const val TYPE_TV = "tv"
private const val TYPE_ANIMATION = "animation"

/**
 * TMDB's genre vocabulary, so a list response's bare `genre_ids` can be turned into the
 * names the classic banner's chips show. Movie and TV number some of the same ideas
 * differently — 28 is Action for film but 10759 is Action & Adventure for TV, and Fantasy
 * splits the same way — so the table is picked by media type rather than merged.
 *
 * Hardcoded like [DEFAULT_PROVIDER_REGION]: these ids are TMDB's own stable genre
 * vocabulary, and `/genre/{movie,tv}/list` would only be re-fetching this table.
 */
private val MOVIE_GENRES = mapOf(
    28 to "Action",
    12 to "Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    14 to "Fantasy",
    36 to "History",
    27 to "Horror",
    10402 to "Music",
    9648 to "Mystery",
    10749 to "Romance",
    878 to "Science Fiction",
    53 to "Thriller",
    10752 to "War",
    37 to "Western",
)

private val TV_GENRES = mapOf(
    10759 to "Action & Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    10762 to "Kids",
    9648 to "Mystery",
    10763 to "News",
    10764 to "Reality",
    10765 to "Sci-Fi & Fantasy",
    10766 to "Soap",
    10767 to "Talk",
    10768 to "War & Politics",
    37 to "Western",
)

/**
 * `vote_average` on its own surfaces the one perfect ten from a single rater, so
 * most-favourite and top-rated both ask for a floor of real votes first.
 */
private val VOTE_FLOOR = listOf("vote_count.gte" to "200")

/**
 * The big services, in the order most people expect to see them.
 *
 * TMDB's own `display_priority` is per-region and mixes the majors in with long-tail
 * catalogues — in India it puts FilmBox+, Cultpix and DOCSVILLE ahead of Crunchyroll — so
 * the rail, which only shows the first handful, would bury the ones people actually have.
 * These ids are pinned to the front in this order; everything else keeps TMDB's order
 * behind them. Ids read from the live API, not guessed. Amazon and Apple appear twice on
 * purpose: TMDB uses a different id for the same brand in different regions.
 */
private val POPULAR_PROVIDER_IDS = intArrayOf(
    8,    // Netflix
    9,    // Amazon Prime Video (US, GB)
    119,  // Amazon Prime Video (IN)
    337,  // Disney Plus
    350,  // Apple TV
    283,  // Crunchyroll
    1899, // HBO Max
    15,   // Hulu
    531,  // Paramount Plus
    386,  // Peacock Premium
    2336, // JioHotstar
    232   // Zee5
)

/** Rent-and-buy stores and profile variants — not services you subscribe to. */
private val NOT_A_SUBSCRIPTION = setOf(
    2,    // Apple TV Store — rent/buy
    3,    // Google Play Movies — rent/buy
    10,   // Amazon Video — rent/buy
    68,   // Microsoft Store — rent/buy
    192,  // YouTube — free/rent, not a subscription catalogue
    175,  // Netflix Kids — a profile, not a service
    2285  // JustWatch TV — the comparison site's own channel
)

/**
 * Whether this entry is a streaming service someone can subscribe to.
 *
 * TMDB's list is mostly resellers: of 92 entries in India, 38 are `<name> Amazon Channel`,
 * `<name> Apple TV channel`, a rent/buy store, or an ad-supported duplicate of a service
 * already in the list. In the US it is 163 of 333. They clutter the rail and duplicate the
 * brands next to them with near-identical logos. A service sold ONLY as an Amazon channel
 * drops out with them — the accepted cost, because the alternative is a rail where Apple
 * appears twice.
 */
private fun isSubscribableService(id: Int, name: String): Boolean {
    if (id in NOT_A_SUBSCRIPTION) return false
    val n = name.lowercase().trim()
    return !n.endsWith("channel") && !n.endsWith("store") &&
        !n.endsWith("kids") && !n.contains("with ads")
}

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
    @SerialName("belongs_to_collection") val collection: TmdbCollection? = null,
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisode? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbEpisode? = null
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

    /** Lightweight genre lookup for a single movie/tv entry (no heavy append_to_response). */
    suspend fun detailGenres(mediaType: String, id: Int): List<TmdbGenre> {
        val body = get("/$mediaType/$id") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbDetail>(body).genres }.getOrDefault(emptyList())
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

    /**
     * The Explore rows, in the order the page shows them.
     *
     * `POPULAR` and `MOST_FAVOURITE` are deliberately different orderings of the same
     * library: TMDB's `/popular` ranks by its own popularity metric (views and trailer
     * plays), while most-favourite ranks by what viewers actually rated. They must not
     * collapse into the same list, so neither is derived from the other.
     */
    enum class ExploreRow { IN_CINEMA, TRENDING, TOP_RATED, MOST_FAVOURITE, LATEST_RELEASE, POPULAR }

    /**
     * One Explore row for the type chip in use.
     *
     * `mediaType` is "movie", "tv" or "animation". Animation has no TMDB endpoint of its
     * own, so it is genre [ANIMATION_GENRE] and every row that `/now_playing`,
     * `/trending` and `/top_rated` cannot filter (those endpoints take no `with_genres`)
     * is re-sourced from `/discover`, which can.
     */
    suspend fun exploreRow(
        row: ExploreRow,
        mediaType: String,
        page: Int = 1
    ): List<TmdbMedia> {
        val animation = mediaType == TYPE_ANIMATION
        val type = if (animation) TYPE_MOVIE else mediaType
        val genres = if (animation) ANIMATION_GENRE else null
        return when (row) {
            ExploreRow.IN_CINEMA -> when {
                type == TYPE_TV -> list("/tv/airing_today")
                // "Now playing" is films only, and it takes no genre filter, so the
                // animation chip falls back to the newest animated releases.
                genres != null -> discover(
                    type, genres = genres, sort = "primary_release_date.desc", page = page,
                    extra = listOf("primary_release_date.lte" to todayIso())
                )
                else -> list("/movie/now_playing")
            }
            ExploreRow.TRENDING -> when {
                // Trending has no genre parameter either, but it does return genre_ids,
                // so the animated shelf is the mixed week list narrowed to animation.
                genres != null -> list("/trending/all/week")
                    .filter { ANIMATION_GENRE.toInt() in it.genreIds }
                type == TYPE_TV -> list("/trending/tv/week")
                else -> list("/trending/movie/week")
            }
            ExploreRow.TOP_RATED -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "vote_average.desc", page = page,
                    extra = VOTE_FLOOR
                )
                else -> list("/$type/top_rated", "page" to page.toString())
            }
            ExploreRow.MOST_FAVOURITE -> discover(
                type, genres = genres, sort = "vote_average.desc", page = page,
                extra = VOTE_FLOOR
            )
            ExploreRow.LATEST_RELEASE -> discover(
                type, genres = genres, page = page,
                sort = if (type == TYPE_TV) "first_air_date.desc" else "primary_release_date.desc",
                extra = listOf(
                    (if (type == TYPE_TV) "first_air_date.lte" else "primary_release_date.lte")
                        to todayIso()
                )
            )
            ExploreRow.POPULAR -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "popularity.desc", page = page
                )
                else -> list("/$type/popular", "page" to page.toString())
            }
        }
    }

    /** A plain list endpoint, decoded like every other. */
    private suspend fun list(path: String, vararg query: Pair<String, String>): List<TmdbMedia> {
        val body = get(path, *query) ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
    }

    /**
     * The streaming services TMDB lists for [region] (ISO 3166-1 alpha-2, e.g. "US"),
     * majors first, de-duplicated and filtered to real subscriptions.
     *
     * Merged from the `/watch/providers/movie` and `/watch/providers/tv` endpoints because a
     * service usually appears in both and the browse screen shows one tile per service, not
     * two. Region is hardcoded to [DEFAULT_PROVIDER_REGION] for now, until a region setting
     * lands; every TMDB call in the app otherwise hardcodes `en-US` too.
     */
    suspend fun watchProviders(region: String = DEFAULT_PROVIDER_REGION): List<TmdbProvider> {
        val byId = LinkedHashMap<Int, TmdbProvider>()
        for (endpoint in listOf("/watch/providers/movie", "/watch/providers/tv")) {
            val body = get(endpoint, "watch_region" to region) ?: continue
            val results = runCatching {
                json.decodeFromString<TmdbPage<TmdbProvider>>(body).results
            }.getOrDefault(emptyList())
            for (p in results) {
                val name = p.displayName
                if (p.id == 0 || name.isEmpty()) continue
                if (!isSubscribableService(p.id, name)) continue
                // First endpoint wins; the rows are identical where they overlap.
                byId.putIfAbsent(p.id, p)
            }
        }
        return byId.values.sortedWith(
            compareBy(
                { POPULAR_PROVIDER_IDS.indexOf(it.id).let { i -> if (i < 0) POPULAR_PROVIDER_IDS.size else i } },
                { it.priority },
                { it.displayName }
            )
        )
    }

    /**
     * One page of what [providerId] carries in [region], filtered to [mediaType]
     * ("movie" or "tv"). Mirrors Zangetsu's provider catalogue: `with_watch_providers`
     * plus the region, sorted by popularity. The service grid calls this for the type the
     * user's movie/TV filter has selected.
     */
    suspend fun providerTitles(
        providerId: Int,
        mediaType: String,
        page: Int = 1,
        region: String = DEFAULT_PROVIDER_REGION
    ): List<TmdbMedia> = discover(
        mediaType = mediaType,
        sort = "popularity.desc",
        page = page,
        extra = listOf(
            "with_watch_providers" to providerId.toString(),
            "watch_region" to region
        )
    )

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
            "language" to "en-US",
            "append_to_response" to "images,external_ids,credits,recommendations,videos,keywords"
        ) ?: return null
        return runCatching { json.decodeFromString<TmdbDetail>(body) }.getOrNull()
    }

    suspend fun seasons(mediaType: String, id: Int): List<TmdbSeason> {
        val detail = detail(mediaType, id) ?: return emptyList()
        return detail.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
    }

    suspend fun episodes(mediaType: String, id: Int, season: Int): List<TmdbEpisode> {
        val body = get("/$mediaType/$id/season/$season", "language" to "en-US") ?: return emptyList()
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


    /**
     * [TmdbMedia.genreIds] as the display names a card can show, in TMDB's own order.
     *
     * The classic banner's chip row is built from [ani.sanin.media.Media.genres] plus
     * format, status and score. Without this, a TMDB title arrives with no genres and the
     * banner falls back to a lone score chip, leaving the middle of the card empty.
     */
    fun genreNames(media: TmdbMedia): List<String> {
        val table = if (media.type == TYPE_TV) TV_GENRES else MOVIE_GENRES
        return media.genreIds.mapNotNull { table[it] }
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
