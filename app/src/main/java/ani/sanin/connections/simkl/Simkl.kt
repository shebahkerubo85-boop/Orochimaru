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
            val codeVerifier = PrefManager.getVal<String?>(PrefName.SimklCodeVerifier) ?: ""
            val body = json.encodeToString(
                SimklTokenRequest.serializer(),
                SimklTokenRequest(
                    clientId = clientId,
                    clientSecret = "",
                    redirectUri = REDIRECT_URI,
                    code = code,
                    grantType = "authorization_code",
                    refreshToken = null,
                    codeVerifier = codeVerifier
                )
            )
            val request = Request.Builder()
                .url("$BASE/oauth/token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = okHttpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: return@tryWithSuspend null
            ani.sanin.util.Logger.log("Simkl.exchangeCode: HTTP ${response.code} body=${respBody.take(200)}")
            if (response.code != 200) return@tryWithSuspend null
            val token = json.decodeFromString(SimklToken.serializer(), respBody)
            saveToken(token)
            token
        }
    }

    fun saveToken(res: SimklToken) {
        PrefManager.setVal(PrefName.SimklToken, res)
    }

    /** Fetch user settings (profile info) after login */
    suspend fun fetchUserData(): SimklUser? {
        val t = token
        if (t == null) {
            ani.sanin.logError(Exception("Simkl.fetchUserData: token is null"), snackbar = false)
            return null
        }
        return try {
            val request = Request.Builder()
                .url("$BASE/users/settings")
                .post("".toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            ani.sanin.util.Logger.log("Simkl.fetchUserData: HTTP ${response.code} body=${body?.take(200)}")
            if (response.code != 200 || body == null) {
                ani.sanin.logError(Exception("Simkl.fetchUserData: HTTP ${response.code}"), snackbar = false)
                return null
            }
            val user = json.decodeFromString(SimklUser.serializer(), body)
            username = user.user?.name
            avatar = user.user?.avatarUrl
            userid = user.account?.id?.toString() ?: user.user?.ids?.slug
            ani.sanin.util.Logger.log("Simkl.fetchUserData: name=$username avatar=${avatar?.take(80)} userid=$userid")
            PrefManager.setVal(PrefName.SimklUserName, username ?: "")
            PrefManager.setVal(PrefName.SimklAvatar, avatar ?: "")
            PrefManager.setVal(PrefName.SimklUserId, userid ?: "")
            user
        } catch (e: Exception) {
            ani.sanin.logError(e, snackbar = false)
            ani.sanin.util.Logger.log("Simkl.fetchUserData: exception ${e.message}")
            null
        }
    }

    /** Scrobble: report watching progress */
    suspend fun scrobbleStart(
        type: String,
        title: String,
        year: Int?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        durationSec: Int = 0,
        anilistId: Int? = null
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val ids = ScrobbleIds(tmdb = tmdbId, imdb = imdbId, anilist = anilistId)
            val item = if (type == "tv") {
                ScrobbleItem(
                    show = ScrobbleShow(
                        title = title,
                        year = year,
                        ids = ids,
                        seasons = listOf(
                            ScrobbleSeason(
                                number = season ?: 1,
                                episodes = listOf(
                                    ScrobbleEpisode(number = episode ?: 1)
                                )
                            )
                        )
                    )
                )
            } else {
                ScrobbleItem(
                    movie = ScrobbleMovie(
                        title = title,
                        year = year,
                        ids = ids
                    )
                )
            }
            val request = Request.Builder()
                .url("$BASE/scrobble/start")
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .post(json.encodeToString(ScrobbleItem.serializer(), item).toRequestBody("application/json".toMediaType()))
                .build()
            val resp = okHttpClient.newCall(request).execute()
            ani.sanin.util.Logger.log("Simkl.scrobbleStart: HTTP ${resp.code} type=$type title=$title")
        }
    }

    suspend fun scrobbleStop(
        type: String,
        title: String,
        year: Int?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        durationSec: Int = 0,
        anilistId: Int? = null
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val ids = ScrobbleIds(tmdb = tmdbId, imdb = imdbId, anilist = anilistId)
            val item = if (type == "tv") {
                ScrobbleItem(
                    show = ScrobbleShow(
                        title = title,
                        year = year,
                        ids = ids,
                        seasons = listOf(
                            ScrobbleSeason(
                                number = season ?: 1,
                                episodes = listOf(
                                    ScrobbleEpisode(number = episode ?: 1)
                                )
                            )
                        )
                    )
                )
            } else {
                ScrobbleItem(
                    movie = ScrobbleMovie(
                        title = title,
                        year = year,
                        ids = ids
                    )
                )
            }
            val request = Request.Builder()
                .url("$BASE/scrobble/stop")
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .post(json.encodeToString(ScrobbleItem.serializer(), item).toRequestBody("application/json".toMediaType()))
                .build()
            val resp = okHttpClient.newCall(request).execute()
            ani.sanin.util.Logger.log("Simkl.scrobbleStop: HTTP ${resp.code} type=$type title=$title")
        }
    }

    /** Mark episodes as watched on Simkl (adds to history + updates library status). */
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
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val idsObj = org.json.JSONObject().apply {
                if (tmdbId != null && tmdbId > 0) put("tmdb", tmdbId)
                if (!imdbId.isNullOrBlank()) put("imdb", imdbId)
                if (anilistId != null && anilistId > 0) put("anilist", anilistId)
            }
            val body = if (type == "tv") {
                // AnymeX format: mark episodes 1..current as watched, no watched_at
                val ep = episode ?: 1
                val episodesArr = org.json.JSONArray()
                for (i in 1..ep) episodesArr.put(org.json.JSONObject().put("number", i))
                val seasonObj = org.json.JSONObject().apply {
                    put("number", season ?: 1)
                    put("episodes", episodesArr)
                }
                val showObj = org.json.JSONObject().apply {
                    put("ids", idsObj)
                    put("seasons", org.json.JSONArray().put(seasonObj))
                }
                org.json.JSONObject().put("shows", org.json.JSONArray().put(showObj)).toString()
            } else {
                // AnymeX: movies skip /sync/history entirely; mark completed via add-to-list
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
        anilistId: Int? = null
    ) {
        tryWithSuspend {
            val t = token ?: return@tryWithSuspend
            val idsObj = org.json.JSONObject().apply {
                if (tmdbId != null && tmdbId > 0) put("tmdb", tmdbId)
                if (!imdbId.isNullOrBlank()) put("imdb", imdbId)
                if (anilistId != null && anilistId > 0) put("anilist", anilistId)
            }
            // AnymeX uses "to" field (not "user_status")
            val body = if (type == "tv") {
                val item = org.json.JSONObject().apply {
                    put("ids", idsObj)
                    put("to", status)
                }
                org.json.JSONObject().put("shows", org.json.JSONArray().put(item)).toString()
            } else {
                val item = org.json.JSONObject().apply {
                    put("ids", idsObj)
                    put("to", status)
                }
                org.json.JSONObject().put("movies", org.json.JSONArray().put(item)).toString()
            }
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
            ani.sanin.util.Logger.log("Simkl.setListStatus: HTTP ${resp.code} status=$status title=$title resp=$respBody")
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
            val idsObj = org.json.JSONObject().apply {
                if (tmdbId != null && tmdbId > 0) put("tmdb", tmdbId)
                if (!imdbId.isNullOrBlank()) put("imdb", imdbId)
                if (anilistId != null && anilistId > 0) put("anilist", anilistId)
            }
            // AnymeX uses "to" field (not "user_status")
            val body = if (type == "tv") {
                val item = org.json.JSONObject().apply {
                    put("ids", idsObj)
                    put("to", "watching")
                }
                org.json.JSONObject().put("shows", org.json.JSONArray().put(item)).toString()
            } else {
                val item = org.json.JSONObject().apply {
                    put("ids", idsObj)
                    put("to", "watching")
                }
                org.json.JSONObject().put("movies", org.json.JSONArray().put(item)).toString()
            }
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
        imdbId: String? = null
    ): String? {
        val t = token ?: return null
        return try {
            val items = if (type == "tv") getShowLibrary() else getMovieLibrary()
            items.firstOrNull { item ->
                val ids = item.ids
                ids != null && (
                    (tmdbId != null && ids.tmdb == tmdbId) ||
                    (imdbId != null && ids.imdb == imdbId)
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
                !it.status.equals("completed", ignoreCase = true) &&
                !it.status.equals("dropped", ignoreCase = true)
            }
            val shows = getShowLibrary().filter {
                it.status.equals("current", ignoreCase = true) ||
                it.status.equals("watching", ignoreCase = true)
            }
            val items = movies + shows
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
        if (t == null) {
            ani.sanin.util.Logger.log("Simkl.getLibrary: token is null")
            return null
        }
        return try {
            val request = Request.Builder()
                .url("$BASE/sync/all-items")
                .get()
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            ani.sanin.util.Logger.log("Simkl.getLibrary: HTTP ${response.code} body=${body?.take(200)}")
            if (response.code != 200 || body == null) {
                ani.sanin.logError(Exception("Simkl.getLibrary: HTTP ${response.code}"), snackbar = false)
                return null
            }
            val root = org.json.JSONObject(body)
            val moviesArr = root.optJSONObject("all")?.optJSONArray("movies")
            val showsArr = root.optJSONObject("all")?.optJSONArray("shows")
            val movies = if (moviesArr != null) json.decodeFromString<List<SimklWatchedItem>>(moviesArr.toString()) else emptyList()
            val shows = if (showsArr != null) json.decodeFromString<List<SimklWatchedItem>>(showsArr.toString()) else emptyList()
            SimklLibrary(movies = movies, shows = shows)
        } catch (e: Exception) {
            ani.sanin.logError(e, snackbar = false)
            ani.sanin.util.Logger.log("Simkl.getLibrary: exception ${e.message}")
            null
        }
    }

    /** Get movie library list */
    suspend fun getMovieLibrary(): List<SimklWatchedItem> {
        val t = token ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$BASE/sync/all-items/movies")
                .get()
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            if (response.code != 200) return emptyList()
            ani.sanin.util.Logger.log("Simkl.getMovieLibrary: HTTP ${response.code} body=${body.take(200)}")
            val root = org.json.JSONObject(body)
            val movies = root.optJSONArray("movies") ?: return emptyList()
            ani.sanin.util.Logger.log("Simkl.getMovieLibrary: raw first item=${movies.optJSONObject(0)?.toString()?.take(300)}")
            val parsed = json.decodeFromString<List<SimklWatchedItem>>(movies.toString())
            ani.sanin.util.Logger.log("Simkl.getMovieLibrary: parsed ${parsed.size} items, first title=${parsed.firstOrNull()?.title} poster=${parsed.firstOrNull()?.poster?.take(50)} movie=${parsed.firstOrNull()?.movie != null}")
            parsed
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getMovieLibrary: ${e.message}")
            emptyList()
        }
    }

    /** Get show library list */
    suspend fun getShowLibrary(): List<SimklWatchedItem> {
        val t = token ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("$BASE/sync/all-items/shows")
                .get()
                .addHeader("Authorization", "Bearer $t")
                .addHeader("simkl-api-key", clientId)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            if (response.code != 200) return emptyList()
            ani.sanin.util.Logger.log("Simkl.getShowLibrary: HTTP ${response.code} body=${body.take(200)}")
            val root = org.json.JSONObject(body)
            val shows = root.optJSONArray("shows") ?: return emptyList()
            ani.sanin.util.Logger.log("Simkl.getShowLibrary: raw first item=${shows.optJSONObject(0)?.toString()?.take(300)}")
            val parsed = json.decodeFromString<List<SimklWatchedItem>>(shows.toString())
            ani.sanin.util.Logger.log("Simkl.getShowLibrary: parsed ${parsed.size} items, first title=${parsed.firstOrNull()?.title} poster=${parsed.firstOrNull()?.poster?.take(50)} show=${parsed.firstOrNull()?.show != null}")
            parsed
        } catch (e: Exception) {
            ani.sanin.util.Logger.log("Simkl.getShowLibrary: ${e.message}")
            emptyList()
        }
    }

    // --- Data classes ---

    @Serializable
    data class SimklTokenRequest(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
        @SerialName("redirect_uri") val redirectUri: String,
        val code: String? = null,
        @SerialName("grant_type") val grantType: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("code_verifier") val codeVerifier: String? = null
    )

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
