package ani.sanin.cloudstream

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbImage
import ani.sanin.connections.tmdb.TmdbImages
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbSeason
import ani.sanin.databinding.FragmentTmdbWatchBinding
import ani.sanin.databinding.ItemEpisodeListBinding
import ani.sanin.databinding.ItemEpisodeGridBinding
import ani.sanin.databinding.ItemTmdbWatchHeaderBinding
import ani.sanin.databinding.DialogTmdbWatchOptionsBinding
import ani.sanin.media.SheetSourceSelector
import ani.sanin.loadImage
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import jp.wasabeef.glide.transformations.BlurTransformation
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import android.widget.LinearLayout
import android.widget.ImageButton
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import ani.sanin.media.anime.Episode as AnimeEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The movie/tv "watch tab": a mirror of the anime watch tab (logo art on top,
 * source chips for installed CS3 plugins, season chips, continue watching and
 * an episode list) backed by TMDB metadata + CloudStream plugins.
 */
class TmdbWatchFragment : Fragment() {

    /** The host screen (details activity or the thin watch host) handles the
     *  back / prequel / sequel actions so the watch tab stays embedded. */
    interface Host {
        fun onWatchBackPressed()
        fun onWatchOpenTitle(type: String, id: Int)
    }

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
        private const val EPISODE_CAP = 24

