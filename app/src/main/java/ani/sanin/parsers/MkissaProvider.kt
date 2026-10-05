package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

/**
 * Native MKissa client.
 *
 * Search, details and the episode list are plain GraphQL and need no handshake. Playback is the
 * awkward part: the site's `episode` query only answers once the call is signed with its
 * "aa-crypto" scheme (see [MkissaCrypto]) and the result itself arrives as an AES-GCM blob that
 * hides the `sourceUrls` array. Each of those `sourceUrl` values is XOR-obfuscated on top of that,
 * and the interesting ones are MKissa's own `/apivtwo/...` CDN links, which need one more JSON hop
 * (`/clock.json`) before they become a playable m3u8/mp4.
 */
class MkissaProvider : NativeAnimeParser() {

    override val name = "MKissa"
    override val saveName = "MKissa"
    override val defaultBaseUrl = "https://mkissa.to"
    override val knownServers = listOf("MKissa")

    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    private val siteBase: String get() = baseUrl.trimEnd('/')

    /** The API is always api.mkissa.net; only the page host (which keys the handshake) is configurable. */
    private val apiBase = "https://api.mkissa.net"

    private val graphqlOrigin = "https://youtu-chan.com"

    private class Material(
        val key: SecretKeySpec,
        val epoch: Long,
        val buildId: String,
        val mask: ByteArray,
        val config: MkissaCrypto.Config,
    )

    @Volatile private var material: Material? = null
    @Volatile private var buildInfo: MkissaBundle.BuildInfo? = null

    // ============================== search & metadata ==============================

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        val json = runCatching {
            gql(SEARCH_QUERY, buildJsonObject {
                put("search", buildJsonObject { put("query", query) })
                put("limit", 26)
                put("page", 1)
            })
        }.getOrElse {
            Logger.log("MKissa: search FAILED for '$query': ${it.message}")
            return@withContext emptyList()
        }

        val shows = (json["data"] as? JsonObject)?.get("shows") as? JsonObject
        if (shows == null) {
            Logger.log("MKissa: search returned no 'shows' for '$query': ${json.errors()}")
            return@withContext emptyList()
        }
        val total = ((shows["pageInfo"] as? JsonObject)?.get("total") as? JsonPrimitive)?.intOrNull

