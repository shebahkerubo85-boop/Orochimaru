package ani.sanin.connections.simkl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import ani.sanin.R
import ani.sanin.client
import ani.sanin.currContext
import ani.sanin.openLinkInBrowser
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.tryWith
import ani.sanin.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object Simkl {
    const val clientId = "083331dcd6f5889dd0a1c6e650448061bc468d725b94957703c1442536d35b4f"
    private const val REDIRECT_URI = "ani.sanin://simkl-auth"
    private const val BASE = "https://api.simkl.com"
    private const val AUTH_URL = "https://simkl.com"

    private const val IMG_BASE = "https://simkl.in/posters"

    fun imageUrl(path: String?, size: String = "m"): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "$IMG_BASE/${path}_$size.jpg"
    }

    var token: String? = null
    var username: String? = null
    var avatar: String? = null
    var userid: String? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val okHttpClient get() = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client

    fun loginIntent(context: Context) {
        val codeVerifier = generateCodeVerifier()
        PrefManager.setVal(PrefName.SimklCodeVerifier, codeVerifier)
        val codeChallenge = codeVerifier  // Simkl uses plain code_challenge
        val url = "$AUTH_URL/oauth/authorize?client_id=$clientId&redirect_uri=${Uri.encode(REDIRECT_URI)}&response_type=code&code_challenge=$codeChallenge&code_challenge_method=plain"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            openLinkInBrowser(url)
        }
    }

    private fun generateCodeVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return (1..128).map { chars.random() }.joinToString("")
    }

    fun getSavedToken(): Boolean {
        val result = tryWith(false) {
            val res = PrefManager.getNullableVal<SimklToken>(PrefName.SimklToken, null)
            if (res == null) {
                ani.sanin.util.Logger.log("Simkl.getSavedToken: no token in prefs")
                return@tryWith false
            }
            token = res.accessToken
            username = PrefManager.getVal<String?>(PrefName.SimklUserName)
            avatar = PrefManager.getVal<String?>(PrefName.SimklAvatar)
            userid = PrefManager.getVal<String?>(PrefName.SimklUserId)
            ani.sanin.util.Logger.log("Simkl.getSavedToken: OK name=$username avatar=${avatar?.take(50)}")
            true
        } ?: false
        if (!result) ani.sanin.util.Logger.log("Simkl.getSavedToken: FAILED")
        return result
    }

    fun removeSavedToken() {
        token = null
        username = null
        avatar = null
        userid = null
        PrefManager.removeVal(PrefName.SimklToken)
    }

    private fun refreshToken(): SimklToken? {
        return tryWith {
            val saved = PrefManager.getNullableVal<SimklToken>(PrefName.SimklToken, null)
                ?: return@tryWith null
            val refresh = saved.refreshToken ?: return@tryWith null
            val body = json.encodeToString(
                SimklTokenRequest.serializer(),
                SimklTokenRequest(
                    clientId = clientId,
                    clientSecret = "",
                    redirectUri = REDIRECT_URI,
                    code = "",
                    grantType = "refresh_token",
                    refreshToken = refresh
                )
            )
            val request = Request.Builder()
                .url("$BASE/oauth/token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: return@tryWith null
            val token = json.decodeFromString(SimklToken.serializer(), respBody)
            saveToken(token)
            token
        }
    }

    suspend fun exchangeCode(code: String): SimklToken? {
        return tryWithSuspend {
            // Exchange authorization code for token
            val body = json.encodeToString(
                SimklTokenRequest.serializer(),
                SimklTokenRequest(
                    clientId = clientId,
                    clientSecret = "",
                    redirectUri = REDIRECT_URI,
                    code = code,
                    grantType = "authorization_code"
                )
            )
            val request = Request.Builder()
                .url("$BASE/oauth/token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: return@tryWithSuspend null
            val token = json.decodeFromString(SimklToken.serializer(), respBody)
            saveToken(token)
            token
        }
    }

    @Serializable
    data class SimklToken(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("created_at") val createdAt: Long? = null
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
        fun isExpired(): Boolean {
            val created = createdAt
            val life = expiresIn
            // If createdAt is null (common with PKCE flow), trust the token
            if (created == null || life == null) return false
            return System.currentTimeMillis() / 1000 > created + life - 60
        }
    }

    @Serializable
    data class SimklUser(
        val user: SimklUserInner? = null,
        val account: SimklAccount? = null
    )

    @Serializable
    data class SimklAccount(
        val id: Int? = null
    )

    @Serializable
    data class SimklUserInner(
        val name: String? = null,
        val ids: SimklUserIds? = null,
        val avatar: kotlinx.serialization.json.JsonElement? = null
    ) {
        val avatarUrl: String?
            get() = when (avatar) {
                is kotlinx.serialization.json.JsonPrimitive -> avatar.content
                is kotlinx.serialization.json.JsonObject -> avatar["full"]?.jsonPrimitive?.content
                else -> null
            }
    }

    @Serializable
    data class SimklUserIds(
        val slug: String? = null
    )

    @Serializable
    data class SimklAvatar(
        val full: String? = null,
        val medium: String? = null
    )

    @Serializable
    data class ScrobbleItem(
        val show: ScrobbleShow? = null,
        val movie: ScrobbleMovie? = null
    )

    @Serializable
    data class ScrobbleShow(
        val title: String? = null,
        val year: Int? = null,
        val ids: ScrobbleIds? = null,
        val seasons: List<ScrobbleSeason>? = null
    )

    @Serializable
    data class ScrobbleMovie(
        val title: String? = null,
        val year: Int? = null,
        val ids: ScrobbleIds? = null
    )

    @Serializable
    data class ScrobbleSeason(
        val number: Int? = null,
        val episodes: List<ScrobbleEpisode>? = null
    )

    @Serializable
    data class ScrobbleEpisode(
        val number: Int? = null
    )

    @Serializable
    data class ScrobbleIds(
        val simkl: Int? = null,
        val tmdb: Int? = null,
        val imdb: String? = null,
        val anilist: Int? = null
    ) : java.io.Serializable

    @Serializable
    data class SimklHistory(
        val movies: List<SimklWatchedItem>? = null,
        val shows: List<SimklWatchedItem>? = null
    )

    @Serializable
    data class SimklWatchedItem(
        // Top-level fields from /sync/all-items response
        val status: String? = null,
        @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String? = null,
        @SerialName("last_watched_at") val lastWatchedAt: String? = null,
        @SerialName("user_rated_at") val userRatedAt: String? = null,
        @SerialName("user_rating") val userRating: Int? = null,
        @SerialName("last_watched") val lastWatched: String? = null,
        @SerialName("next_to_watch") val nextToWatch: String? = null,
        @SerialName("last_watched_episode") val lastWatchedEpisode: Int? = null,
        val totalEpisodes: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episodes: List<SimklWatchedEpisode>? = null,
        // Nested show/movie objects from API response
        val show: SimklShowData? = null,
        val movie: SimklMovieData? = null
    ) : java.io.Serializable {
        // Computed properties that flatten nested data for UI consumption
        val title: String? get() = show?.title ?: movie?.title
        val year: Int? get() = show?.year ?: movie?.year
        val poster: String? get() = show?.poster ?: movie?.poster
        val ids: ScrobbleIds? get() = show?.ids ?: movie?.ids
        val mediaType: String? get() = if (show != null) "tv" else if (movie != null) "movie" else type
    }

    @Serializable
    data class SimklShowData(
        val title: String? = null,
        val year: Int? = null,
        val poster: String? = null,
        val ids: ScrobbleIds? = null
    ) : java.io.Serializable

    @Serializable
    data class SimklMovieData(
        val title: String? = null,
        val year: Int? = null,
        val poster: String? = null,
        val ids: ScrobbleIds? = null
    ) : java.io.Serializable

    @Serializable
    data class SimklWatchedEpisode(
        val number: Int? = null,
        val aired: Int? = null,
        val completed: Boolean? = null
    ) : java.io.Serializable

    @Serializable
    data class SimklLibrary(
        val movies: List<SimklWatchedItem>? = null,
        val shows: List<SimklWatchedItem>? = null
    )
}

// Simkl API Functions
extension SimklApi on Simkl {
    /** Mark episodes as watched and update library status on Simkl. */
    suspend fun addToHistory(
        type: String,
        title: String,
        year: Int?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        anilistId: Int? = null
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val idsObj = buildJsonObject {
                if (tmdbId != null && tmdbId > 0) put("tmdb", JsonPrimitive(tmdbId.toString()))
                if (!imdbId.isNullOrBlank()) put("imdb", JsonPrimitive(imdbId))
                if (anilistId != null && anilistId > 0) put("anilist", JsonPrimitive(anilistId))
            }
            // Query status BEFORE /sync/history (which resets it to "watching")
            val prevStatus = if (type == "tv") getMediaStatus("tv", tmdbId, imdbId, anilistId) else null

            val body = if (type == "tv") {
                val ep = episode ?: 1
                buildJsonObject {
                    put("shows", buildJsonArray {
                        add(buildJsonObject {
                            put("ids", idsObj)
                            put("seasons", buildJsonArray {
                                add(buildJsonObject {
                                    put("number", JsonPrimitive(season ?: 1))
                                    put("episodes", buildJsonArray {
                                        for (i in 1..ep) {
                                            add(buildJsonObject { put("number", JsonPrimitive(i)) })
                                        }
                                    })
                                })
                            })
                        })
                    })
                }.toString()
            } else {
                // Movies skip /sync/history entirely; mark completed via add-to-list
                setListStatus("movie", title, year, tmdbId, imdbId, "completed")
                return@tryWithSuspend
            }
            val request = Request.Builder()
                .url("$BASE/sync/history")
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = okHttpClient.newCall(request).execute()
            val respBody = resp.body?.string()?.take(300)
            ani.sanin.util.Logger.log("Simkl.addToHistory: HTTP ${resp.code} type=$type title=$title s=${season}e=${episode} resp=$respBody")
            // /sync/history resets show status to "watching" — restore the pre-call status
            if (type == "tv" && (resp.code == 200 || resp.code == 201) && prevStatus != null && prevStatus != "watching") {
                ani.sanin.util.Logger.log("Simkl.addToHistory: restoring status=$prevStatus for $title (was reset by /sync/history)")
                setListStatus("tv", title, year, tmdbId, imdbId, prevStatus, anilistId, skipHistory = true)
            }
        }
    }

    /** Set the list status (watching / plantowatch / completed / dropped / hold) for a show or movie. */
    suspend fun setListStatus(
        type: String,
        title: String,
        year: Int?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        status: String,
        anilistId: Int? = null,
        skipHistory: Boolean = false
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val idsObj = buildJsonObject {
                if (tmdbId != null && tmdbId > 0) put("tmdb", JsonPrimitive(tmdbId.toString()))
                if (!imdbId.isNullOrBlank()) put("imdb", JsonPrimitive(imdbId))
                if (anilistId != null && anilistId > 0) put("anilist", JsonPrimitive(anilistId))
            }

            // For "completed" TV shows, set status FIRST via /sync/add-to-list,
            // THEN mark all episodes watched via /sync/history.
            // If we call history first, Simkl resets status back to "watching".
            if (status == "completed" && type == "tv" && !skipHistory) {
                // 1. Set status to "completed" via /sync/add-to-list FIRST
                val listBody = buildJsonObject {
                    put("shows", buildJsonArray {
                        add(buildJsonObject {
                            put("to", JsonPrimitive(status))
                            put("ids", idsObj)
                        })
                    })
                }.toString()
                val listResp = okHttpClient.newCall(
                    Request.Builder()
                        .url("$BASE/sync/add-to-list")
                        .addHeader("Authorization", "Bearer $t")
                        .addHeader("simkl-api-key", clientId)
                        .addHeader("Content-Type", "application/json")
                        .post(listBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                val listRespBody = listResp.body?.string()?.take(200)
                ani.sanin.util.Logger.log("Simkl.setListStatus: add-to-list HTTP ${listResp.code} status=$status title=$title resp=$listRespBody")
                
                // 2. Then mark all episodes watched via /sync/history
                val seasonsArr = buildJsonArray {
                    try {
                        val tmdbDetail = ani.sanin.connections.tmdb.Tmdb.detail("tv", tmdbId ?: 0)
                        val numSeasons = tmdbDetail?.numberOfSeasons ?: 1
                        for (s in 1..numSeasons) {
                            val eps = try {
                                ani.sanin.connections.tmdb.Tmdb.episodes("tv", tmdbId ?: 0, s)
                            } catch (_: Exception) { emptyList() }
                            if (eps.isEmpty()) continue
                            add(buildJsonObject {
                                put("number", JsonPrimitive(s))
                                put("episodes", buildJsonArray {
                                    for (ep in eps) {
                                        add(buildJsonObject { put("number", JsonPrimitive(ep.episodeNumber)) })
                                    }
                                })
                            })
                        }
                    } catch (_: Exception) {
                        add(buildJsonObject {
                            put("number", JsonPrimitive(1))
                            put("episodes", buildJsonArray {
                                for (i in 1..99) {
                                    add(buildJsonObject { put("number", JsonPrimitive(i)) })
                                }
                            })
                        })
                    }
                }
                if (seasonsArr.isNotEmpty()) {
                    val histBody = buildJsonObject {
                        put("shows", buildJsonArray {
                            add(buildJsonObject {
                                put("ids", idsObj)
                                put("seasons", seasonsArr)
                            })
                        })
                    }
                    val histResp = okHttpClient.newCall(
                        Request.Builder()
                            .url("$BASE/sync/history")
                            .addHeader("Authorization", "Bearer $t")
                            .addHeader("simkl-api-key", clientId)
                            .addHeader("Content-Type", "application/json")
                            .post(histBody.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    ani.sanin.util.Logger.log("Simkl.setListStatus: history HTTP ${histResp.code} for completed tv (${seasonsArr.size} seasons)")
                }
            } else {
                // For non-completed or movies, just set status via /sync/add-to-list
                val listBody = buildJsonObject {
                    put(if (type == "tv") "shows" else "movies", buildJsonArray {
                        add(buildJsonObject {
                            put("to", JsonPrimitive(status))
                            put("ids", idsObj)
                        })
                    })
                }.toString()
                val resp = okHttpClient.newCall(
                    Request.Builder()
                        .url("$BASE/sync/add-to-list")
                        .addHeader("Authorization", "Bearer $t")
                        .addHeader("simkl-api-key", clientId)
                        .addHeader("Content-Type", "application/json")
                        .post(listBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                val respBody = resp.body?.string()?.take(200)
                ani.sanin.util.Logger.log("Simkl.setListStatus: HTTP ${resp.code} status=$status title=$title resp=$respBody")
            }
        }
    }

    /** Add item to Simkl watchlist with "watching" status (called on first scrobble start). */
    suspend fun addToWatchlist(
        type: String,
        tmdbId: Int? = null,
        imdbId: String? = null,
        anilistId: Int? = null
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val idsObj = buildJsonObject {
                if (tmdbId != null && tmdbId > 0) put("tmdb", JsonPrimitive(tmdbId.toString()))
                if (!imdbId.isNullOrBlank()) put("imdb", JsonPrimitive(imdbId))
                if (anilistId != null && anilistId > 0) put("anilist", JsonPrimitive(anilistId))
            }
            val body = buildJsonObject {
                put(if (type == "tv") "shows" else "movies", buildJsonArray {
                    add(buildJsonObject {
                        put("to", JsonPrimitive("watching"))
                        put("ids", idsObj)
                    })
                })
            }.toString()
            val resp = okHttpClient.newCall(
                Request.Builder()
                    .url("$BASE/sync/add-to-list")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("simkl-api-key", clientId)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            val respBody = resp.body?.string()?.take(200)
            ani.sanin.util.Logger.log("Simkl.addToWatchlist: HTTP ${resp.code} type=$type tmdb=$tmdbId resp=$respBody")
        }
    }

    /** Get the user's list status for a specific show/movie from Simkl library. */
    suspend fun getMediaStatus(
        type: String,
        tmdbId: Int? = null,
        imdbId: String? = null,
        anilistId: Int? = null
    ): String? {
        val t = token ?: return null
        return try {
            val items = if (type == "tv") getShowLibrary() else getMovieLibrary()
            items.firstOrNull { item ->
                val ids = item.ids
                ids != null && (
                    (tmdbId != null && ids.tmdb == tmdbId) ||
                    (imdbId != null && ids.imdb == imdbId) ||
                    (anilistId != null && ids.anilist == anilistId)
                )
            }?.status
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getMediaStatus: ${e.message}")
            null
        }
    }

    /** Get continue watching (in progress) items from Simkl library */
    suspend fun getContinueWatching(): List<SimklWatchedItem> {
        val t = token
        if (t == null) {
            ani.sanin.util.Logger.log("Simkl.getContinueWatching: token is null")
            return emptyList()
        }
        // Derive continue watching from library data (same as AnymeX)
        // /sync/history doesn't work reliably with GET via this client
        return try {
            val movies = getMovieLibrary().filter {
                val s = it.status?.lowercase()
                s == "watching" || s == "current"
            }
            val shows = getShowLibrary().filter {
                val s = it.status?.lowercase()
                s == "watching" || s == "current"
            }
            val items = (movies + shows).sortedByDescending {
                it.lastWatchedAt ?: ""
            }
            ani.sanin.util.Logger.log("Simkl.getContinueWatching: ${items.size} items (${movies.size} movies, ${shows.size} shows)")
            items
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getContinueWatching: exception ${e.message}")
            emptyList()
        }
    }

    /** Get full library (movies + shows) */
    suspend fun getLibrary(): SimklLibrary? {
        val t = token
        if (t == null) return null
        return try {
            val showsResp = okHttpClient.newCall(
                Request.Builder()
                    .url("$BASE/users/self/library/shows")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("simkl-api-key", clientId)
                    .addHeader("Content-Type", "application/json")
                    .build()
            ).execute().body?.string()?.take(500)?.let { json.decodeFromString(SimklLibrary.serializer(), it) } ?: emptyList()
            val moviesResp = okHttpClient.newCall(
                Request.Builder()
                    .url("$BASE/users/self/library/movies")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("simkl-api-key", clientId)
                    .addHeader("Content-Type", "application/json")
                    .build()
            ).execute().body?.string()?.take(500)?.let { json.decodeFromString(SimklLibrary.serializer(), it) } ?: emptyList()
            SimklLibrary(movies = moviesResp.movies, shows = showsResp.shows)
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getLibrary: ${e.message}")
            null
        }
    }

    /** Get watchlist (movies + shows) from Simkl */
    suspend fun getWatchlist(): SimklLibrary? {
        val t = token
        if (t == null) return null
        return try {
            val showsResp = okHttpClient.newCall(
                Request.Builder()
                    .url("$BASE/users/self/watchlist/shows")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("simkl-api-key", clientId)
                    .addHeader("Content-Type", "application/json")
                    .build()
            ).execute().body?.string()?.take(500)?.let { json.decodeFromString(SimklLibrary.serializer(), it) } ?: emptyList()
            val moviesResp = okHttpClient.newCall(
                Request.Builder()
                    .url("$BASE/users/self/watchlist/movies")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("simkl-api-key", clientId)
                    .addHeader("Content-Type", "application/json")
                    .build()
            ).execute().body?.string()?.take(500)?.let { json.decodeFromString(SimklLibrary.serializer(), it) } ?: emptyList()
            SimklLibrary(movies = moviesResp.movies, shows = showsResp.shows)
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getWatchlist: ${e.message}")
            null
        }
    }

    /** Save token to preferences */
    private fun saveToken(token: SimklToken) {
        PrefManager.setVal(PrefName.SimklToken, token)
        this.token = token.accessToken
        username = PrefManager.getVal<String?>(PrefName.SimklUserName)
        avatar = PrefManager.getVal<String?>(PrefName.SimklAvatar)
        userid = PrefManager.getVal<String?>(PrefName.SimklUserId)
        ani.sanin.util.Logger.log("Simkl.saveToken: saved token for $username")
    }
}
