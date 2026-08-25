package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private suspend fun resolveGet(
    url: String,
    referer: String?,
    accept: String,
): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .header("Accept", accept)
        .apply { referer?.let { header("Referer", it) } }
        .get().build()
    okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        body
    }
}

internal data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val format: VideoType,
    val quality: Int? = null,
    val subtitles: List<Subtitle> = emptyList(),
)

internal data class NamedStream(
    val name: String,
    val stream: ResolvedStream,
)

internal object StreamResolvers {
    private const val FLIX_REFERER = "https://flixcloud.cc/"

    suspend fun gogoPlayer(
        playerUrl: String,
        episodeUrl: String,
        label: String,
        language: String,
    ): List<NamedStream> = withContext(Dispatchers.IO) {
        try {
            val playerBody = resolveGet(playerUrl, episodeUrl, "text/html,application/xhtml+xml,*/*")
            val megavidEmbed = Regex("""src="(https://megavid\.buzz/[^"]+)"""")
                .find(playerBody)?.groupValues?.get(1)
            if (megavidEmbed != null) {
                return@withContext megavid(megavidEmbed, "$label · $language")
            }

            val sourcesMatch = Regex("""var\s+sources\s*=\s*(\[[^\]]+\])\s*;""")
                .find(playerBody)?.groupValues?.get(1)
            if (sourcesMatch != null) {
                val sources = Mapper.json.parseToJsonElement(sourcesMatch).jsonArray
                return@withContext sources.mapNotNull { element ->
                    val source = element as? JsonObject ?: return@mapNotNull null
                    val url = source.string("file") ?: return@mapNotNull null
                    val qualityLabel = source.string("label") ?: source.string("type") ?: "Direct"
                    NamedStream(
                        "$qualityLabel · $language",
                        ResolvedStream(
                            url = url,
                            headers = mapOf("Referer" to playerUrl),
                            format = streamFormat(url, source.string("type")),
                            quality = qualityValue(qualityLabel),
                        ),
                    )
                }
            }

            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(playerBody)?.groupValues?.get(1)
            if (iframe != null) {
                listOf(NamedStream("$label · $language", ResolvedStream(iframe, mapOf("Referer" to playerUrl), VideoType.M3U8)))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Logger.log("Gogo stream resolver error ($label): ${e.message}")
            emptyList()
        }
    }

    private suspend fun megavid(embedUrl: String, name: String): List<NamedStream> = withContext(Dispatchers.IO) {
        try {
            val embedHeaders = mapOf(
                "Referer" to "https://gogoanime.by/player/",
                "Accept" to "text/html,application/xhtml+xml,*/*",
            )
            val embedBody = resolveGet(embedUrl, null, embedHeaders["Accept"]!!)
            val payloadMatch = Regex(
                """<script[^>]+id="player-payload"[^>]*>(.*?)</script>""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(embedBody)?.groupValues?.get(1)
            val payload = payloadMatch?.let { runCatching { Mapper.json.parseToJsonElement(it).jsonObject }.getOrNull() }
            var sourcePath = payload?.string("sourceUrl")
            if (sourcePath.isNullOrBlank()) return@withContext emptyList()

            val tracks = mutableListOf<Subtitle>()
            suspend fun requestSource(url: String): JsonObject? {
                val body = resolveGet(
                    url,
                    embedUrl,
                    "application/json",
                )
                val obj = runCatching { Mapper.json.parseToJsonElement(body).jsonObject }.getOrNull()
                return obj?.takeIf { it.string("status") == "ok" && !it.string("source").isNullOrBlank() }
            }

            val absoluteSource = java.net.URI(embedUrl).resolve(sourcePath).toString()
            var sourceObj = requestSource(absoluteSource)
            if (sourceObj == null) {
                sourceObj = requestSource("$absoluteSource${if ("?" in absoluteSource) "&" else "?"}provider=1")
            }
            if (sourceObj == null) return@withContext emptyList()

            sourceObj.jsonPrimitiveArray("tracks")?.forEach { element ->
                val track = element as? JsonObject ?: return@forEach
                val url = track.string("file") ?: return@forEach
                val label = track.string("label") ?: "Unknown"
                tracks.add(
                    Subtitle(
                        language = label,
                        file = ani.sanin.FileUrl(url, mapOf("Referer" to embedUrl)),
                        type = SubtitleType.VTT,
                    )
                )
            }

            val streamUrl = sourceObj.string("source")!!
            listOf(
                NamedStream(
                    name,
                    ResolvedStream(
                        url = streamUrl,
                        headers = mapOf("Referer" to embedUrl),
                        format = streamFormat(streamUrl, sourceObj.string("type")),
                        subtitles = tracks,
                    ),
                )
            )
        } catch (e: Exception) {
            Logger.log("Megavid resolver error: ${e.message}")
            emptyList()
        }
    }

    suspend fun flixCloud(embedUrl: String): List<NamedStream> = withContext(Dispatchers.IO) {
        try {
            val html = resolveGet(
                embedUrl,
                FLIX_REFERER,
                "text/html,application/xhtml+xml,*/*",
            )
            val seed = html.value("obfuscation_seed") ?: return@withContext emptyList()
            val fields = obfuscationFields(seed)
            val cryptoHtml = html.section(fields.containerName, fields.arrayName, fields.objectName)
            val encryptedKey = cryptoHtml.value(fields.keyField)?.base64Bytes()
                ?: return@withContext emptyList()
            val iv = cryptoHtml.value(fields.ivField)?.base64Bytes()
                ?: return@withContext emptyList()
            val keyFragment2 = html.value(fields.keyFragment2Field)?.base64Bytes()
                ?: return@withContext emptyList()
            val tokenReference = html.value(fields.tokenField) ?: return@withContext emptyList()
            val tokenBody = resolveGet(
                "https://flixcloud.cc/api/m3u8/$tokenReference",
                FLIX_REFERER,
                "application/json",
            )
            val token = Mapper.json.parseToJsonElement(tokenBody).jsonObject
            val videoField = sha256Hex("$tokenReference vid").substring(0, 10)
            val keyField = sha256Hex("$tokenReference key").substring(0, 10)
            val encryptedVideo = token.string(videoField)?.base64Bytes()
                ?: return@withContext emptyList()
            val encryptedKeyFragment = token.string(keyField)?.base64Bytes()
                ?: return@withContext emptyList()

            val mixedKey = mixKeyFragments(
                encryptedKey,
                keyFragment2,
                encryptedKeyFragment,
                seed.substring(0, 8).toLong(16).toInt(),
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val pbeKey = factory.generateSecret(
                PBEKeySpec(seed.toCharArray(), seed.toByteArray(Charsets.UTF_8), 1000, 256)
            )
            val derived = pbeKey.encoded
            repeat(32) { index ->
                derived[index] = (derived[index].toInt() xor seed[index % seed.length].code).toByte()
            }
            val aesKey = MessageDigest.getInstance("SHA-256").digest(derived)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            val streamUrl = String(cipher.doFinal(encryptedVideo), Charsets.UTF_8).trim()
            if (!streamUrl.startsWith("http")) return@withContext emptyList()

            val subtitles = parseFlixSubtitles(html, embedUrl)
            listOf(
                NamedStream(
                    "FlixCloud",
                    ResolvedStream(
                        url = streamUrl,
                        headers = mapOf("Referer" to FLIX_REFERER),
                        format = VideoType.M3U8,
                        subtitles = subtitles,
                    ),
                )
            )
        } catch (e: Exception) {
            Logger.log("FlixCloud resolver error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun mixKeyFragments(
        fragment1: ByteArray,
        fragment2: ByteArray,
        fragment3: ByteArray,
        seed: Int,
    ): ByteArray {
        require(fragment1.size == fragment2.size && fragment1.size == fragment3.size)
        val output = ByteArray(fragment1.size)
        for (index in output.indices) {
            val mixed = (fragment1[index].toInt() xor fragment2[index].toInt() xor
                fragment3[index].toInt() xor 0x33) and 0xff
            val rotated = ((mixed shl 4) and 0xff) or ((mixed and 0xff) ushr 4)
            output[index] = ((rotated xor (((index * 46) + seed) and 0xff))).toByte()
        }
        return output
    }

    private suspend fun sha256Hex(value: String): String = withContext(Dispatchers.IO) {
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class ObfuscationFields(
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val keyField: String,
        val ivField: String,
        val tokenField: String,
        val keyFragment2Field: String,
    )

    private suspend fun obfuscationFields(seed: String): ObfuscationFields = withContext(Dispatchers.IO) {
        var hash = seed
        repeat(3) { index -> hash = sha256Hex(hash + index.toString()) }
        var secondHash = hash
        repeat(3) { index -> secondHash = sha256Hex(secondHash + index.toString()) }
        ObfuscationFields(
            containerName = "cd_${hash.substring(24, 32)}",
            arrayName = "ad_${hash.substring(32, 40)}",
            objectName = "od_${hash.substring(40, 48)}",
            keyField = "kf_${hash.substring(8, 16)}",
            ivField = "ivf_${hash.substring(16, 24)}",
            tokenField = "${hash.substring(48, 64)}_${hash.substring(56, 64)}",
            keyFragment2Field = "${secondHash.substring(0, 16)}_${secondHash.substring(16, 24)}",
        )
    }

    private fun parseFlixSubtitles(html: String, embedUrl: String): List<Subtitle> {
        val regex = Regex("""\{url:"([^"]+)",language:"([^"]+)",format:"([^"]+)"""")
        return regex.findAll(html).distinctBy { it.groupValues[1] }.mapNotNull { match ->
            val (url, languageName, formatText) = match.destructured
            val type = when (formatText.lowercase()) {
                "ass", "ssa" -> SubtitleType.ASS
                "srt" -> SubtitleType.SRT
                else -> SubtitleType.VTT
            }
            Subtitle(
                language = languageName,
                file = ani.sanin.FileUrl(url, mapOf("Referer" to embedUrl)),
                type = type,
            )
        }.toList()
    }

    private fun streamFormat(url: String, declaredType: String?): VideoType = when {
        declaredType.equals("hls", true) || url.contains(".m3u8", true) -> VideoType.M3U8
        declaredType.equals("dash", true) || url.contains(".mpd", true) -> VideoType.DASH
        else -> VideoType.CONTAINER
    }

    private fun qualityValue(label: String): Int? =
        Regex("""(\d{3,4})p?""").find(label)?.groupValues?.get(1)?.toIntOrNull()

    private fun String.base64Bytes(): ByteArray = Base64.getDecoder().decode(this)

    private fun String.value(key: String): String? {
        val escaped = Regex.escape(key)
        return Regex("(?:^|[,{}\\[])(?:\"$escaped\"|$escaped):\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(this)?.groupValues?.get(1)
    }

    private fun String.section(vararg keys: String): String {
        val key = { value: String -> "(?:\"${Regex.escape(value)}\"|${Regex.escape(value)})" }
        val pattern = "${key(keys[0])}\\:\\{${key(keys[1])}\\:\\[\\{${key(keys[2])}\\:\\{"
        return Regex("""$pattern(.*?)\\}\\]\\}""").find(this)?.groupValues?.get(1).orEmpty()
    }
}

internal fun JsonObject.string(key: String): String? =
    this[key]?.let { value ->
        runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { content ->
            value !is JsonArray && value !is JsonObject
        }
    }

internal fun JsonObject.jsonPrimitiveArray(key: String): JsonArray? =
    this[key] as? JsonArray
