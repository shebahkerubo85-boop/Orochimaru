package ani.sanin.connections.subtitles

import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OpenSubtitles {

    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "Sanin v3.2.2"
    private const val API_KEY = "sMaSHqhfU08qaUehns7TOLJbbEg8O3D4"

    suspend fun search(imdbId: String, season: Int, episode: Int, queryText: String? = null): List<StremioSub> {
        return withContext(Dispatchers.IO) {
            try {
                val languages = PrefManager.getVal<Set<String>>(PrefName.OnlineSubtitleLanguages)
                    .joinToString(",") { it.take(2).lowercase() }

                val urls = mutableListOf<String>()
                urls.add("$BASE_URL/subtitles?imdb_id=$imdbId&languages=$languages&type=episode&season_number=$season&episode_number=$episode")
                if (!queryText.isNullOrBlank()) {
                    // imdb_id searches come back empty for many shows, so also try a text
                    // query (same trick as Dantotsu) to surface release-named subtitles.
                    val queryUrl = buildString {
                        append("$BASE_URL/subtitles?query=${URLEncoder.encode(queryText, "UTF-8")}&languages=$languages")
                        if (episode > 0) append("&episode_number=$episode")
                    }
                    urls.add(queryUrl)
                }

                val results = mutableListOf<StremioSub>()
                for (url in urls) {
                    try {
                        Logger.log("OpenSubtitles: Searching $url")

                        val request = Request.Builder()
                            .url(url)
                            .header("Api-Key", API_KEY)
                            .header("User-Agent", USER_AGENT)
                            .header("Content-Type", "application/json")
                            .build()

                        val response = okHttpClient.newCall(request).execute()
                        if (!response.isSuccessful) {
                            Logger.log("OpenSubtitles: Search failed HTTP ${response.code} for $url")
                            continue
                        }

                        val body = response.body?.string() ?: continue
                        val searchResult = Mapper.json.decodeFromString<OpenSubtitlesSearchResponse>(body)

                        val mapped = searchResult.data.mapNotNull { item ->
                            try {
                                val file = item.attributes.files.firstOrNull() ?: return@mapNotNull null
                                val downloadUrl = downloadSubtitle(file.fileId, API_KEY) ?: return@mapNotNull null
                                val fileName = file.fileName ?: item.attributes.release ?: item.attributes.language
                                Logger.log("OpenSubtitles: Got link for file ${file.fileId} → $downloadUrl")

                                StremioSub(
                                    id = item.id,
                                    url = downloadUrl,
                                    lang = item.attributes.language,
                                    label = fileName,
                                    source = "opensubtitles"
                                )
                            } catch (_: Exception) { null }
                        }
                        results.addAll(mapped.take(12))
                        if (results.isNotEmpty()) break
                    } catch (e: Exception) {
                        Logger.log("OpenSubtitles: url failed $url -> ${e.message}")
                    }
                }
                results
            } catch (e: Exception) {
                Logger.log("OpenSubtitles: Error - ${e.message}")
                emptyList()
            }
        }
    }

    private fun downloadSubtitle(fileId: Int, apiKey: String): String? {
        return try {
            val json = Mapper.json.encodeToString(DownloadRequest(fileId))
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/download")
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.log("OpenSubtitles: Download failed HTTP ${response.code} for file $fileId")
                return null
            }

            val body = response.body?.string() ?: return null
            val downloadResult = Mapper.json.decodeFromString<DownloadResponse>(body)
            downloadResult.link
        } catch (e: Exception) {
            Logger.log("OpenSubtitles: Download error - ${e.message}")
            null
        }
    }
}

@Serializable
data class OpenSubtitlesSearchResponse(
    val data: List<OpenSubtitlesItem> = emptyList()
)

@Serializable
data class OpenSubtitlesItem(
    val id: String,
    val attributes: OpenSubtitlesAttributes
)

@Serializable
data class OpenSubtitlesAttributes(
    val language: String,
    val release: String? = null,
    val files: List<OpenSubtitlesFile> = emptyList()
)

@Serializable
data class OpenSubtitlesFile(
    @SerialName("file_id") val fileId: Int,
    @SerialName("file_name") val fileName: String? = null
)

@Serializable
data class DownloadRequest(
    @SerialName("file_id") val fileId: Int
)

@Serializable
data class DownloadResponse(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null
)