        val results = (shows["edges"] as? JsonArray).orEmpty().mapNotNull { el ->
            val edge = el as? JsonObject ?: return@mapNotNull null
            val id = edge.str("_id") ?: return@mapNotNull null
            val title = edge.str("name") ?: return@mapNotNull null
            ShowResponse(
                name = title,
                link = id,
                coverUrl = FileUrl(edge.str("thumbnail").orEmpty()),
                otherNames = listOfNotNull(edge.str("englishName"), edge.str("nativeName")).filter { it != title },
                total = total,
                extra = mutableMapOf("showId" to id),
            )
        }
        Logger.log("MKissa: search '$query' -> ${results.size}/${total ?: "?"} result(s)")
        results
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> =
        withContext(Dispatchers.IO) {
            val showId = extra?.get("showId") ?: animeLink
            if (showId.isBlank()) {
                Logger.log("MKissa: loadEpisodes aborted, no showId (link='$animeLink')")
                return@withContext emptyList()
            }

            val json = runCatching { gql(EPISODES_QUERY, buildJsonObject { put("_id", showId) }) }
                .getOrElse {
                    Logger.log("MKissa: loadEpisodes FAILED for $showId: ${it.message}")
                    return@withContext emptyList()
                }
            val show = (json["data"] as? JsonObject)?.get("show") as? JsonObject
            if (show == null) {
                Logger.log("MKissa: loadEpisodes FAILED for $showId, no show in response: ${json.errors()}")
                return@withContext emptyList()
            }
            val available = show["availableEpisodesDetail"] as? JsonObject
            if (available == null) {
                Logger.log("MKissa: loadEpisodes FAILED for $showId, no availableEpisodesDetail")
                return@withContext emptyList()
            }

            // Both lists arrive as descending string episode numbers.
            val sub = available.numbers("sub")
            val dub = available.numbers("dub")
            val numbers = (sub + dub).distinct().sortedWith(
                compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })
            )
            if (numbers.isEmpty()) {
                Logger.log("MKissa: loadEpisodes returned 0 episodes for $showId")
                return@withContext emptyList()
            }
            Logger.log(
                "MKissa: loadEpisodes $showId -> ${numbers.size} episode(s) " +
                    "(sub=${sub.size}, dub=${dub.size}) range=${numbers.first()}..${numbers.last()}"
            )

            numbers.map { num ->
                val hasSub = sub.contains(num)
                val hasDub = dub.contains(num)
                val epExtra = mutableMapOf<String, String>()
                extra?.forEach { (k, v) -> epExtra[k] = v }
                epExtra["showId"] = showId
                epExtra["number"] = num
                epExtra["sub"] = hasSub.toString()
                epExtra["dub"] = hasDub.toString()
                // Dub preference wins when the episode actually has a dub, otherwise fall back to sub.
                val audio = when {
                    selectDub && hasDub -> "dub"
                    hasSub -> "sub"
                    else -> "dub"
                }
                epExtra["audio"] = audio
                Episode(
                    number = num,
                    link = num,
                    title = "Episode $num",
                    extra = epExtra,
                )
            }
        }

    // ============================== playback ==============================

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode,
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        val showId = extra?.get("showId")
        if (showId.isNullOrBlank()) {
            Logger.log("MKissa: loadVideoServers aborted, no showId (link='$episodeLink')")
            return@withContext emptyList()
        }
        val audio = extra?.get("audio") ?: "sub"
        val translation = if (audio == "dub") "dub" else "sub"

        // A rejected signature just means the site rotated its key material; rebuild once and retry
        // before giving up, so a normal key rotation is invisible to the user.
        val episode = runCatching { signedEpisode(showId, translation, episodeLink) }
            .recoverCatching { first ->
                Logger.log("MKissa: retrying ep $episodeLink with fresh material: ${first.message}")
                signedEpisode(showId, translation, episodeLink, forceRefresh = true)
            }
            .getOrElse {
                Logger.log(
                    "MKissa: loadVideoServers FAILED for $showId ep $episodeLink ($translation): ${it.message}"
                )
                return@withContext emptyList()
            }

        val sources = episode.sources
        if (sources.isEmpty()) {
            Logger.log("MKissa: loadVideoServers FAILED for $showId ep $episodeLink ($translation), no usable sourceUrls")
            return@withContext emptyList()
        }
        Logger.log("MKissa: ep $episodeLink ($translation) -> ${sources.size} source(s)")

        val servers = linkedMapOf<String, VideoServer>()
        val seenSources = mutableSetOf<String>()
        sources.forEach { source ->
            val raw = source.url
            // MKissa often lists the same server more than once; de-dupe on the real source url.
            if (!seenSources.add(raw)) return@forEach

            val name = source.name.ifBlank { source.type.ifBlank { "MKissa" } }
            val headers = mutableMapOf("Referer" to "$siteBase/", "Origin" to siteBase)

            if (raw.startsWith(INTERNAL_PREFIX)) {
                val resolved = resolveInternal(raw, name)
                if (resolved.isEmpty()) {
                    Logger.log("MKissa: internal source '$name' resolved to 0 playable url(s) from $raw")
                }
                resolved.forEach { video ->
                    servers["${video.label}|${video.url}"] = VideoServer(
                        name = video.label,
                        embed = FileUrl(video.url, video.headers),
                        extraData = if (video.subtitles.isEmpty()) {
                            null
                        } else {
                            mapOf("subtitles" to Mapper.json.encodeToString(
                                JsonElement.serializer(),
                                JsonArray(video.subtitles.map { it.toJson() }),
                            ))
                        },
                    )
                }
            } else {
                // Third party embed: only keep it if something can actually pull a media URL out.
                val embedded = runCatching { EmbedRouter.resolve(raw, "$siteBase/") }.getOrNull()
                val media = embedded?.urls?.firstOrNull { it.isMedia() }
                if (media != null) {
                    val map = headers.toMutableMap()
                    map.putAll(embedded.headers)
                    servers[media] = VideoServer(name, FileUrl(media, map))
                } else {
                    Logger.log("MKissa: skipping unplayable host for $name -> $raw")
                }
            }
        }

        Logger.log(
            "MKissa: server sheet for ep $episodeLink ($translation): ${servers.size} playable -> " +
                servers.values.joinToString { it.name }
        )
        if (servers.isEmpty()) {
            Logger.log(
                "MKissa: loadVideoServers produced 0 servers for ep $episodeLink ($translation); " +
                    "checked ${sources.size} source(s)"
            )
        }
        servers.values.toList()
    }

    /**
     * Wraps the default extractor purely so playback shows up in the log: MKissa hands out signed
     * CDN urls that can still 403 or 404 by the time the player asks for them, and without this the
     * only symptom is a silently empty server sheet.
     */
    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        val inner = NativeVideoExtractor(server)
        return object : VideoExtractor() {
            override val server: VideoServer = server
            override suspend fun extract(): VideoContainer = try {
                val container = inner.extract()
                Logger.log(
                    "MKissa: play '${server.name}' -> ${container.videos.size} video(s), " +
                        "${container.subtitles.size} subtitle(s), ${container.audioTracks.size} audio track(s) " +
                        "from ${server.embed.url}"
                )
                container
            } catch (e: Exception) {
                Logger.log("MKissa: play FAILED '${server.name}' (${server.embed.url}): ${e.message}")
                throw e
            }
        }
    }

    /**
     * MKissa's own CDN links need one JSON hop (`/clock.json`) before they become playable media.
     * Each format wants different headers, so every candidate carries its own.
     */
    private fun resolveInternal(raw: String, name: String): List<InternalVideo> {
        val url = apiBase + raw.replace("/clock?", "/clock.json?")
        val body = runCatching {
            rawGet(url, mapOf("User-Agent" to USER_AGENT, "Referer" to "$siteBase/", "Accept" to "*/*"))
        }.getOrElse {
            Logger.log("MKissa: clock.json request failed for '$name': ${it.message}")
            return emptyList()
        }
        val root = runCatching { Mapper.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val links = root?.get("links") as? JsonArray
        if (links == null) {
            Logger.log("MKissa: clock.json for '$name' had no 'links': ${body.take(200)}")
            return emptyList()
        }

        val playlistHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "$siteBase/",
            "Origin" to siteBase,
        )
        // The DASH CDN answers 403 to anything carrying a Referer.
        val dashHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*")

        val out = mutableListOf<InternalVideo>()
        links.forEach { el ->
            val link = el as? JsonObject ?: return@forEach
            val media = link.str("link") ?: return@forEach
            val res = link.str("resolutionStr").orEmpty()
            val label = listOf(name, res).filter { it.isNotBlank() }.joinToString(" ")

            val subs = (link["subtitles"] as? JsonArray).orEmpty().mapNotNull { s ->
                val o = s as? JsonObject ?: return@mapNotNull null
                val src = o.str("src") ?: return@mapNotNull null
                SubData(url = src, language = o.str("lang").orEmpty().ifBlank { "Unknown" })
            }

            when {
                link.bool("mp4") ->
                    out += InternalVideo("$label MP4", media, playlistHeaders, subs)

                link.bool("hls") ->
                    out += InternalVideo("$label HLS", media, playlistHeaders, subs)

                link.bool("dash") ->
                    out += InternalVideo("$label DASH", media, dashHeaders, subs)

                link.bool("crIframe") -> {
                    val streams = (link["portData"] as? JsonObject)?.get("streams") as? JsonArray
                    streams.orEmpty().forEach { st ->
                        val stream = st as? JsonObject ?: return@forEach
                        val su = stream.str("url") ?: return@forEach
                        val hard = stream.str("hardsub_lang").orEmpty()
                        val suffix = if (hard.isBlank()) "" else " [hardsub $hard]"
                        when (stream.str("format")) {
                            "adaptive_hls" -> out += InternalVideo(
                                "$label AC-HLS$suffix", su, playlistHeaders, subs
                            )
                            "adaptive_dash" -> out += InternalVideo(
                                "$label AC-DASH$suffix", su, dashHeaders, subs
                            )
                            else -> Unit
                        }
                    }
                }

                else -> Unit
            }
        }
        return out.distinctBy { it.url }
    }

    // ============================== signed GraphQL ==============================

    private fun signedEpisode(
        showId: String,
        translation: String,
        number: String,
        forceRefresh: Boolean = false,
    ): EpisodePayload {
        val mat = if (forceRefresh) refreshMaterial() else material()
        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", translation)
            // The site's query types this as Float!, so it has to go over the wire as a number.
            put("episodeNum", number.toFloatOrNull() ?: 0f)
        }
        val hash = MkissaCrypto.sha256Hex(STREAM_QUERY)
        val aaReq = MkissaCrypto.buildAaReq(mat.key, mat.epoch, mat.buildId, hash, LANE_EPISODE)
        val extensions = buildJsonObject {
            put("persistedQuery", buildJsonObject {
                put("version", 1)
                put("sha256Hash", hash)
            })
            put("k", LANE_EPISODE)
            put("aaReq", aaReq)
        }

        // Try the registered persisted query first, then register it once if the server has never
        // seen the hash.
        var json = runCatching {
            apqGet(variables, extensions, mat)
        }.getOrElse { error ->
            Logger.log("MKissa: apq get failed: ${error.message}")
            null
        }

        if (json != null && json.isPersistedQueryMissing()) {
            Logger.log("MKissa: registering persisted query")
            runCatching { apqPost(STREAM_QUERY, variables, extensions, mat) }
            json = runCatching { apqGet(variables, extensions, mat) }.getOrNull()
        }

        val obj = json ?: throw IOException("MKissa: no response for episode query")
        val errors = (obj["errors"] as? JsonArray).orEmpty()
        val firstMessage = (errors.firstOrNull() as? JsonObject)?.str("message").orEmpty()
        if (firstMessage.isNotBlank()) {
            if (firstMessage.contains(CAPTCHA_ERROR) || firstMessage.contains("captcha", ignoreCase = true)) {
                throw IOException("MKissa is rate limiting this device (NEED_CAPTCHA); streams return on their own later")
            }
            Logger.log("MKissa: GraphQL error: $firstMessage")
        }

        val payload = (obj["data"] as? JsonObject)?.get("episode") as? JsonObject
            ?: throw IOException("MKissa: episode missing from response")
        return EpisodePayload(
            sources = (payload["sourceUrls"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                Source(
                    url = decodeSource(o.str("sourceUrl").orEmpty()),
                    type = o.str("type").orEmpty(),
                    name = o.str("sourceName").orEmpty(),
                )
            }.filter { it.url.isNotBlank() }
        )
    }

    private fun apqGet(variables: JsonObject, extensions: JsonObject, mat: Material): JsonObject {
        val url = "$apiBase/api?variables=${encode(Mapper.json.encodeToString(JsonElement.serializer(), variables))}" +
            "&extensions=${encode(Mapper.json.encodeToString(JsonElement.serializer(), extensions))}"
        return postJson(url, "", streamHeaders(mat), mat)
    }

    private fun apqPost(
        query: String,
        variables: JsonObject,
        extensions: JsonObject,
        mat: Material,
    ): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
            put("extensions", extensions)
        }
        return postJson(
            "$apiBase/api",
            Mapper.json.encodeToString(JsonElement.serializer(), body),
            streamHeaders(mat),
            mat,
        )
    }

    private fun streamHeaders(mat: Material): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Content-Type" to "application/json",
        "Origin" to graphqlOrigin,
        "Referer" to "$graphqlOrigin/",
        "x-build-id" to mat.buildId,
    )

    private fun gql(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val text = rawPostJson(
            "$apiBase/api",
            Mapper.json.encodeToString(JsonElement.serializer(), body),
            mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Content-Type" to "application/json",
                "Origin" to graphqlOrigin,
                "Referer" to "$graphqlOrigin/",
            ),
        )
        return runCatching { Mapper.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("MKissa: bad GraphQL response")
    }

    /**
     * Both the GraphQL `data` block and the streamed `incremental` blobs arrive encrypted; the
     * wrapper's `err` field is the only plaintext.
     */
    private fun postJson(url: String, body: String, headers: Map<String, String>, mat: Material): JsonObject {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(body.toRequestBody(JSON_MEDIA)).build()
        val text = okHttpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
        val wrapper = runCatching { Mapper.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IOException("MKissa: bad response body")
        val errText = (wrapper["err"] as? JsonPrimitive)?.contentOrNull
        val encrypted = wrapper.str("data")
        if (encrypted.isNullOrBlank()) {
            if (!errText.isNullOrBlank()) throw IOException("MKissa: $errText")
            return wrapper
        }
        val plain = MkissaCrypto.decrypt(encrypted, mat.key)
            ?: throw IOException("MKissa: could not decrypt response")
        return runCatching { Mapper.json.parseToJsonElement(plain) as? JsonObject }.getOrNull()
            ?: throw IOException("MKissa: bad decrypted response")
    }

    // ============================== crypto material ==============================

    private fun material(): Material {
        material?.let { return it }
        synchronized(this) {
            material?.let { return it }
            val info = resolveBuild()
            val mask = MkissaCrypto.deriveMask(info.buildId, info.seeds, info.config)
                ?: throw IOException("MKissa: could not derive mask from build ${info.buildId}")
            val group = keyGroup(siteBase)
            val fresh = bootstrap(mask, info, group)
            material = fresh
            return fresh
        }
    }

    /**
     * Drops cached material and rebuilds it, used when the server rejects our signature.
     *
     * A rejected signature means the epoch moved, not that the site redeployed, so the scraped
     * build is deliberately kept: re-crawling here would pay the bundle crawl again on the retry
     * path for no benefit.
     */
    private fun refreshMaterial(): Material {
        material = null
        return material()
    }

    private fun bootstrap(mask: ByteArray, info: MkissaBundle.BuildInfo, group: String): Material {
        val host = siteBase.toHttpUrl().host
        val epochs = MkissaCrypto.epochCandidates() + MkissaCrypto.skewedEpochCandidates()
        var lastError: Exception? = null
        for (epoch in epochs.distinct()) {
            val token = MkissaCrypto.bootToken(
                mask = mask,
                buildId = info.buildId,
                epoch = epoch,
                keyGroup = group,
                refererHost = host,
                lane = LANE_EPISODE,
                cfg = info.config,
            )
            val url = "$apiBase/client-crypto/v1/bootstrap?buildId=${encode(info.buildId)}&k=$LANE_EPISODE"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "x-build-id" to info.buildId,
                "x-aa-boot" to token,
                "Origin" to siteBase,
                "Referer" to "$siteBase/",
            )
            val text = runCatching { rawGet(url, headers) }.getOrElse {
                lastError = it as? Exception ?: IOException(it.message)
                continue
            }
            val obj = runCatching { Mapper.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: continue
            val partB = obj.str("partB")
            val serverEpoch = (obj["epoch"] as? JsonPrimitive)?.let { it.longOrNullCompat() }
            if (partB.isNullOrBlank()) {
                lastError = IOException("bootstrap: no partB in $text")
                continue
            }
            val partBytes = runCatching { android.util.Base64.decode(partB, android.util.Base64.DEFAULT) }
                .getOrElse { continue }
            val epochUsed = serverEpoch ?: epoch
            Logger.log("MKissa: bootstrap ok epoch=$epochUsed build=${info.buildId}")
            return Material(
                key = MkissaCrypto.deriveKey(mask, partBytes),
                epoch = epochUsed,
                buildId = info.buildId,
                mask = mask,
                config = info.config,
            )
        }
        throw lastError ?: IOException("MKissa: bootstrap failed for every epoch candidate")
    }

    /** The site keys its handshake by which "family" of host is asking. */
    private fun keyGroup(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        return when {
            h in listOf("mkissa.to", "localhost", "127.0.0.1") -> "mkissa"
            h.startsWith("192.168.") -> "mirror"
            h in listOf("youtu-chan.com", "isekai2nd.com") -> "mirror"
            else -> "mkissa"
        }
    }

    /**
     * Resolves the per-build crypto material, preferring a live scrape of the site's crypto chunk.
     *
     * The scrape only exists to survive the site rotating its build, so every failure path lands on
     * [MkissaBundle.KNOWN_GOOD] instead of propagating: the API host is frequently reachable even
     * when the CDN serving the bundle is not, and a missing server sheet is a worse outcome than a
     * slightly stale build.
     */
    private fun resolveBuild(): MkissaBundle.BuildInfo {
        buildInfo?.let { return it }
        synchronized(this) {
            buildInfo?.let { return it }
            val scraped = runCatching { crawlBundle() }.getOrElse {
                Logger.log("MKissa: bundle scrape failed (${it.message}), using build ${MkissaBundle.KNOWN_GOOD.buildId}")
                null
            }
            val info = scraped ?: MkissaBundle.KNOWN_GOOD
            Logger.log("MKissa: crypto material from build ${info.buildId} (${if (scraped != null) "scrape" else "fallback"})")
            buildInfo = info
            return info
        }
    }

    /** Walks the SvelteKit entry bundle to the chunk that carries the crypto implementation. */
    private fun crawlBundle(): MkissaBundle.BuildInfo {
        val html = rawGet("$siteBase/", mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,*/*"), crawl = true)
        val entryPath = MkissaBundle.APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)
            ?: throw IOException("could not find the app entry in the site HTML")
        // The site references its entry chunk by absolute CDN url, but keep the relative case working
        // in case that ever changes.
        val entryUrl = when {
            entryPath.startsWith("http://") || entryPath.startsWith("https://") -> entryPath
            else -> siteBase.toHttpUrl().resolve(entryPath.removePrefix("/"))?.toString()
        } ?: throw IOException("bad entry path $entryPath")
        Logger.log("MKissa: entry bundle $entryUrl")
        val entryJs = rawGet(
            entryUrl,
            mapOf("User-Agent" to USER_AGENT, "Referer" to "$siteBase/"),
            crawl = true,
        )

        val pool = Executors.newFixedThreadPool(CRAWL_THREADS)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CRAWL_BUDGET_MS)
            val visited = mutableSetOf(entryUrl)
            var frontier = listOf(entryUrl to entryJs)
            var hop = 0
            while (hop < MAX_BUNDLE_HOPS) {
                // Anything already in hand that carries the marker wins immediately.
                for ((url, js) in frontier) {
                    if (!js.contains(MkissaBundle.CRYPTO_CHUNK_MARKER)) continue
                    val info = MkissaBundle.parse(js)
                    if (info != null) {
                        Logger.log("MKissa: build ${info.buildId} from ${url.substringAfterLast('/')} (hop $hop)")
                        return info
                    }
                    Logger.log("MKissa: crypto chunk ${url.substringAfterLast('/')} had no usable build info")
                }

                val next = collectChunkUrls(frontier, visited)
                if (next.isEmpty()) break
                Logger.log("MKissa: bundle hop ${hop + 1}, fetching ${next.size} chunk(s)")

                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) throw IOException("crawl budget exhausted before hop ${hop + 1}")

                // Concurrent, because the CDN answers most requests fast and stalls the rest: a
                // serial loop spends N x timeout on a handful of dead edges, which is what turned a
                // few seconds of work into an hour of silence.
                val futures = next.map { url ->
                    pool.submit(Callable { fetchCrawlChunk(url) })
                }
                val fetched = ArrayList<Pair<String, String>>(next.size)
                for (future in futures) {
                    val got = try {
                        future.get(left.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                    } catch (e: Exception) {
                        Logger.log("MKissa: chunk fetch gave up (${e.javaClass.simpleName})")
                        continue
                    }
                    if (got?.second != null) fetched += got.first to got.second!!
                }
                frontier = fetched
                hop++
            }
            throw IOException("walked ${visited.size} bundle file(s) without finding the crypto chunk")
        } finally {
            pool.shutdownNow()
        }
    }

    /** Best-effort body fetch for a crawl target; a miss is normal, not exceptional. */
    private fun fetchCrawlChunk(url: String): Pair<String, String?>? =
        try {
            url to rawGet(url, mapOf("User-Agent" to USER_AGENT, "Referer" to "$siteBase/"), crawl = true)
        } catch (e: Exception) {
            Logger.log("MKissa: chunk ${url.substringAfterLast('/')} failed (${e.message})")
            null
        }

    /**
     * Next round of chunk urls, shared chunks first.
     *
     * The crypto code lives in `immutable/chunks/`, while `immutable/nodes/` holds the per-route
     * bundles. The entry references 145 files, and the crypto chunk was the 38th of 38 shared ones,
     * so ordering by directory is the difference between finding it in the first round and digging
     * through hundreds of irrelevant files. Refs are already relative to the file that imports them
     * and must be resolved as written — inventing a second `../` reading produced 290 candidates
     * instead of 145, half of them guaranteed 404s.
     */
    private fun collectChunkUrls(
        frontier: List<Pair<String, String>>,
        visited: MutableSet<String>,
    ): List<String> {
        val shared = LinkedHashSet<String>()
        val other = LinkedHashSet<String>()
        for ((url, js) in frontier) {
            val base = runCatching { url.toHttpUrl() }.getOrNull() ?: continue
            for (ref in MkissaBundle.chunkRefs(js)) {
                val resolved = base.resolve(ref)?.toString() ?: continue
                if (!visited.add(resolved)) continue
                if (resolved.contains("/chunks/")) shared += resolved else other += resolved
            }
        }
        return (shared + other).take(MAX_CRAWL_FETCHES)
    }

    // ============================== sourceUrl obfuscation ==============================

    /**
     * Each `sourceUrl` is hex bytes masked with a single-byte XOR. The prefix tells us which key,
     * and unprefixed values have to be brute-forced.
     */
    private fun decodeSource(value: String): String {
        if (value.isBlank()) return value
        val (payload, keyType) = when {
            value.startsWith("--") -> value.substring(2) to 3
            value.startsWith("#-") -> value.substring(2) to 2
            value.startsWith("##") -> value.substring(2) to 1
            value.startsWith("-#") -> value.substring(2) to 4
            value.startsWith("#") -> value.substring(1) to 0
            else -> value to null
        }
        if (payload.length % 2 != 0) return value
        val size = payload.length / 2
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            val hi = payload[i * 2].digitToIntOrNull(16) ?: return value
            val lo = payload[i * 2 + 1].digitToIntOrNull(16) ?: return value
            bytes[i] = ((hi shl 4) or lo).toByte()
        }

        if (keyType == null) {
            for (mask in XOR_MASKS) {
                val decoded = String(CharArray(size) { i -> ((bytes[i].toInt() and 0xFF) xor mask).toChar() })
                if (decoded.contains("/clock") || decoded.contains("http")) return decoded
            }
            return value
        }
        val mask = XOR_MASKS.getOrNull(keyType) ?: return value
        return String(CharArray(size) { i -> ((bytes[i].toInt() and 0xFF) xor mask).toChar() })
    }

    // ============================== http helpers ==============================

    private fun rawGet(url: String, headers: Map<String, String>, crawl: Boolean = false): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> if (v.isNotBlank()) header(k, v) }
        }.get().build()
        val client = if (crawl) crawlClient else okHttpClient
        client.newCall(request).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url")
            return body
        }
    }

    private fun rawPostJson(url: String, body: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.post(body.toRequestBody(JSON_MEDIA)).build()
        okHttpClient.newCall(request).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("HTTP ${it.code} for $url: ${text.take(200)}")
            return text
        }
    }

    // ============================== small helpers ==============================

    private class EpisodePayload(val sources: List<Source>)

    private class Source(val url: String, val type: String, val name: String)

    /** A single playable media url produced by the internal CDN resolver. */
    private class InternalVideo(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        val subtitles: List<SubData>,
    )

    private fun SubData.toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("language", language)
        put("type", type)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.numbers(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.isPersistedQueryMissing(): Boolean =
        (this["errors"] as? JsonArray).orEmpty().any {
            val m = (it as? JsonObject)?.str("message").orEmpty()
            m.contains("PersistedQueryNotFound") || m.contains("PERSISTED_QUERY_NOT_FOUND")
        }

    /** Compact error summary for the log, so a failure is diagnosable without a debugger. */
    private fun JsonObject.errors(): String =
        (this["errors"] as? JsonArray).orEmpty().joinToString { e ->
            val o = e as? JsonObject
            buildString {
                append(o?.str("message") ?: e.toString())
                o?.get("path")?.let { append(" @ ").append(it) }
            }
        }.ifBlank { this.toString() }

    private fun JsonPrimitive.longOrNullCompat(): Long? =
        contentOrNull?.toLongOrNull() ?: floatOrNull?.toLong()

    private fun String.isMedia(): Boolean =
        contains(".m3u8", true) || contains(".mp4", true) || contains(".mpd", true) ||
            contains("master.m3u8", true)

    companion object {
        private const val LANE_EPISODE = "k7"
        private const val INTERNAL_PREFIX = "/apivtwo/"
        private const val CAPTCHA_ERROR = "NEED_CAPTCHA"

        /** entry -> nodes -> chunks is all that is needed in practice; the cap is a safety net. */
        private const val MAX_BUNDLE_HOPS = 3
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * The shared client retries a stalled connect twice at 30s each and is capped at two
         * minutes per call, so a CDN that accepts the socket and then goes quiet costs ~90s before
         * anything is logged. Bundle scraping is a nicety, not a prerequisite, so it gets its own
         * short leash and falls back to [MkissaBundle.KNOWN_GOOD].
         *
         * `newBuilder()` inherits application interceptors, and the shared [RetryInterceptor] alone
         * stretched one chunk to 17s here, so they come off. A dead edge must fail in the time this
         * client promises, or the crawl budget below means nothing.
         */
        private val crawlClient = okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .apply { interceptors().clear() }
            .build()

        /** Chunk fetches run concurrently; the CDN is fast on most edges and dead on a few. */
        private const val CRAWL_THREADS = 8

        /** One round, one timeout. Chunks are prioritised so the crypto chunk is in round one. */
        private const val MAX_CRAWL_FETCHES = 48

        /** Wall-clock ceiling for the whole scrape before it gives up and falls back. */
        private const val CRAWL_BUDGET_MS = 25_000L

        private val XOR_MASKS = listOf(
            "allanimenews",
            "1234567890123456789",
            "1234567890123456789012345",
            "s5feqxw21",
            "feqx1",
        ).map { key -> key.fold(0) { mask, ch -> mask xor ch.code } }

        /**
         * Kept byte-for-byte identical to the site's own copy of this query: the aaReq signature
         * covers SHA-256 of the query text, so reformatting it changes the hash.
         */
        private val STREAM_QUERY = """
            query(${'$'}showId: String!, ${'$'}translationType: String!, ${'$'}episodeNum: Float!) {
              episode(showId: ${'$'}showId, translationType: ${'$'}translationType, episodeNum: ${'$'}episodeNum) {
                id
                episodeInfo {
                  vidPath
                  vidPathAlt
                  vidSize
                  vidDuration
                }
                uploadDate
                sourceUrls {
                  sourceUrl
                  type
                  sourceName
                  priority
                }
                show {
                  _id
                }
              }
            }
        """.trimIndent()

        private val SEARCH_QUERY = """
            query(${'$'}search: SearchInput!, ${'$'}limit: Int, ${'$'}page: Int) {
              shows(search: ${'$'}search, limit: ${'$'}limit, page: ${'$'}page) {
                pageInfo {
                  total
                }
                edges {
                  _id
                  name
                  englishName
                  nativeName
                  thumbnail
                  slugTime
                }
              }
            }
        """.trimIndent()

        private val EPISODES_QUERY = """
            query(${'$'}_id: String!) {
              show(_id: ${'$'}_id) {
                _id
                availableEpisodesDetail
              }
            }
        """.trimIndent()
    }
}
