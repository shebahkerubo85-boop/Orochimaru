package ani.sanin.connections.tmdb

import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseEntry
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
    @SerialName("vote_count") val voteCount: Int = 0,
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

/**
 * One page of a TMDB list response.
 *
 * [totalPages] is kept because a paged list has to know where it stops: without it there
 * is no way to tell "the last page" from "a page I have not asked for yet", so an
 * infinite scroller can only ever stop by guessing. Defaults are 1 page / 0 results so a
 * response missing them (or a decode that failed) reads as a finished list rather than a
 * huge one.
 */
@Serializable
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0
)

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

/**
 * How far ahead [ExploreRow.UPCOMING] looks.
 *
 * A year, not a month: the rail is sorted by `popularity` and nothing is filtered on
 * quality, because an unreleased title has no votes and no vote floor is usable. Widening
 * the window is therefore free — it adds anticipation-ordered titles (Dune, Avengers)
 * without demoting the near-term ones, and keeps the shelf from emptying in a month.
 */
private const val UPCOMING_WINDOW_DAYS = 370

/**
 * TMDB's hard ceiling on paging, and it is lower than the number TMDB reports.
 *
 * Both `/discover` and the list endpoints answer HTTP 400 past page 500 — "Pages start at 1
 * and max at 500" — while still reporting `total_pages: 1001`. Verified against
 * `/movie/popular`, `/tv/popular` and `/discover/movie`. So `total_pages` cannot be trusted
 * on its own: an unclamped scroller pages happily up to 1001 and then spends its remaining
 * attempts on requests that can only fail.
 */
private const val MAX_PAGE = 500

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
/**
 * Minimum votes before a title counts as "rated". A query filter for the released rows,
 * and again in code to rank a person's credits, so it is one number in one place.
 */
private const val VOTE_FLOOR_MIN = 200

private val VOTE_FLOOR = listOf("vote_count.gte" to VOTE_FLOOR_MIN.toString())

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

    /**
     * The date this title actually sorts by.
     *
     * `release_date` is null for every TV row, which is not a missing value but a different
     * field, so ordering on it alone silently leaves TV parts unsorted. [year] already reads
     * the year out of whichever date is present.
     */
    val sortDate: String? get() = releaseDate ?: firstAirDate
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

/**
 * One person from `/person/{id}` — the cast member behind a credit, opened from a cast card.
 *
 * Every field except [id] and [name] is nullable because TMDB genuinely omits them: a
 * long-dead actor has no [birthday] range worth showing, an extra in a festival short has
 * no [biography] at all, and roughly one in a dozen has no [profilePath]. The screen has to
 * treat each as "not stated" rather than as an error.
 */
@Serializable
data class TmdbPerson(
    val id: Int,
    val name: String,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("also_known_as") val alsoKnownAs: List<String> = emptyList(),
    @SerialName("imdb_id") val imdbId: String? = null,
    /**
     * Only present when the request asked for `append_to_response=combined_credits`.
     *
     * TMDB merges an appended response into the *top level* of the reply rather than
     * nesting it, so this arrives beside `name` and `biography` instead of under them.
     */
    @SerialName("combined_credits") val combinedCredits: TmdbCombinedCredits? = null
)

@Serializable
data class TmdbPersonImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0
)

@Serializable
data class TmdbPersonImages(val profiles: List<TmdbPersonImage> = emptyList())