        fun newInstance(
            mediaType: String,
            mediaId: Int,
            pluginSourceId: String?,
            pluginUrl: String?
        ): TmdbWatchFragment = TmdbWatchFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MEDIA_TYPE, mediaType)
                putInt(ARG_MEDIA_ID, mediaId)
                pluginSourceId?.let { putString(ARG_PLUGIN_SOURCE, it) }
                pluginUrl?.let { putString(ARG_PLUGIN_URL, it) }
            }
        }
    }

    private var host: Host? = null

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        host = context as? Host
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    private lateinit var binding: FragmentTmdbWatchBinding
    private lateinit var headerBinding: ItemTmdbWatchHeaderBinding

    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var pluginSourceId: String? = null
    private var pluginUrl: String? = null
    private var pluginLoad: LoadResponse? = null
    private val pluginEpisodes = mutableMapOf<Int, List<TmdbEpisode>>()
    private var detail: TmdbDetail? = null
    private var seasons: List<TmdbSeason> = emptyList()
    private var selectedSeason = 1
    private var episodes: List<TmdbEpisode> = emptyList()
    private var movieEpisodes: List<TmdbEpisode> = emptyList()

    private val sources by lazy { CsRepos.installed(requireContext()) }
    // -1 = Auto Search (try every installed plugin in order, no chip selected)
    private var selectedSourceIndex = -1
    // 0 bars, 1 list, 2 grid, 3 compact (anime mode styles)
    private var episodeStyle = 0
    private var reversed = false
    private var isResolving = false

    private var prequel: TmdbMedia? = null
    private var sequel: TmdbMedia? = null

    private val pluginMode get() = pluginUrl != null

    private lateinit var episodeAdapter: EpisodeListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentTmdbWatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mediaType = arguments?.getString(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = arguments?.getInt(ARG_MEDIA_ID, -1) ?: -1
        pluginSourceId = arguments?.getString(ARG_PLUGIN_SOURCE)
        pluginUrl = arguments?.getString(ARG_PLUGIN_URL)
        // Plugin titles have no TMDB id — key everything (caches, continue
        // watching, notify) off a stable hash of the plugin URL.
        if (pluginUrl != null) mediaId = pluginUrl.hashCode()
        episodeStyle = (PrefManager.getNullableCustomVal("tmdb_style", 0, Int::class.java)
            ?: 0).coerceIn(0, 1)
        reversed = PrefManager.getNullableCustomVal("tmdb_reversed_$mediaId", false, Boolean::class.java)
            ?: false
        Logger.log("TMDB_WATCH: opened mediaType=$mediaType mediaId=$mediaId style=$episodeStyle reversed=$reversed")

        binding.tmdbWatchBack.setOnClickListener { goBack() }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchBack)
        binding.tmdbWatchScrollTop.setOnClickListener {
            binding.tmdbWatchRecycler.scrollToPosition(0)
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchScrollTop, binding.tmdbWatchScrollTop)

        binding.tmdbWatchRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.tmdbWatchRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val logo = binding.tmdbWatchLogo
                val title = binding.tmdbWatchTitle
                val offset = recyclerView.computeVerticalScrollOffset().toFloat()
                val maxTranslate = 200f * resources.displayMetrics.density
                val translation = -minOf(offset, maxTranslate)
                logo.translationY = translation
                logo.alpha = 1f - (translation / -maxTranslate)
                title.translationY = translation
                title.alpha = 1f - (translation / -maxTranslate)
                binding.tmdbWatchScrollTop.isVisible = recyclerView.computeVerticalScrollOffset() > 0
            }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        // Re-read the blur/grey watched toggles (anime-exact) and re-sync Simkl
        // state so the indicators reflect setting changes / scrobbles.
        if (::episodeAdapter.isInitialized) {
            episodeAdapter.refreshCache()
            loadSimklWatched()
            // Refresh the continue card too — the resume position was just saved
            // by the player, so returning shows "Continue watching" immediately.
            updateContinueCard()
        }
    }

    private fun goBack() {
        host?.onWatchBackPressed()
            ?: requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private fun load() {
        lifecycleScope.launch {
            if (pluginMode) {
                loadPlugin()
                return@launch
            }
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details")
                goBack()
                return@launch
            }
            detail = d

            val logo = Tmdb.logoUrl(d)
            if (logo != null) {
                binding.tmdbWatchLogo.isVisible = true
                binding.tmdbWatchLogo.loadImage(logo)
            } else {
                binding.tmdbWatchTitle.isVisible = true
                binding.tmdbWatchTitle.text = d.displayTitle
            }

            headerBinding = ItemTmdbWatchHeaderBinding.inflate(LayoutInflater.from(requireContext()))

            if (mediaType == "tv") {
                seasons = Tmdb.seasons(mediaType, mediaId)
                selectedSeason = restoreSeason()
                episodes = Tmdb.episodes(mediaType, mediaId, selectedSeason)
            } else {
                // A movie is a single "episode".
                movieEpisodes = listOf(
                    TmdbEpisode(
                        id = mediaId,
                        name = d.displayTitle,
                        episodeNumber = 1,
                        seasonNumber = 1,
                        stillPath = d.backdropPath ?: d.posterPath,
                        airDate = d.releaseDate,
                        voteAverage = d.voteAverage
                    )
                )
                loadCollectionParts(d)
            }

            buildHeader()
            buildAdapter()
            updateContinueCard()
            loadSimklWatched()
            // Always kick off the auto search as soon as the tab opens so the user
            // sees "Searching : …" immediately instead of an idle Sources row.
            autoSearchOnOpen()
        }
    }

    /** Plugin-driven watch tab: loads the title straight from the selected
     *  plugin (home card -> here), mapping the LoadResponse onto the same
     *  TMDB-shaped data so the rest of the screen behaves identically. */
    private suspend fun loadPlugin() {
        val url = pluginUrl ?: return
        val source = sources.firstOrNull { it.id == pluginSourceId } ?: run {
            snackString("Plugin not installed anymore")
            goBack()
            return
        }
        val api = withContext(Dispatchers.IO) {
            CsRuntime.apisFor(requireContext(), source).firstOrNull()
        } ?: run {
            snackString("Could not load ${source.name}")
            goBack()
            return
        }
        Logger.log("TMDB_WATCH: plugin mode, loading '$url' via ${source.name}")
        val load = withContext(Dispatchers.IO) {
            runCatching { api.load(url) }.getOrNull()
        }
        if (load == null) {
            Logger.log(android.util.Log.ERROR, "TMDB_WATCH: plugin load returned null for $url")
            snackString("Plugin returned nothing for this title")
            goBack()
            return
        }
        pluginLoad = load
        Logger.log(
            "TMDB_WATCH: plugin load -> ${load.javaClass.simpleName} '${load.name}' " +
                "(mediaId=$mediaId, api=${load.apiName})"
        )

        val poster = load.posterUrl
        val backdrop = load.backgroundPosterUrl ?: load.posterUrl
        val rating = load.score?.toFloat(10)?.toDouble() ?: 0.0
        val genres = load.tags.orEmpty().map { TmdbGenre(0, it) }
        val year = load.year?.toString()
        val images = load.logoUrl?.let { TmdbImages(logos = listOf(TmdbImage(it))) }

        if (load is TvSeriesLoadResponse) {
            mediaType = "tv"
            seasons = buildPluginSeasons(load)
            selectedSeason = restoreSeason()
            pluginEpisodes.clear()
            val tmdbEpRatings = seasons.associate { s ->
                s.seasonNumber to Tmdb.episodes("tv", mediaId, s.seasonNumber)
                    .associate { it.episodeNumber to it.voteAverage }
            }
            seasons.forEach { season ->
                pluginEpisodes[season.seasonNumber] = load.episodes
                    .filter { (it.season ?: 1) == season.seasonNumber }
                    .map { toPluginEpisode(it, tmdbEpRatings[season.seasonNumber]) }
            }
            episodes = pluginEpisodes[selectedSeason].orEmpty()
            detail = TmdbDetail(
                id = mediaId,
                name = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                firstAirDate = year?.let { "$it-01-01" },
                numberOfSeasons = seasons.size,
                genres = genres,
                images = images,
                seasons = seasons
            )
        } else {
            mediaType = "movie"
            movieEpisodes = listOf(
                TmdbEpisode(
                    id = mediaId,
                    name = load.name,
                    episodeNumber = 1,
                    seasonNumber = 1,
                    stillPath = backdrop,
                    airDate = year?.let { "$it-01-01" },
                    voteAverage = rating
                )
            )
            detail = TmdbDetail(
                id = mediaId,
                title = load.name,
                overview = load.plot,
                voteAverage = rating,
                backdropPath = backdrop,
                posterPath = poster,
                releaseDate = year?.let { "$it-01-01" },
                genres = genres,
                images = images
            )
        }

        val d = detail ?: return
        val logo = Tmdb.logoUrl(d)
        if (logo != null) {
            binding.tmdbWatchLogo.isVisible = true
            binding.tmdbWatchLogo.loadImage(logo)
        } else {
            binding.tmdbWatchTitle.isVisible = true
            binding.tmdbWatchTitle.text = d.displayTitle
        }
        headerBinding = ItemTmdbWatchHeaderBinding.inflate(LayoutInflater.from(requireContext()))
        buildHeader()
        buildAdapter()
        updateContinueCard()
        loadSimklWatched()
        autoSearchOnOpen()
    }

    private fun buildPluginSeasons(load: TvSeriesLoadResponse): List<TmdbSeason> {
        val numbers = load.episodes.mapNotNull { it.season }.distinct().sorted()
            .ifEmpty { listOf(1) }
        val names = load.seasonNames.orEmpty().associate { it.season to it.name }
        return numbers.map { num ->
            TmdbSeason(
                id = num,
                name = names[num],
                seasonNumber = num,
                episodeCount = load.episodes.count { (it.season ?: 1) == num }
            )
        }
    }

    private fun toPluginEpisode(
        ep: com.lagradost.cloudstream3.Episode,
        tmdbRatings: Map<Int, Double>? = null
    ): TmdbEpisode = TmdbEpisode(
        id = ep.data.hashCode(),
        name = ep.name,
        episodeNumber = ep.episode ?: 0,
        seasonNumber = ep.season ?: 1,
        stillPath = ep.posterUrl,
        airDate = formatEpochDate(ep.date),
        voteAverage = tmdbRatings?.get(ep.episode ?: 0)?.takeIf { it > 0 } ?: 0.0
    )

    private fun formatEpochDate(epoch: Long?): String? {
        if (epoch == null || epoch <= 0L) return null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epoch }
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun pluginShells(): Map<String, AnimeEpisode>? = if (!pluginMode) {
        null
    } else if (mediaType == "tv") {
        TmdbStreamResolver.pluginEpisodeShells(mediaId, pluginEpisodes)
    } else {
        detail?.let { TmdbStreamResolver.pluginMovieShell(mediaId, it) }
    }

    private suspend fun loadCollectionParts(d: TmdbDetail) {
        val collectionId = d.collection?.id ?: return
        val parts = Tmdb.collection(collectionId)
        val idx = parts.indexOfFirst { it.id == mediaId }
        if (idx > 0) prequel = parts[idx - 1]
        if (idx >= 0 && idx < parts.lastIndex) sequel = parts[idx + 1]
    }

    private fun buildHeader() {
        val h = headerBinding

        // ── source chips: installed CS3 plugins only (Auto Search is NOT a chip) ──
        if (selectedSourceIndex == -1) selectedSourceIndex = defaultSourceIndex()
        h.tmdbWatchSourceChips.removeAllViews()
        h.tmdbWatchSourceTitle.text = when {
            sources.isEmpty() -> getString(R.string.tmdb_watch_no_sources)
            selectedSourceIndex == -1 -> getString(R.string.tmdb_watch_auto_search)
            else -> getString(R.string.tmdb_watch_sources)
        }
        sources.forEachIndexed { index, source ->
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSourceChips, false) as Chip
            chip.text = source.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.setTextColor(
                androidx.core.content.ContextCompat.getColorStateList(
                    requireContext(), ani.sanin.R.color.chip_text_color
                )
            )
            chip.tag = index
            if (index == selectedSourceIndex) chip.isChecked = true
            chip.setOnClickListener {
                val nowChecked = chip.isChecked
                selectedSourceIndex = if (nowChecked) index else -1
                saveSourcePref()
                lastAutoSource = null
                TmdbStreamResolver.invalidateLinks(mediaId)
                resolveJob?.cancel()
                resolveJob = null
                isResolving = false
                setSourceStatus(getString(R.string.tmdb_watch_sources))
                refreshChips(h.tmdbWatchSourceChips)
                episodeAdapter.submitEpisodes(episodesOrMovie())
                if (nowChecked) refreshSelected() else autoSearchOnOpen()
            }
            FocusEffectUtil.applyFocusListener(chip)
            h.tmdbWatchSourceChips.addView(chip)
        }

        // ── season area ──
        if (mediaType == "tv") {
            if (seasons.isNotEmpty()) {
                h.tmdbWatchSeasonScroll.isVisible = true
                h.tmdbWatchSingleSeason.isVisible = false
                h.tmdbWatchSeasonChips.removeAllViews()
                seasons.forEach { season ->
                    val chip = LayoutInflater.from(requireContext()).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSeasonChips, false) as Chip
                    chip.text = "Season ${season.seasonNumber}"
                    chip.isCheckable = true
                    chip.isClickable = true
                    chip.isFocusable = true
                    chip.setTextColor(
                        androidx.core.content.ContextCompat.getColorStateList(
                            requireContext(), ani.sanin.R.color.chip_text_color
                        )
                    )
                    chip.tag = season.seasonNumber
                    if (season.seasonNumber == selectedSeason) chip.isChecked = true
                    chip.setOnClickListener {
                        selectedSeason = season.seasonNumber
                        refreshChips(h.tmdbWatchSeasonChips)
                        loadEpisodesForSeason()
                    }
                    FocusEffectUtil.applyFocusListener(chip)
                    h.tmdbWatchSeasonChips.addView(chip)
                }
            } else {
                h.tmdbWatchSeasonScroll.isVisible = false
                h.tmdbWatchSingleSeason.isVisible = true
                h.tmdbWatchSingleSeason.text = getString(R.string.tmdb_watch_one_season)
            }
        } else {
            h.tmdbWatchMovieRow.isVisible = !pluginMode
            h.tmdbWatchPrequel.isVisible = !pluginMode && prequel != null
            h.tmdbWatchPrequel.setOnClickListener {
                prequel?.let { openWatch(it.type, it.id) }
            }
            FocusEffectUtil.applyFocusListener(h.tmdbWatchPrequel)
            h.tmdbWatchSequel.isVisible = !pluginMode && sequel != null
            h.tmdbWatchSequel.setOnClickListener {
                sequel?.let { openWatch(it.type, it.id) }
            }
            FocusEffectUtil.applyFocusListener(h.tmdbWatchSequel)
        }

        // ── refresh / notification / appearance ──
        h.tmdbWatchRefresh.setOnClickListener { refreshSelected() }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchRefresh)

        updateNotifyIcon()
        h.tmdbWatchNotify.setOnClickListener {
            val current = PrefManager.getNullableCustomVal("tmdb_notify_$mediaId", false, Boolean::class.java) ?: false
            PrefManager.setCustomVal("tmdb_notify_$mediaId", !current)
            updateNotifyIcon()
            toast(getString(if (!current) R.string.tmdb_watch_notify_on else R.string.tmdb_watch_notify_off))
        }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchNotify)

        h.tmdbWatchAppearance.setOnClickListener { showOptionsDialog() }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchAppearance)

        // ── continue watching ──
        h.tmdbWatchContinueCard.setOnClickListener { onContinueClick() }
        FocusEffectUtil.applyFocusListener(h.tmdbWatchContinueCard)

        h.tmdbWatchEpisodeCount.text = "${episodesOrMovie().size} ${getString(R.string.episodes).trim()}"
    }

    private fun refreshChips(group: com.google.android.material.chip.ChipGroup) {
        group.post {
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as Chip
                chip.isChecked = chip.tag == if (group === headerBinding.tmdbWatchSeasonChips) selectedSeason else selectedSourceIndex
            }
        }
    }

    /** First chip on open: the plugin chosen at home if it's still installed,
     *  otherwise the first installed plugin (or Auto Search if none). */
    private fun defaultSourceIndex(): Int {
        // Per-title source persistence first, so re-opening a show resumes the
        // exact plugin you last used (mirrors anime mode's continuity).
        val savedId = PrefManager.getNullableCustomVal("tmdb_source_$mediaId", null, String::class.java)
        if (!savedId.isNullOrEmpty()) {
            val idx = sources.indexOfFirst { it.id == savedId }
            if (idx >= 0) return idx
        }
        val preferredId = pluginSourceId
            ?: PrefManager.getVal<String>(PrefName.ContentSource).takeIf { it != "tmdb" }
        if (preferredId != null) {
            val idx = sources.indexOfFirst { it.id == preferredId }
            if (idx >= 0) return idx
        }
        return if (sources.isNotEmpty()) 0 else -1
    }

    private fun updateNotifyIcon() {
        val enabled = PrefManager.getNullableCustomVal("tmdb_notify_$mediaId", false, Boolean::class.java) ?: false
        headerBinding.tmdbWatchNotify.setImageResource(
            if (enabled) R.drawable.ic_round_notifications_active_24
            else R.drawable.ic_round_notifications_none_24
        )
    }

    private fun setSourceStatus(text: String) {
        // Pad with invisible braille blanks so the status always fills its weight
        // and the refresh/notification buttons stay pinned at the right edge,
        // regardless of title length or how many plugin chips are present.
        val filler = "\u2800".repeat(120)
        headerBinding.tmdbWatchSourceTitle.ellipsize = TextUtils.TruncateAt.END
        headerBinding.tmdbWatchSourceTitle.isSelected = false
        headerBinding.tmdbWatchSourceTitle.text = text + filler
        headerBinding.tmdbWatchSpinner.isVisible = text.startsWith("Searching")
    }

    private fun showOptionsDialog() {
        val db = DialogTmdbWatchOptionsBinding.inflate(LayoutInflater.from(requireContext()))
        var run = false
        var rev = reversed
        var style = episodeStyle
        fun styleLabel(s: Int) = when (s) {
            0 -> R.string.tmdb_watch_style_bars
            else -> R.string.list
        }
        db.tmdbLayoutText.setText(styleLabel(style))
        db.tmdbSortText.text = getString(if (rev) R.string.tmdb_watch_down_to_up else R.string.tmdb_watch_up_to_down)
        db.tmdbSortTop.rotation = if (rev) -90f else 90f
        var selected = if (style == 0) db.tmdbStyleBars else db.tmdbStyleList
        selected.alpha = 1f
        fun select(it: ImageButton, s: Int) {
            selected.alpha = 0.33f
            selected = it
            selected.alpha = 1f
            style = s
            db.tmdbLayoutText.setText(styleLabel(s))
            run = true
        }
        db.tmdbStyleBars.setOnClickListener { select(db.tmdbStyleBars, 0) }
        db.tmdbStyleList.setOnClickListener { select(db.tmdbStyleList, 1) }
        db.tmdbSortTop.setOnClickListener {
            rev = !rev
            db.tmdbSortTop.rotation = if (rev) -90f else 90f
            db.tmdbSortText.text = getString(if (rev) R.string.tmdb_watch_down_to_up else R.string.tmdb_watch_up_to_down)
            run = true
        }
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.tmdb_watch_options))
            setCustomView(db.root)
            setPosButton(R.string.ok) {
                if (run) applyStyle(style, rev)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun applyStyle(style: Int, rev: Boolean) {
        episodeStyle = style.coerceIn(0, 1)
        reversed = rev
        PrefManager.setCustomVal("tmdb_style", style)
        PrefManager.setCustomVal("tmdb_reversed_$mediaId", rev)
        Logger.log("TMDB_WATCH: layout style=$style reversed=$rev")
        episodeAdapter.updateStyle(style)
        episodeAdapter.submitEpisodes(episodesOrMovie())
    }

    private fun displayList(eps: List<TmdbEpisode>): List<TmdbEpisode> =
        if (reversed) eps.reversed() else eps

    private fun episodesOrMovie(): List<TmdbEpisode> =
        displayList(if (mediaType == "tv") episodes.take(EPISODE_CAP) else movieEpisodes)

    private fun loadEpisodesForSeason() {
        if (pluginMode) {
            episodes = pluginEpisodes[selectedSeason].orEmpty()
            headerBinding.tmdbWatchEpisodeCount.text = "${episodes.size} ${getString(R.string.episodes).trim()}"
            episodeAdapter.submitEpisodes(displayList(episodes.take(EPISODE_CAP)))
            updateContinueCard()
            return
        }
        lifecycleScope.launch {
            val eps = Tmdb.episodes(mediaType, mediaId, selectedSeason)
            episodes = eps
            headerBinding.tmdbWatchEpisodeCount.text = "${eps.size} ${getString(R.string.episodes).trim()}"
            episodeAdapter.submitEpisodes(displayList(eps.take(EPISODE_CAP)))
            updateContinueCard()
        }
    }

    private fun buildAdapter() {
        episodeAdapter = EpisodeListAdapter(episodeStyle, episodesOrMovie()) { episode ->
            onEpisodeClick(episode)
        }
        binding.tmdbWatchRecycler.adapter = episodeAdapter
        // Header is a fixed first item owned by the adapter.
        episodeAdapter.setHeader(headerBinding.root)
    }

    /** Fetches Simkl watched episodes for the current title and marks them in
     *  the episode list (eye icon + anime blur/grey toggles). */
    private fun loadSimklWatched() {
        val realTmdbId = if (!pluginMode) mediaId else null
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val watched = if (realTmdbId != null && realTmdbId > 0 && mediaType == "tv") {
                runCatching {
                    val item = Simkl.getShowLibrary()
                        .firstOrNull { it.ids?.tmdb == realTmdbId }
                    if (item == null) return@runCatching emptySet()
                    // Prefer lastWatchedEpisode (total absolute across all seasons)
                    val totalWatched = item.lastWatchedEpisode ?: 0
                    if (totalWatched > 0) {
                        // Compute cumulative episode offsets per season so we can map
                        // absolute numbers back to per-season episode numbers.
                        var cumulative = 0
                        val result = mutableSetOf<Int>()
                        for (s in 1..50) {
                            val eps = try { Tmdb.episodes(mediaType, realTmdbId, s) } catch (_: Exception) { emptyList() }
                            if (eps.isEmpty()) break
                            for (ep in eps) {
                                cumulative++
                                if (cumulative <= totalWatched) {
                                    result.add(ep.episodeNumber)
                                }
                            }
                            // If we haven't reached this season yet at all, stop
                            if (cumulative >= totalWatched) break
                        }
                        result
                    } else {
                        // Fallback: use per-episode completed flag if available
                        item.episodes
                            ?.filter { it.completed == true }
                            ?.mapNotNull { it.number }
                            ?.toSet() ?: emptySet()
                    }
                }.getOrDefault(emptySet())
            } else emptySet()
            withContext(Dispatchers.Main) {
                if (::episodeAdapter.isInitialized) {
                    episodeAdapter.setWatchedEpisodes(watched)
                }
            }
        }
    }

    private fun onEpisodeClick(episode: TmdbEpisode) {
        // A user click always wins: cancel the on-open auto search if it is still
        // running, but keep blocking while an explicit resolve is in flight.
        if (isResolving && autoSearchJob?.isActive != true) return
        autoSearchJob?.cancel()
        autoSearchJob = null
        isResolving = false
        val d = detail ?: return
        val season = if (mediaType == "tv") episode.seasonNumber.takeIf { it > 0 } ?: selectedSeason else null
        val ep = if (mediaType == "tv") episode.episodeNumber else null
        Logger.log(
            "TMDB_WATCH: episode click '${d.displayTitle}' season=$season ep=$ep " +
                "sourceIdx=$selectedSourceIndex (${currentSourceName()})"
        )
        val sourceName = currentSourceName()
        val fetchingLabel = getString(R.string.tmdb_watch_fetching, sourceName)
        val picker = SheetSourceSelector.newInstanceLoading(fetchingLabel)
        fun fillPicker(result: TmdbStreamResolver.StreamResult.Success) {
            val labels = ArrayList(result.links.map { "${it.label}  •  $sourceName" })
            picker.setOnSelect { idx ->
                if (isAdded) {
                    val link = result.links[idx]
                    Logger.log(
                        "TMDB_WATCH: opening player url=${link.url} " +
                            "host=${runCatching { java.net.URI(link.url).host }.getOrNull() ?: "unknown"} " +
                            "referer=${link.referer} headers=${link.headers}"
                    )
                    saveLastPlayed(season, ep)
                    val source = sources.getOrNull(selectedSourceIndex) ?: lastAutoSource
                    if (source == null) {
                        snackString(getString(R.string.tmdb_watch_no_sources))
                        return@setOnSelect
                    }
                    lifecycleScope.launch {
                        TmdbStreamResolver.launchPlayer(
                            requireContext(),
                            mediaId,
                            mediaType,
                            d,
                            source,
                            season,
                            ep,
                            result.links,
                            link.label,
                            episodesOverride = pluginShells(),
                            load = pluginLoad
                        )
                    }
                }
            }
            picker.updateSources(labels)
        }

        // Same plugin + same episode already resolved? Reuse the cached servers —
        // the sheet appears instantly with no double loading.
        val cached = TmdbStreamResolver.cachedLinks(mediaId, sourceName, season, ep)
        if (cached != null && cached.links.isNotEmpty()) {
            Logger.log(
                "TMDB_WATCH: cached ${cached.links.size} links for " +
                    "'${d.displayTitle}' S${season}E$ep via $sourceName"
            )
            setSourceStatus(
                "${getString(R.string.found)} : ${cached.matchName ?: d.displayTitle} from $sourceName"
            )
            fillPicker(cached)
            picker.show(childFragmentManager, "tmdbWatchServerSelector")
            return
        }
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        // Open the server sheet FIRST with a "Fetching from …" row; the resolved
        // links are dropped into the same sheet when they land, so the user sees
        // progress instead of a bare snackbar.
        picker.show(childFragmentManager, "tmdbWatchServerSelector")
        isResolving = true
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolve(season, ep) }
            isResolving = false
            if (!isAdded || childFragmentManager.isStateSaved) {
                Logger.log("TMDB_WATCH: discarding links, activity not showable")
                return@launch
            }
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    Logger.log(android.util.Log.ERROR, "TMDB_WATCH: failed: ${result.message}")
                    picker.updateSources(listOf("─── ${result.message} ───"))
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    TmdbStreamResolver.cacheLinks(mediaId, sourceName, season, ep, result)
                    setSourceStatus(
                        "${if (selectedSourceIndex == -1) getString(R.string.found) else getString(R.string.selected)} : " +
                            "${result.matchName ?: d.displayTitle} from $sourceName"
                    )
                    Logger.log(
                        "TMDB_WATCH: ${result.links.size} links via $sourceName: " +
                            result.links.mapIndexed { i, l -> "$i:${l.label}" }.joinToString(" | ")
                    )
                    fillPicker(result)
                }
            }
        }
    }

    /** Runs the auto search (all installed sources, in order) the moment the tab
     *  opens, so "Searching : …" shows immediately; on success the checked source
     *  chip moves to whichever plugin actually provided the streams. */
    private fun autoSearchOnOpen() {
        autoSearchJob?.cancel()
        if (isResolving) return
        val d = detail ?: return
        val season = if (mediaType == "tv") selectedSeason else null
        Logger.log("TMDB_WATCH: auto search on open for '${d.displayTitle}' (season=$season)")
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        autoSearchJob = lifecycleScope.launch {
            val (source, result) = try {
                withContext(Dispatchers.IO) {
                    if (sources.isEmpty()) {
                        null to TmdbStreamResolver.StreamResult.Error(getString(R.string.tmdb_watch_no_sources))
                    } else if (pluginMode) {
                        val home = sources.firstOrNull { it.id == pluginSourceId }
                        val load = pluginLoad
                        if (home != null && load != null) {
                            home to TmdbStreamResolver.resolvePluginStreams(
                                requireContext(), home, load, season, null
                            )
                        } else {
                            null to TmdbStreamResolver.StreamResult.Error("Plugin data missing")
                        }
                    } else {
                        TmdbStreamResolver.resolveAuto(requireContext(), sources, d, season, null)
                    }
                }
            } finally {
                // Only clear state if this coroutine is still the active auto
                // search — a user click already nulled the job and started an
                // explicit resolve, so its flag must not be clobbered here.
                if (autoSearchJob == coroutineContext[Job]) {
                    autoSearchJob = null
                    isResolving = false
                }
            }
            lastAutoSource = source
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    Logger.log(android.util.Log.ERROR, "TMDB_WATCH: auto search failed: ${result.message}")
                    snackString(result.message)
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    val foundName = source?.name ?: currentSourceName()
                    setSourceStatus(
                        "${getString(R.string.found)} : ${result.matchName ?: d.displayTitle} from $foundName"
                    )
                    source?.let { src ->
                        val idx = sources.indexOf(src)
                        if (idx >= 0) {
                            selectedSourceIndex = idx
                        }
                    }
                    refreshChips(headerBinding.tmdbWatchSourceChips)
                    Logger.log("TMDB_WATCH: auto search found ${result.links.size} links via $foundName")
                    snackString("${result.links.size} links found via $foundName")
                }
            }
        }
    }

    private var lastAutoSource: CsInstalledSource? = null

    /** The auto search kicked off on tab open; cancelled the moment the user
     *  explicitly picks an episode or presses refresh so it never blocks them. */
    private var autoSearchJob: Job? = null

    /** In-flight explicit resolve (episode click / refresh); cancelled when the
     *  user switches plugin so its result is never cached under the new source. */
    private var resolveJob: Job? = null

    private suspend fun resolve(season: Int?, ep: Int?): TmdbStreamResolver.StreamResult {
        val d = detail ?: return TmdbStreamResolver.StreamResult.Error("No title loaded")
        if (selectedSourceIndex == -1) {
            if (sources.isEmpty()) {
                return TmdbStreamResolver.StreamResult.Error(getString(R.string.tmdb_watch_no_sources))
            }
            if (pluginMode) {
                // Auto search on a plugin title: resolve the home plugin directly
                // (its LoadResponse is already in hand — no title search needed).
                val home = sources.firstOrNull { it.id == pluginSourceId }
                val load = pluginLoad
                if (home != null && load != null) {
                    val result = TmdbStreamResolver.resolvePluginStreams(requireContext(), home, load, season, ep)
                    lastAutoSource = home
                    return result
                }
            }
            val (source, result) = TmdbStreamResolver.resolveAuto(requireContext(), sources, d, season, ep)
            lastAutoSource = source
            return result
        }
        val source = sources.getOrNull(selectedSourceIndex)
            ?: return TmdbStreamResolver.StreamResult.Error("Source not found")
        val load = pluginLoad
        if (pluginMode && source.id == pluginSourceId && load != null) {
            return TmdbStreamResolver.resolvePluginStreams(requireContext(), source, load, season, ep)
        }
        return TmdbStreamResolver.resolveStreams(requireContext(), source, d, season, ep)
    }

    private fun currentSourceName(): String = when {
        selectedSourceIndex == -1 -> lastAutoSource?.name ?: getString(R.string.tmdb_watch_auto_search)
        else -> sources.getOrNull(selectedSourceIndex)?.name ?: getString(R.string.tmdb_watch_auto_search)
    }

    private fun refreshSelected() {
        if (isResolving && autoSearchJob?.isActive != true) return
        autoSearchJob?.cancel()
        autoSearchJob = null
        isResolving = false
        val d = detail ?: return
        val season = if (mediaType == "tv") selectedSeason else null
        val ep = null
        Logger.log("TMDB_WATCH: refresh pressed for '${d.displayTitle}' (sourceIdx=$selectedSourceIndex)")
        snackString(getString(R.string.tmdb_watch_loading, d.displayTitle))
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolve(season, ep) }
            isResolving = false
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    snackString(result.message)
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    setSourceStatus(
                        "${if (selectedSourceIndex == -1) getString(R.string.found) else getString(R.string.selected)} : " +
                            "${result.matchName ?: d.displayTitle} from ${currentSourceName()}"
                    )
                    snackString("${result.links.size} links found via ${currentSourceName()}")
                }
            }
        }
    }

    private fun onContinueClick() {
        val d = detail ?: return
        val (season, ep) = lastPlayed() ?: run {
            if (mediaType == "tv") onEpisodeClick(episodes.firstOrNull() ?: return)
            else onEpisodeClick(movieEpisodes.firstOrNull() ?: return)
            return
        }
        val episode = if (mediaType == "tv") {
            episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == ep }
                ?: TmdbEpisode(id = 0, name = "S${season}E$ep", episodeNumber = ep, seasonNumber = season)
        } else {
            movieEpisodes.firstOrNull() ?: return
        }
        onEpisodeClick(episode)
    }

    /** Restore the last-watched season for this title, falling back to the
     *  first season if the saved one no longer exists. */
    private fun restoreSeason(): Int {
        val saved = lastPlayed()?.first ?: return seasons.firstOrNull()?.seasonNumber ?: 1
        return seasons.firstOrNull { it.seasonNumber == saved }?.seasonNumber ?: 1
    }

    private fun saveLastPlayed(season: Int?, ep: Int?) {
        val savedSeason = season ?: 1
        val savedEp = ep ?: 1
        PrefManager.setCustomVal("tmdb_last_${mediaId}", "$savedSeason:$savedEp")
    }

    private fun lastPlayed(): Pair<Int, Int>? {
        val raw = PrefManager.getNullableCustomVal("tmdb_last_${mediaId}", null, String::class.java) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val s = parts[0].toIntOrNull() ?: return null
        val e = parts[1].toIntOrNull() ?: return null
        return s to e
    }

    private fun saveSourcePref() {
        // -1 = Auto Search: forget the per-title pin so it falls back to the
        // home/global source next time instead of resurrecting an old plugin.
        if (selectedSourceIndex == -1) {
            PrefManager.setCustomVal("tmdb_source_$mediaId", "")
            return
        }
        val source = sources.getOrNull(selectedSourceIndex) ?: return
        PrefManager.setCustomVal("tmdb_source_$mediaId", source.id)
    }

    private fun updateContinueCard() {
        val h = headerBinding
        val syntheticId = TmdbStreamResolver.syntheticId(mediaId)
        // The player stores position under "S{season}E{ep}" for TV and "1" for
        // movies, so look up the exact key of the last thing the user watched
        // instead of assuming "_1" (which is why TV continue cards vanished).
        val (s, e) = lastPlayed() ?: (if (mediaType == "tv") selectedSeason to 1 else 1 to 1)
        val epKey = if (mediaType == "tv") "S${s}E$e" else "1"
        val pos = PrefManager.getNullableCustomVal("${syntheticId}_$epKey", 0L, Long::class.java) ?: 0L
        val max = PrefManager.getNullableCustomVal("${syntheticId}_${epKey}_max", 0L, Long::class.java) ?: 0L
        if (pos <= 0 || max <= 0) {
            h.tmdbWatchContinueCard.isVisible = false
            return
        }
        val detail = detail ?: return
        h.tmdbWatchContinueCard.isVisible = true
        h.tmdbWatchContinueImage.loadImage(Tmdb.imageUrl(detail.backdropPath ?: detail.posterPath, 780))
        val mm = TimeUnit.MILLISECONDS.toMinutes(pos)
        val ss = TimeUnit.MILLISECONDS.toSeconds(pos) % 60
        val episodeTitle = episodes.firstOrNull { it.seasonNumber == s && it.episodeNumber == e }?.name
            ?: (if (mediaType == "tv") "S${s} E$e" else detail.displayTitle)
        h.tmdbWatchContinueText.text = episodeTitle
        h.tmdbWatchContinueDetail.text = getString(R.string.tmdb_watch_continue_detail, s, e, mm, ss)
        val div = (pos.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        val barParams = h.tmdbWatchContinueProgress.layoutParams as LinearLayout.LayoutParams
        barParams.weight = div
        h.tmdbWatchContinueProgress.layoutParams = barParams
        val emptyParams = h.tmdbWatchContinueProgressEmpty.layoutParams as LinearLayout.LayoutParams
        emptyParams.weight = 1f - div
        h.tmdbWatchContinueProgressEmpty.layoutParams = emptyParams
    }

    private fun openWatch(type: String, id: Int) {
        host?.onWatchOpenTitle(type, id)
    }

    // ── adapter ─────────────────────────────────────────────────────────────

    private class EpisodeListAdapter(
        private var style: Int,
        private var items: List<TmdbEpisode>,
        private val onClick: (TmdbEpisode) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var header: View? = null
        private var watchedEpisodes: Set<Int> = emptySet()

        private val blurUnwatched: Boolean get() = PrefManager.getVal(PrefName.BlurUnwatchedEpisodes)
        private val greyWatched: Boolean get() = PrefManager.getVal(PrefName.GreyWatchedEpisodes)

        fun setHeader(view: View) {
            header = view
            notifyItemInserted(0)
        }

        fun setWatchedEpisodes(eps: Set<Int>) {
            watchedEpisodes = eps
            notifyDataSetChanged()
        }

        fun refreshCache() {
            notifyDataSetChanged()
        }

        fun updateStyle(newStyle: Int) {
            style = newStyle
            notifyDataSetChanged()
        }

        fun submitEpisodes(newItems: List<TmdbEpisode>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size + (if (header != null) 1 else 0)

        override fun getItemViewType(position: Int): Int =
            if (header != null && position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                HeaderVH(header!!)
            } else if (style == 1) {
                ListVH(ItemEpisodeListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                GridVH(ItemEpisodeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 0) return
            val ep = items[position - (if (header != null) 1 else 0)]
            val title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}"
            val date = ep.airDate.orEmpty()
            val image = Tmdb.imageUrl(ep.stillPath, 500)
            val isWatched = watchedEpisodes.contains(ep.episodeNumber)
            when (holder) {
                is GridVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    if (ep.voteAverage > 0) {
                        holder.binding.itemEpisodeRating.isVisible = true
                        holder.binding.itemEpisodeRating.text =
                            "★ " + String.format("%.1f", ep.voteAverage)
                    } else {
                        holder.binding.itemEpisodeRating.isVisible = false
                    }
                    loadEpisodeImage(holder.binding.itemMediaImage, image, isWatched)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.itemEpisodeSparkle1.isVisible = false
                    holder.binding.itemEpisodeSparkle2.isVisible = false
                    applyWatchedState(
                        holder.binding.itemEpisodeViewed,
                        holder.binding.itemEpisodeViewedCover,
                        holder.binding.itemMediaImage,
                        holder.binding.itemEpisodeTitle,
                        holder.binding.itemEpisodeDate,
                        holder.binding.itemEpisodeNumber,
                        isWatched
                    )
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is ListVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    val desc = ep.overview.orEmpty()
                    holder.binding.itemEpisodeDesc.text = desc
                    holder.binding.itemEpisodeDesc.isVisible = desc.isNotBlank()
                    if (ep.voteAverage > 0) {
                        holder.binding.itemEpisodeRating.isVisible = true
                        holder.binding.itemEpisodeRating.text =
                            "★ " + String.format("%.1f", ep.voteAverage)
                    } else {
                        holder.binding.itemEpisodeRating.isVisible = false
                    }
                    loadEpisodeImage(holder.binding.itemMediaImage, image, isWatched)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.itemDownload.isVisible = false
                    holder.binding.itemDownloadStatus.isVisible = false
                    holder.binding.itemEpisodeSparkle1.isVisible = false
                    holder.binding.itemEpisodeSparkle2.isVisible = false
                    applyWatchedState(
                        holder.binding.itemEpisodeViewed,
                        holder.binding.itemEpisodeViewedCover,
                        holder.binding.itemMediaImage,
                        holder.binding.itemEpisodeTitle,
                        holder.binding.itemEpisodeDate,
                        holder.binding.itemEpisodeNumber,
                        isWatched
                    )
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                else -> {}
            }
        }

        /** Anime-exact watched styling: eye icon + cover when watched, and
         *  optional grey (watched) / blur (unwatched) via the shared toggles. */
        private fun applyWatchedState(
            viewedIcon: View,
            viewedCover: View,
            image: android.widget.ImageView,
            title: android.widget.TextView,
            date: android.widget.TextView,
            number: android.widget.TextView,
            isWatched: Boolean
        ) {
            if (isWatched) {
                viewedCover.isVisible = true
                viewedIcon.isVisible = true
                if (greyWatched) {
                    val cm = ColorMatrix().apply { setSaturation(0f) }
                    image.colorFilter = ColorMatrixColorFilter(cm)
                    title.alpha = 0.5f
                    date.alpha = 0.5f
                    number.alpha = 0.5f
                } else {
                    image.colorFilter = null
                    title.alpha = 1f
                    date.alpha = 1f
                    number.alpha = 1f
                }
            } else {
                viewedCover.isVisible = false
                viewedIcon.isVisible = false
                image.colorFilter = null
                if (blurUnwatched) {
                    title.alpha = 0.5f
                    date.alpha = 0.5f
                    number.alpha = 0.5f
                } else {
                    title.alpha = 1f
                    date.alpha = 1f
                    number.alpha = 1f
                }
            }
        }

        /** Load episode thumbnail with optional BlurTransformation (anime-exact). */
        private fun loadEpisodeImage(
            image: android.widget.ImageView,
            url: String?,
            isWatched: Boolean
        ) {
            if (url.isNullOrEmpty()) {
                image.loadImage(url)
                return
            }
            if (!isWatched && blurUnwatched) {
                val ctx = image.context
                val glideUrl = com.bumptech.glide.load.model.GlideUrl(url)
                Glide.with(ctx).load(glideUrl)
                    .override(400, 0)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .transform(BlurTransformation(15, 3))
                    .into(image)
            } else {
                image.loadImage(url)
            }
        }

        class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView)
        class GridVH(val binding: ItemEpisodeGridBinding) : RecyclerView.ViewHolder(binding.root)
        class ListVH(val binding: ItemEpisodeListBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
