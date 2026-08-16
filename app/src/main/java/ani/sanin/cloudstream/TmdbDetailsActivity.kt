package ani.sanin.cloudstream

import android.content.Intent
import android.util.Log
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.FileUrl
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbCast
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbSeason
import ani.sanin.databinding.ActivityTmdbDetailsBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbCastBinding
import ani.sanin.databinding.ItemTmdbEpisodeBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.anime.Anime
import ani.sanin.media.anime.Episode
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.parsers.Video
import ani.sanin.parsers.VideoContainer
import ani.sanin.parsers.VideoExtractor
import ani.sanin.parsers.VideoServer
import ani.sanin.parsers.VideoType
import ani.sanin.util.Logger
import ani.sanin.media.SheetSourceSelector
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbDetailsActivity : AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
    }

    private lateinit var binding: ActivityTmdbDetailsBinding
    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var detail: TmdbDetail? = null
    private var seasons: List<TmdbSeason> = emptyList()
    private var selectedSeason = 1
    private var episodesSection: View? = null
    private var episodeAdapter: EpisodeGridAdapter? = null
    private val sources by lazy { CsRepos.installed(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Plugins use CommonActivity.getActivity() for toasts/browser launches;
        // keep it pointed at the TMDB screen while plugin streams are being used.
        CommonActivity.setActivityInstance(this)
        binding = ActivityTmdbDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1)
        Logger.log(
            "TMDB_PLAY: TmdbDetailsActivity opened mediaType=$mediaType mediaId=$mediaId"
        )

        binding.tmdbDetailBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailBack)
        binding.tmdbDetailPlayCard.setOnClickListener { onPlayClick() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailPlayCard)

        load()
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }

    private fun load() {
        lifecycleScope.launch {
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details")
                return@launch
            }
            detail = d
            binding.tmdbDetailBackdrop.loadImage(Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780))
            val logo = Tmdb.logoUrl(d)
            if (logo != null) {
                binding.tmdbDetailLogo.loadImage(logo)
            } else {
                binding.tmdbDetailLogo.visibility = View.GONE
            }
            binding.tmdbDetailRating.text = buildString {
                if (d.voteAverage > 0) append("★ ").append(String.format("%.1f", d.voteAverage)).append("  •  ")
                if (d.year.isNotBlank()) append(d.year)
            }
            binding.tmdbDetailStatus.text = statusLabel(d.status)
            binding.tmdbDetailSynopsis.text = d.overview?.takeIf { it.isNotBlank() } ?: "No synopsis available."
            d.genres.take(5).forEach { genre ->
                val chip = TextView(this@TmdbDetailsActivity).apply {
                    text = genre.name
                    setTextColor(resources.getColor(R.color.cs_chip_text, null))
                    setBackgroundResource(R.drawable.tmdb_chip_bg)
                    textSize = 12f
                    setPadding(36, 12, 36, 12)
                    isFocusable = true
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 20 }
                binding.tmdbDetailGenreChips.addView(chip, lp)
                FocusEffectUtil.applyFocusListener(chip)
            }

            if (mediaType == "tv") {
                seasons = Tmdb.seasons(mediaType, mediaId)
                if (seasons.isNotEmpty()) selectedSeason = seasons.first().seasonNumber
                buildEpisodesSection()
            } else {
                binding.tmdbDetailPlayText.text = "Play"
            }
            buildCastSection(d)
            buildMoreLikeSection(d)
        }
    }

    private fun statusLabel(status: String?): String = when (status?.lowercase()) {
        "returning series", "returning" -> "Ongoing"
        "released" -> "Released"
        "planned" -> "Upcoming"
        "in production" -> "In Production"
        "ended", "canceled", "cancelled" -> "Completed"
        else -> status ?: ""
    }

    // ── episodes / seasons ───────────────────────────────────────────────────

    private fun buildEpisodesSection() {
        val ctx = this
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = sectionHeader("Episodes")
        section.addView(header)

        val seasonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        seasons.forEach { season ->
            val chip = TextView(ctx).apply {
                text = "Season ${season.seasonNumber}"
                isFocusable = true
                isClickable = true
                setPadding(36, 12, 36, 12)
                textSize = 13f
                setOnClickListener {
                    selectedSeason = season.seasonNumber
                    refreshSeasonChips(seasonRow)
                    loadEpisodes()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
            seasonRow.addView(chip, lp)
            FocusEffectUtil.applyFocusListener(chip)
        }
        section.addView(seasonRow)
        seasonChipRow = seasonRow
        refreshSeasonChips(seasonRow)

        val grid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, episodeSpanCount())
            isNestedScrollingEnabled = false
        }
        episodeAdapter = EpisodeGridAdapter { episode -> onEpisodeClick(episode) }
        grid.adapter = episodeAdapter
        section.addView(grid)

        binding.tmdbDetailSections.addView(section)
        episodesSection = section
        loadEpisodes()
    }

    private var seasonChipRow: LinearLayout? = null

    private fun refreshSeasonChips(row: LinearLayout) {
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as TextView
            val selected = chip.text.toString().endsWith(selectedSeason.toString())
            chip.setTextColor(resources.getColor(R.color.cs_chip_text, null))
            chip.setBackgroundResource(R.drawable.tmdb_chip_bg)
            chip.alpha = if (selected) 1f else 0.6f
        }
    }

    private fun loadEpisodes() {
        lifecycleScope.launch {
            val eps = Tmdb.episodes(mediaType, mediaId, selectedSeason)
            episodeAdapter?.submit(eps)
        }
    }

    private fun onEpisodeClick(episode: TmdbEpisode) {
        openSources(episode.seasonNumber, episode.episodeNumber)
    }

    private fun onPlayClick() {
        if (mediaType == "tv") {
            episodesSection?.let { scrollToSection(it) } ?: run {
                binding.tmdbDetailPlayCard.post { scrollToSection(episodesSection ?: return@post) }
            }
        } else {
            openSources(null, null)
        }
    }

    private fun scrollToSection(section: View) {
        binding.root.post {
            val target = IntArray(2)
            val scroller = IntArray(2)
            section.getLocationInWindow(target)
            binding.root.getLocationInWindow(scroller)
            binding.root.smoothScrollTo(0, target[1] - scroller[1] + binding.root.scrollY)
        }
    }

    private fun episodeSpanCount(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

    // ── source / playback flow ──────────────────────────────────────────────

    private fun openSources(season: Int?, episodeNumber: Int?) {
        val usable = sources.filter {
            it.type.equals("movie", true) || it.type.equals("tv", true) || it.type.equals("all", true)
        }
        if (usable.isEmpty()) {
            snackString("No CloudStream sources installed. Add a repo in Extensions first.")
            return
        }
        val sheet = SheetSourceSelector.newInstance(
            ArrayList(usable.map { it.name }),
            onSelect = { idx -> playFromSource(usable[idx], season, episodeNumber) }
        )
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        sheet.show(supportFragmentManager, "tmdbSourceSelector")
    }

    private fun playFromSource(source: CsInstalledSource, season: Int?, episodeNumber: Int?) {
        val d = detail ?: return
        Logger.log(
            "TMDB_PLAY: user pressed play -> source '${source.name}' " +
                "(season=$season ep=$episodeNumber) for '${d.displayTitle}'"
        )
        snackString("Loading ${source.name}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolveStreams(source, d, season, episodeNumber) }
            when (result) {
                is StreamResult.Error -> {
                    Logger.log(Log.ERROR, "TMDB_PLAY: failed for ${source.name}: ${result.message}")
                    snackString(result.message)
                }
                is StreamResult.Success -> {
                    // The activity may have been backgrounded/destroyed while links were
                    // resolving (e.g. the user pressed play again). Showing a dialog then
                    // crashes with "Can not perform this action after onSaveInstanceState".
                    if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                        Log.i("TmdbDetails", "Discarding resolved links: activity not in a showable state")
                        return@launch
                    }
                    Logger.log(
                        "TMDB_PLAY: ${source.name} resolved ${result.links.size} links: " +
                            result.links.mapIndexed { i, l -> "$i:${l.label}" }.joinToString(" | ")
                    )
                    val labels = ArrayList(result.links.map { "${it.label}  •  ${source.name}" })
                    val picker = SheetSourceSelector.newInstance(labels, onSelect = { idx ->
                        if (!isFinishing && !isDestroyed) {
                            val link = result.links[idx]
                            Logger.log(
                                "TMDB_PLAY: opening anime player for '${d.displayTitle}' " +
                                    "url=${link.url} host=${runCatching { java.net.URI(link.url).host }.getOrNull() ?: "unknown"} " +
                                    "referer=${link.referer} headers=${link.headers}"
                            )
                            openInAnimePlayer(d.displayTitle, link)
                        }
                    })
                    picker.show(supportFragmentManager, "tmdbQualitySelector")
                }
            }
        }
    }

    private sealed class StreamResult {
        data class PlayableLink(
            val label: String,
            val url: String,
            val referer: String? = null,
            val headers: Map<String, String> = emptyMap()
        )
        data class Success(val links: List<PlayableLink>) : StreamResult()
        data class Error(val message: String) : StreamResult()
    }

    /**
     * Plays a resolved TMDB stream through the full-featured anime player
     * (ExoplayerView), so every button/behavior is identical to anime playback.
     * The plugin link is wrapped into a synthetic one-episode [Media] with a
     * single [VideoExtractor] carrying the URL + headers.
     */
    private fun openInAnimePlayer(title: String, link: StreamResult.PlayableLink) {
        val url = link.url
        val videoType = when {
            url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
            url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
            else -> VideoType.CONTAINER
        }
        val headers = HashMap(link.headers).apply {
            link.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val video = Video(quality = null, format = videoType, file = FileUrl(url, headers))
        val extractor = object : VideoExtractor() {
            override val server = VideoServer("TMDB", "")
            override suspend fun extract() = VideoContainer(listOf(video))
        }
        val episode = Episode(
            number = "1",
            title = title,
            selectedExtractor = "TMDB",
            selectedVideo = 0,
            selectedSubtitle = -1,
            extractors = mutableListOf(extractor),
            extractorsSource = 0,
        )
        val anime = Anime(
            selectedEpisode = "1",
            episodes = mutableMapOf("1" to episode),
            totalEpisodes = 1,
            episodeDuration = 1,
        )
        // Negative id space so it can never collide with a real anilist id.
        val syntheticId = -1000000000 - (this.mediaId % 1000000)
        val media = Media(
            anime = anime,
            id = syntheticId,
            name = title,
            nameRomaji = title,
            userPreferredName = title,
            isAdult = false,
        )
        ExoplayerView.media = media
        ExoplayerView.initialized = true
        Logger.log(
            "TMDB_PLAY: launching ExoplayerView type=${videoType.name} " +
                "headers=${headers} mediaId=${syntheticId}"
        )
        startActivity(Intent(this, ExoplayerView::class.java))
    }

    private suspend fun resolveStreams(
        source: CsInstalledSource,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): StreamResult {
        val apis = CsRuntime.apisFor(this, source)
        if (apis.isEmpty()) {
            val why = CsRuntime.lastError?.let { "\n$it" } ?: ""
            return StreamResult.Error("Failed to load ${source.name} — is it a .cs3 plugin?$why")
        }
        val failures = mutableListOf<String>()
        for (api in apis) {
            Logger.log(
                "TMDB_PLAY: trying provider ${api.name} for '${d.displayTitle}' " +
                    "(season=$season ep=$episodeNumber)"
            )
            Log.i("TmdbDetails", "Trying provider ${api.name} for '${d.displayTitle}' (season=$season ep=$episodeNumber)")
            try {
                val (streams, reason) = resolveFromApi(api, d, season, episodeNumber)
                Logger.log(
                    "TMDB_PLAY: ${api.name} returned ${streams.size} playable links " +
                        "reason=${if (reason.isNotBlank()) reason else "ok"}"
                )
                Log.i("TmdbDetails", "${api.name}: returned ${streams.size} playable links")
                if (streams.isNotEmpty()) return StreamResult.Success(streams)
                if (reason.isNotBlank()) failures += reason
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: provider ${api.name} threw $detail")
                Logger.log(t)
                Log.e("TmdbDetails", "Provider ${api.name} failed", t)
                failures += "${api.name}: $detail"
            }
        }
        val why = failures.lastOrNull()?.let { " — $it" } ?: ""
        return StreamResult.Error("No playable streams found on ${source.name}$why")
    }

    private data class ApiResolve(
        val links: List<StreamResult.PlayableLink>,
        val reason: String
    )

    private suspend fun resolveFromApi(
        api: MainAPI,
        d: TmdbDetail,
        season: Int?,
        episodeNumber: Int?
    ): ApiResolve {
        val wantMovie = d.displayTitle.isNotBlank() && mediaType == "movie"
        // Providers implement search(query, page) (paginated) OR the legacy search(query).
        // Calling the one-arg form throws NotImplementedError in the base class, which made
        // paginated-only providers (MovieBox, Goojara, ...) report "no streams". Mirror
        // CloudStream: call search(query, 1) first, then fall back to quickSearch().
        var searchError: String? = null
        val results = runCatching { api.search(d.displayTitle, 1)?.items }
            .getOrElse { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                searchError = "search threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                Logger.log(t)
                Log.e("TmdbDetails", "${api.name}: search(query,1) failed", t)
                null
            }?.takeIf { it.isNotEmpty() }
            ?: runCatching { api.quickSearch(d.displayTitle) }
                .getOrElse { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    searchError = "quickSearch threw ${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                    Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} $searchError")
                    Logger.log(t)
                    Log.e("TmdbDetails", "${api.name}: quickSearch failed", t)
                    null
                } ?: emptyList()
        Logger.log(
            "TMDB_PLAY: ${api.name} search '${d.displayTitle}' -> ${results.size} results " +
                (searchError ?: "")
        )
        Log.i("TmdbDetails", "${api.name}: search '${d.displayTitle}' -> ${results.size} results")
        val match = bestSearchMatch(results, d.displayTitle, wantMovie)
        Log.i("TmdbDetails", "${api.name}: best match = ${match?.url ?: "NONE"}")
        if (match == null) {
            return ApiResolve(emptyList(), "${api.name}: no search result matched '${d.displayTitle}' (${results.size} results)")
        }

        val response = try {
            api.load(match.url)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} load threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: load failed", t)
            return ApiResolve(emptyList(), "${api.name}: load threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} load '${match.url}' -> " +
                (response?.javaClass?.simpleName ?: "null response")
        )
        Log.i("TmdbDetails", "${api.name}: load ${match.url} -> ${response?.javaClass?.simpleName ?: "null"}")
        if (response == null) {
            return ApiResolve(emptyList(), "${api.name}: load returned null for ${match.url}")
        }
        val dataUrl: String = when {
            response is TvSeriesLoadResponse && season != null && episodeNumber != null -> {
                val episode = response.episodes.firstOrNull {
                    it.episode == episodeNumber && (it.season == null || it.season == season)
                } ?: response.episodes.firstOrNull { it.episode == episodeNumber }
                episode?.data ?: return ApiResolve(
                    emptyList(),
                    "${api.name}: episode $episodeNumber (season $season) not found in load response"
                )
            }
            response is MovieLoadResponse -> response.dataUrl
            else -> response.url
        }
        Log.i("TmdbDetails", "${api.name}: dataUrl = ${dataUrl.take(160).ifBlank { "<blank>" }}")
        if (dataUrl.isBlank()) {
            return ApiResolve(emptyList(), "${api.name}: blank dataUrl after load")
        }

        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val ok = try {
            api.loadLinks(dataUrl, isCasting = false, subtitleCallback = { subs.add(it) }, callback = { links.add(it) })
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val detail = "${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
            Logger.log(Log.ERROR, "TMDB_PLAY: ${api.name} loadLinks threw $detail")
            Logger.log(t)
            Log.e("TmdbDetails", "${api.name}: loadLinks failed", t)
            return ApiResolve(emptyList(), "${api.name}: loadLinks threw $detail")
        }
        Logger.log(
            "TMDB_PLAY: ${api.name} loadLinks ok=$ok links=${links.size} subs=${subs.size} " +
                "dataUrl=${dataUrl.take(120)}"
        )
        Log.i("TmdbDetails", "${api.name}: loadLinks ok=$ok links=${links.size} subs=${subs.size}")
        if (!ok || links.isEmpty()) {
            return ApiResolve(emptyList(), "${api.name}: loadLinks ok=$ok links=${links.size}")
        }

        val seen = HashSet<String>()
        val playable = links.mapNotNull { link ->
            val url = link.url
            if (url.isBlank() || !seen.add(url)) return@mapNotNull null
            val quality = Qualities.getStringByInt(link.quality).ifBlank { link.name }
            StreamResult.PlayableLink(
                label = quality,
                url = url,
                referer = link.referer.takeIf { it.isNotBlank() },
                headers = link.headers
            )
        }
        return ApiResolve(playable, "ok")
    }

    private fun bestSearchMatch(
        results: List<SearchResponse>,
        title: String,
        wantMovie: Boolean
    ): SearchResponse? {
        val exact = results.firstOrNull {
            (!wantMovie || it.type?.isMovieType() == true) && looseMatch(it.name, title)
        }
        if (exact != null) return exact
        return results.firstOrNull { (!wantMovie || it.type?.isMovieType() == true) }
            ?: results.firstOrNull()
    }

    private fun looseMatch(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.contains(y) || y.contains(x)
    }

    // ── cast / more like this ───────────────────────────────────────────────

    private fun buildCastSection(d: TmdbDetail) {
        val cast = d.credits?.cast.orEmpty().take(20)
        if (cast.isEmpty()) return
        val ctx = this
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        section.addView(sectionHeader("Cast"))
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = CastAdapter(cast)
            isNestedScrollingEnabled = false
            setPadding(24, 8, 24, 8)
        }
        section.addView(list)
        binding.tmdbDetailSections.addView(section)
    }

    private fun buildMoreLikeSection(d: TmdbDetail) {
        val recs = d.recommendations?.results.orEmpty().take(20)
        if (recs.isEmpty()) return
        val ctx = this
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        section.addView(sectionHeader("More Like This"))
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = MoreLikeAdapter(recs) { media ->
                startActivity(
                    Intent(this@TmdbDetailsActivity, TmdbDetailsActivity::class.java)
                        .putExtra(ARG_MEDIA_TYPE, media.type)
                        .putExtra(ARG_MEDIA_ID, media.id)
                )
            }
            isNestedScrollingEnabled = false
            setPadding(24, 8, 24, 8)
        }
        section.addView(list)
        binding.tmdbDetailSections.addView(section)
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setPadding(4, 24, 4, 4)
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
    }

    class EpisodeGridAdapter(
        private val onClick: (TmdbEpisode) -> Unit
    ) : RecyclerView.Adapter<EpisodeGridAdapter.VH>() {

        private var items: List<TmdbEpisode> = emptyList()

        fun submit(list: List<TmdbEpisode>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbEpisodeNumber.text = "E${item.episodeNumber}"
            holder.binding.tmdbEpisodeName.text = item.name?.takeIf { it.isNotBlank() }
                ?: "Episode ${item.episodeNumber}"
            holder.binding.root.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.root)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class CastAdapter(
        private val items: List<TmdbCast>
    ) : RecyclerView.Adapter<CastAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbCastImage.loadImage(Tmdb.imageUrl(item.profilePath, 200))
            holder.binding.tmdbCastName.text = item.name
            holder.binding.tmdbCastRole.text = item.character ?: ""
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCastImage)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCastBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class MoreLikeAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<MoreLikeAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            TmdbCards.applyCardStyle(holder.binding, item)
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