@Serializable
data class TmdbCombinedCredits(
    val cast: List<TmdbMedia> = emptyList(),
    val crew: List<TmdbMedia> = emptyList()
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
    ): List<TmdbMedia> = discoverPage(
        mediaType, genres, sort, year, keywords, page, extra
    ).results

    /** [discover], keeping the page counts so a caller that pages can see the ceiling. */
    suspend fun discoverPage(
        mediaType: String = "movie",
        genres: String? = null,
        sort: String? = null,
        year: Int? = null,
        keywords: String? = null,
        page: Int = 1,
        extra: List<Pair<String, String>> = emptyList()
    ): TmdbPage<TmdbMedia> {
        val query = mutableListOf("page" to page.toString())
        genres?.let { query.add("with_genres" to it) }
        sort?.let { query.add("sort_by" to it) }
        year?.let { query.add("year" to it.toString()) }
        keywords?.let { query.add("with_keywords" to it) }
        query.addAll(extra)
        val body = get("/discover/$mediaType", *query.toTypedArray())
            ?: return TmdbPage(page = page)
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body) }
            .getOrDefault(TmdbPage(page = page))
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

    /** Titles similar to a single item, used to seed the Simkl "Recommended" rail. */
    suspend fun similar(mediaType: String, id: Int): List<TmdbMedia> {
        val body = get("/$mediaType/$id/similar") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body).results }
            .getOrDefault(emptyList())
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
     * The Explore rows. The first five are the page's rails, in the order it shows them;
     * `POPULAR` is the viewer's own list and is not a rail.
     *
     * `POPULAR` is deliberately not derived from `TOP_RATED`: TMDB's `/popular` ranks by
     * its own popularity metric (views and trailer plays), while top-rated ranks by what
     * viewers actually rated, so the two must not collapse into the same list.
     */
    enum class ExploreRow { IN_CINEMA, TRENDING, TOP_RATED, LATEST_RELEASE, UPCOMING, POPULAR }

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
        val result = when (row) {
            ExploreRow.IN_CINEMA -> when {
                type == TYPE_TV -> list("/tv/airing_today", "page" to page.toString())
                // "Now playing" is films only, and it takes no genre filter, so the
                // animation chip falls back to the newest animated releases.
                genres != null -> discover(
                    type, genres = genres, sort = "primary_release_date.desc", page = page,
                    extra = listOf("primary_release_date.lte" to todayIso())
                )
                else -> list("/movie/now_playing", "page" to page.toString())
            }
            ExploreRow.TRENDING -> when {
                // Trending has no genre parameter either, but it does return genre_ids,
                // so the animated shelf is the mixed week list narrowed to animation. One
                // page of a mixed list holds only a couple of animated titles, so the
                // first pages are pulled together until the shelf is worth showing.
                genres != null -> {
                    val animationGenres = (1..4).flatMap { p ->
                        list("/trending/all/week", "page" to p.toString())
                    }.filter { ANIMATION_GENRE.toInt() in it.genreIds }
                        .distinctBy { it.id }
                    animationGenres.drop((page - 1) * 20).take(20)
                }
                type == TYPE_TV -> list("/trending/tv/week", "page" to page.toString())
                else -> list("/trending/movie/week", "page" to page.toString())
            }
            ExploreRow.TOP_RATED -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "vote_average.desc", page = page,
                    extra = VOTE_FLOOR
                )
                else -> list("/$type/top_rated", "page" to page.toString())
            }
            ExploreRow.LATEST_RELEASE -> discover(
                type, genres = genres, page = page,
                sort = if (type == TYPE_TV) "first_air_date.desc" else "primary_release_date.desc",
                extra = listOf(
                    (if (type == TYPE_TV) "first_air_date.lte" else "primary_release_date.lte")
                        to todayIso()
                )
            )
            // Forward-looking, so it is ranked by `popularity` rather than by rating: TMDB
            // computes popularity from trailer plays and searches, which exist before a
            // release, while `vote_average` and any vote floor do not. TV has no
            // `/movie/upcoming` equivalent worth using, so both sides go through
            // `/discover` on the date field that actually means "premiere" for the type.
            ExploreRow.UPCOMING -> discover(
                type, genres = genres, page = page,
                sort = "popularity.desc",
                extra = listOf(
                    (if (type == TYPE_TV) "first_air_date.gte" else "primary_release_date.gte")
                        to todayIso(),
                    (if (type == TYPE_TV) "first_air_date.lte" else "primary_release_date.lte")
                        to todayPlusDays(UPCOMING_WINDOW_DAYS)
                )
            )
            ExploreRow.POPULAR -> when {
                genres != null -> discover(
                    type, genres = genres, sort = "popularity.desc", page = page
                )
                else -> list("/$type/popular", "page" to page.toString())
            }
        }
        // A card with no poster reads as a broken shelf, so items that ship no artwork
        // are dropped rather than shown blank. This keeps the discovery rails clean
        // without costing anything for movie/TV, where posterless entries are rare.
        return result.filter { it.posterPath != null }
    }

    /**
     * [exploreRow.POPULAR] as a page, for a list the viewer can keep scrolling.
     *
     * Only this row is worth paging. It is TMDB's entire catalogue rather than a curated
     * shelf, so the first page is 20 items out of 20,001 — 1001 pages — and the other
     * rows are fixed shelves where page 2 is not a continuation of anything the viewer
     * was shown. Returns `totalPages` so the caller can stop at the ceiling instead of
     * asking past it and getting an empty page forever.
     */
    suspend fun popularPage(
        mediaType: String,
        page: Int = 1
    ): TmdbPage<TmdbMedia> {
        val animation = mediaType == TYPE_ANIMATION
        val type = if (animation) TYPE_MOVIE else mediaType
        val genres = if (animation) ANIMATION_GENRE else null
        // Clamped on the way out as well as on the way in: TMDB reports a ceiling it will
        // not serve, so the caller's stopping point has to be the smaller of the two.
        val wanted = page.coerceIn(1, MAX_PAGE)
        val result = if (genres != null) {
            discoverPage(
                type, genres = genres, sort = "popularity.desc", page = wanted
            )
        } else {
            listPage("/$type/popular", "page" to wanted.toString())
        }
        return result.copy(totalPages = result.totalPages.coerceIn(1, MAX_PAGE))
    }

    /** A plain list endpoint, decoded like every other. */
    private suspend fun list(path: String, vararg query: Pair<String, String>): List<TmdbMedia> =
        listPage(path, *query).results

    /** [list], keeping the page counts. */
    private suspend fun listPage(
        path: String,
        vararg query: Pair<String, String>
    ): TmdbPage<TmdbMedia> {
        val body = get(path, *query) ?: return TmdbPage()
        return runCatching { json.decodeFromString<TmdbPage<TmdbMedia>>(body) }
            .getOrDefault(TmdbPage())
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

    /**
     * [todayIso] shifted [days] forward, for the far end of an upcoming window.
     *
     * Goes through [java.util.Calendar] rather than adding milliseconds, so a day added
     * across a DST boundary still lands on the same wall-clock date instead of drifting
     * to 23:00 the day before.
     */
    private fun todayPlusDays(days: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, days)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

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
    suspend fun collection(id: Int): List<TmdbMedia> = collectionDetail(id)?.parts
        ?.sortedBy { it.releaseDate }
        .orEmpty()

    /** A whole collection response, so the caller can read its name as well as its parts. */
    private suspend fun collectionDetail(id: Int): TmdbCollection? {
        val body = get("/collection/$id") ?: return null
        return runCatching { json.decodeFromString<TmdbCollection>(body) }.getOrNull()
    }

    /**
     * Franchise cards for the Explore tab, built from TMDB collections.
     *
     * Seeds are the current type's popular titles, and a title's `belongs_to_collection`
     * gives the franchise it belongs to. Collections are ordered by release date, which is
     * TMDB's only ordering here; it matches story order for the straight-line cases and
     * will be wrong for a prequel released after its sequel. Trakt's curated rank and the
     * generated franchise map are what fix that, and both replace this.
     *
     * TMDB has no equivalent collection for TV, so `belongs_to_collection` is null there and
     * the TV chip yields nothing from this source. Trakt's list structure is what covers TV.
     */
    suspend fun franchiseCards(mediaType: String): List<Franchise> {
        // Animation is a genre on TMDB, not a media type, so the chip has to become genre 16
        // on `movie`. Dropping this turns the Animation chip into plain popular movies, which
        // is what the old Popular row never did either.
        val animation = mediaType == TYPE_ANIMATION
        val seeds = discover(
            mediaType = if (animation) TYPE_MOVIE else mediaType,
            genres = if (animation) ANIMATION_GENRE else null,
            sort = "popularity.desc",
            page = 1
        )
        if (seeds.isEmpty()) return emptyList()
        val collectionIds = seeds.mapNotNull { it.collection?.id }.distinct()

        // Fetched serially and deduplicated by id, since a dozen popular titles usually
        // belong to far fewer than a dozen franchises. One request per collection is also
        // all this needs: the parts come back in the same body as the name, so nothing is
        // re-read here.
        return collectionIds.mapNotNull { id ->
            val collection = runCatching { collectionDetail(id) }.getOrNull() ?: return@mapNotNull null
            val parts = collection.parts
            if (parts.isEmpty()) return@mapNotNull null

            // Ordered once, on the field the type actually populates. Sorting on
            // `releaseDate` alone would leave every TV part null and keep the response order.
            val ordered = parts.sortedBy { it.sortDate }.map { part ->
                FranchiseEntry(
                    year = part.year,
                    sortYear = part.sortDate?.take(4)?.toIntOrNull(),
                    posterUrl = imageUrl(part.posterPath, 342),
                    title = part.displayTitle,
                    backdropUrl = imageUrl(part.backdropPath, 780),
                )
            }
            if (ordered.isEmpty()) return@mapNotNull null

            // Re-read from the sorted entries rather than from `parts`, which is still in
            // response order: taking its last element would not reliably be the latest entry.
            val latest = ordered.last()

            Franchise(
                // The collection's own name, not a part's title, so the card reads as the
                // franchise rather than as whichever film happens to sort first.
                name = collection.name ?: latest.title,
                // Per spec the banner is the latest entry's artwork, preferring the wide
                // crop and falling back to the poster when a title has no backdrop.
                bannerUrl = latest.backdropUrl ?: latest.posterUrl,
                entries = ordered,
            )
        }
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

    /**
     * One cast or crew member, for the screen a cast card opens.
     *
     * Null on a failed request rather than a stand-in person, so the screen can close
     * instead of showing an empty page with a blank name.
     */
    suspend fun person(id: Int): TmdbPerson? {
        val body = get("/person/$id") ?: return null
        return runCatching { json.decodeFromString<TmdbPerson>(body) }.getOrNull()
    }

    /**
     * A person's publicity stills, best first.
     *
     * TMDB returns them in no useful order and many are near-identical crops of one press
     * photo, so they are ranked by `vote_average` — the crowd's own ranking of that image.
     * A person with a single registered photo is normal, so an empty list is not an error.
     */
    suspend fun personProfiles(id: Int): List<TmdbPersonImage> {
        val body = get("/person/$id/images") ?: return emptyList()
        return runCatching { json.decodeFromString<TmdbPersonImages>(body).profiles }
            .getOrDefault(emptyList())
            .filter { !it.filePath.isNullOrBlank() }
            .sortedByDescending { it.voteAverage }
    }

    /**
     * What a person is actually known for, as [TmdbMedia] the rest of the app can open.
     *
     * `combined_credits` is not a filmography and does not survive being taken at face
     * value. Measured on Brad Pitt: 158 raw credits, and sorting by `popularity` — the
     * obvious choice — puts The Daily Show, Raw and Saturday Night Live at the top, because
     * a talk-show cameo has more viewers than a film has votes. Ranking by `vote_average`
     * behind the same [VOTE_FLOOR_MIN] the other rows use gives Fight Club, Se7en and
     * Inglourious Basterds instead.
     *
     * The response also repeats titles: a series a performer returned to across several
     * seasons comes back once per season, so rows are folded by id — keeping the
     * best-attended one — before they are ranked.
     */
    suspend fun personCredits(id: Int): List<TmdbMedia> {
        val body = get("/person/$id", "append_to_response" to "combined_credits")
            ?: return emptyList()
        val credits = runCatching { json.decodeFromString<TmdbPerson>(body) }
            .getOrNull()?.combinedCredits ?: return emptyList()
        val best = LinkedHashMap<Int, TmdbMedia>()
        for (m in credits.cast) {
            val seen = best[m.id]
            if (seen == null || m.voteCount > seen.voteCount) best[m.id] = m
        }
        return best.values
            .filter { it.voteCount >= VOTE_FLOOR_MIN }
            .sortedByDescending { it.voteAverage }
    }

}
