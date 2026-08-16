package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbEpisode
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbSeason
import ani.sanin.databinding.ActivityTmdbWatchBinding
import ani.sanin.databinding.ItemEpisodeCompactBinding
import ani.sanin.databinding.ItemEpisodeGridBinding
import ani.sanin.databinding.ItemEpisodeListBinding
import ani.sanin.databinding.ItemTmdbEpisodeBarBinding
import ani.sanin.databinding.ItemTmdbWatchHeaderBinding
import ani.sanin.databinding.DialogTmdbWatchOptionsBinding
import ani.sanin.media.SheetSourceSelector
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.themes.ThemeManager
import ani.sanin.snackString
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import ani.sanin.util.customAlertDialog
import android.widget.ImageButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The movie/tv "watch tab": a mirror of the anime watch tab (logo art on top,
 * source chips for installed CS3 plugins, season chips, continue watching and
 * an episode list) backed by TMDB metadata + CloudStream plugins.
 */
class TmdbWatchActivity : AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        private const val EPISODE_CAP = 24
    }

    private lateinit var binding: ActivityTmdbWatchBinding
    private lateinit var headerBinding: ItemTmdbWatchHeaderBinding

    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var detail: TmdbDetail? = null
    private var seasons: List<TmdbSeason> = emptyList()
    private var selectedSeason = 1
    private var episodes: List<TmdbEpisode> = emptyList()
    private var movieEpisodes: List<TmdbEpisode> = emptyList()

    private val sources by lazy { CsRepos.installed(this) }
    // -1 = Auto Search (try every installed plugin in order, no chip selected)
    private var selectedSourceIndex = -1
    // 0 bars, 1 list, 2 grid, 3 compact (anime mode styles)
    private var episodeStyle = 0
    private var reversed = false
    private var isResolving = false

    private var prequel: TmdbMedia? = null
    private var sequel: TmdbMedia? = null

    private lateinit var episodeAdapter: EpisodeListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the user's actual theme (OLED black, accent colors, ...).
        ThemeManager(this).applyTheme()
        binding = ActivityTmdbWatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1)
        episodeStyle = PrefManager.getNullableCustomVal("tmdb_style_$mediaId", 0, Int::class.java)
            ?: 0
        reversed = PrefManager.getNullableCustomVal("tmdb_reversed_$mediaId", false, Boolean::class.java)
            ?: false
        Logger.log("TMDB_WATCH: opened mediaType=$mediaType mediaId=$mediaId style=$episodeStyle reversed=$reversed")

        binding.tmdbWatchBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchBack)
        binding.tmdbWatchScrollTop.setOnClickListener {
            binding.tmdbWatchRecycler.scrollToPosition(0)
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbWatchScrollTop, binding.tmdbWatchScrollTop)

        binding.tmdbWatchRecycler.layoutManager = LinearLayoutManager(this)
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

    private fun load() {
        lifecycleScope.launch {
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details")
                finish()
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

            headerBinding = ItemTmdbWatchHeaderBinding.inflate(layoutInflater)

            if (mediaType == "tv") {
                seasons = Tmdb.seasons(mediaType, mediaId)
                selectedSeason = seasons.firstOrNull()?.seasonNumber ?: 1
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

            buildHeader(d)
            buildAdapter()
            updateContinueCard()
        }
    }

    private suspend fun loadCollectionParts(d: TmdbDetail) {
        val collectionId = d.collection?.id ?: return
        val parts = Tmdb.collection(collectionId)
        val idx = parts.indexOfFirst { it.id == mediaId }
        if (idx > 0) prequel = parts[idx - 1]
        if (idx >= 0 && idx < parts.lastIndex) sequel = parts[idx + 1]
    }

    private fun buildHeader(d: TmdbDetail) {
        val h = headerBinding

        // ── source chips: installed CS3 plugins only (Auto Search is NOT a chip) ──
        h.tmdbWatchSourceChips.removeAllViews()
        h.tmdbWatchSourceTitle.text = when {
            sources.isEmpty() -> getString(R.string.tmdb_watch_no_sources)
            selectedSourceIndex == -1 -> getString(R.string.tmdb_watch_auto_search)
            else -> getString(R.string.tmdb_watch_sources)
        }
        sources.forEachIndexed { index, source ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSourceChips, false) as Chip
            chip.text = source.name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.tag = index
            if (index == selectedSourceIndex) chip.isChecked = true
            chip.setOnClickListener {
                val nowChecked = chip.isChecked
                selectedSourceIndex = if (nowChecked) index else -1
                Logger.log(
                    "TMDB_WATCH: source chip -> '${source.name}' (idx $index) " +
                        "checked=$nowChecked (selectedSourceIndex=$selectedSourceIndex)"
                )
                setSourceStatus(getString(R.string.tmdb_watch_sources))
                refreshChips(h.tmdbWatchSourceChips)
            }
            FocusEffectUtil.applyFocusListener(chip)
            h.tmdbWatchSourceChips.addView(chip)
        }

        // ── season area ──
        if (mediaType == "tv") {
            if (seasons.size > 1) {
                h.tmdbWatchSeasonScroll.isVisible = true
                h.tmdbWatchSingleSeason.isVisible = false
                h.tmdbWatchSeasonChips.removeAllViews()
                seasons.forEach { season ->
                    val chip = LayoutInflater.from(this).inflate(R.layout.item_tmdb_chip, h.tmdbWatchSeasonChips, false) as Chip
                    chip.text = "Season ${season.seasonNumber}"
                    chip.isCheckable = true
                    chip.isClickable = true
                    chip.isFocusable = true
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
            // Movie: prequel / sequel
            h.tmdbWatchMovieRow.isVisible = true
            h.tmdbWatchPrequel.isVisible = prequel != null
            h.tmdbWatchPrequel.setOnClickListener {
                prequel?.let { openWatch(it.type, it.id) }
            }
            FocusEffectUtil.applyFocusListener(h.tmdbWatchPrequel)
            h.tmdbWatchSequel.isVisible = sequel != null
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

    private fun updateNotifyIcon() {
        val enabled = PrefManager.getNullableCustomVal("tmdb_notify_$mediaId", false, Boolean::class.java) ?: false
        headerBinding.tmdbWatchNotify.setImageResource(
            if (enabled) R.drawable.ic_round_notifications_active_24
            else R.drawable.ic_round_notifications_none_24
        )
    }

    private fun setSourceStatus(text: String) {
        headerBinding.tmdbWatchSourceTitle.text = text
        headerBinding.tmdbWatchSpinner.isVisible = text.startsWith("Searching")
    }

    private fun showOptionsDialog() {
        val db = DialogTmdbWatchOptionsBinding.inflate(layoutInflater)
        var run = false
        var rev = reversed
        var style = episodeStyle
        fun styleLabel(s: Int) = when (s) {
            0 -> R.string.tmdb_watch_style_bars
            1 -> R.string.list
            2 -> R.string.grid
            else -> R.string.compact
        }
        db.tmdbLayoutText.setText(styleLabel(style))
        db.tmdbSortText.text = getString(if (rev) R.string.tmdb_watch_down_to_up else R.string.tmdb_watch_up_to_down)
        db.tmdbSortTop.rotation = if (rev) -90f else 90f
        var selected = when (style) {
            1 -> db.tmdbStyleList
            2 -> db.tmdbStyleGrid
            3 -> db.tmdbStyleCompact
            else -> db.tmdbStyleBars
        }
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
        db.tmdbStyleGrid.setOnClickListener { select(db.tmdbStyleGrid, 2) }
        db.tmdbStyleCompact.setOnClickListener { select(db.tmdbStyleCompact, 3) }
        db.tmdbSortTop.setOnClickListener {
            rev = !rev
            db.tmdbSortTop.rotation = if (rev) -90f else 90f
            db.tmdbSortText.text = getString(if (rev) R.string.tmdb_watch_down_to_up else R.string.tmdb_watch_up_to_down)
            run = true
        }
        customAlertDialog().apply {
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
        episodeStyle = style
        reversed = rev
        PrefManager.setCustomVal("tmdb_style_$mediaId", style)
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

    private fun onEpisodeClick(episode: TmdbEpisode) {
        if (isResolving) return
        val d = detail ?: return
        val season = if (mediaType == "tv") episode.seasonNumber.takeIf { it > 0 } ?: selectedSeason else null
        val ep = if (mediaType == "tv") episode.episodeNumber else null
        Logger.log(
            "TMDB_WATCH: episode click '${d.displayTitle}' season=$season ep=$ep " +
                "sourceIdx=$selectedSourceIndex (${currentSourceName()})"
        )
        snackString(getString(R.string.tmdb_watch_loading, d.displayTitle))
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { resolve(season, ep) }
            isResolving = false
            when (result) {
                is TmdbStreamResolver.StreamResult.Error -> {
                    setSourceStatus(getString(R.string.tmdb_watch_sources))
                    Logger.log(android.util.Log.ERROR, "TMDB_WATCH: failed: ${result.message}")
                    snackString(result.message)
                }
                is TmdbStreamResolver.StreamResult.Success -> {
                    setSourceStatus(
                        "${if (selectedSourceIndex == -1) getString(R.string.found) else getString(R.string.selected)} : " +
                            "${result.matchName ?: d.displayTitle} from ${currentSourceName()}"
                    )
                    if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) {
                        Logger.log("TMDB_WATCH: discarding links, activity not showable")
                        return@launch
                    }
                    val sourceName = currentSourceName()
                    Logger.log(
                        "TMDB_WATCH: ${result.links.size} links via $sourceName: " +
                            result.links.mapIndexed { i, l -> "$i:${l.label}" }.joinToString(" | ")
                    )
                    val labels = ArrayList(result.links.map { "${it.label}  •  $sourceName" })
                    val picker = SheetSourceSelector.newInstance(labels, onSelect = { idx ->
                        if (!isFinishing && !isDestroyed) {
                            val link = result.links[idx]
                            Logger.log(
                                "TMDB_WATCH: opening player url=${link.url} " +
                                    "host=${runCatching { java.net.URI(link.url).host }.getOrNull() ?: "unknown"} " +
                                    "referer=${link.referer} headers=${link.headers}"
                            )
                            saveLastPlayed(season, ep)
                            TmdbStreamResolver.openInAnimePlayer(
                                this@TmdbWatchActivity,
                                d.displayTitle,
                                link,
                                mediaId
                            )
                        }
                    })
                    picker.show(supportFragmentManager, "tmdbWatchServerSelector")
                }
            }
        }
    }

    private var lastAutoSource: CsInstalledSource? = null

    private suspend fun resolve(season: Int?, ep: Int?): TmdbStreamResolver.StreamResult {
        val d = detail ?: return TmdbStreamResolver.StreamResult.Error("No title loaded")
        if (selectedSourceIndex == -1) {
            if (sources.isEmpty()) {
                return TmdbStreamResolver.StreamResult.Error(getString(R.string.tmdb_watch_no_sources))
            }
            val (source, result) = TmdbStreamResolver.resolveAuto(this, sources, d, season, ep)
            lastAutoSource = source
            return result
        }
        val source = sources.getOrNull(selectedSourceIndex)
            ?: return TmdbStreamResolver.StreamResult.Error("Source not found")
        return TmdbStreamResolver.resolveStreams(this, source, d, season, ep)
    }

    private fun currentSourceName(): String = when {
        selectedSourceIndex == -1 -> lastAutoSource?.name ?: getString(R.string.tmdb_watch_auto_search)
        else -> sources.getOrNull(selectedSourceIndex)?.name ?: getString(R.string.tmdb_watch_auto_search)
    }

    private fun refreshSelected() {
        if (isResolving) return
        val d = detail ?: return
        val season = if (mediaType == "tv") selectedSeason else null
        val ep = null
        Logger.log("TMDB_WATCH: refresh pressed for '${d.displayTitle}' (sourceIdx=$selectedSourceIndex)")
        snackString(getString(R.string.tmdb_watch_loading, d.displayTitle))
        setSourceStatus(getString(R.string.tmdb_watch_searching, d.displayTitle))
        isResolving = true
        lifecycleScope.launch {
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

    private fun saveLastPlayed(season: Int?, ep: Int?) {
        if (mediaType == "tv" && season != null && ep != null) {
            PrefManager.setCustomVal("tmdb_last_${mediaId}", "$season:$ep")
        }
    }

    private fun lastPlayed(): Pair<Int, Int>? {
        val raw = PrefManager.getNullableCustomVal("tmdb_last_${mediaId}", null, String::class.java) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val s = parts[0].toIntOrNull() ?: return null
        val e = parts[1].toIntOrNull() ?: return null
        return s to e
    }

    private fun updateContinueCard() {
        val h = headerBinding
        val syntheticId = TmdbStreamResolver.syntheticId(mediaId)
        val pos = PrefManager.getNullableCustomVal("${syntheticId}_1", 0L, Long::class.java) ?: 0L
        val max = PrefManager.getNullableCustomVal("${syntheticId}_1_max", 0L, Long::class.java) ?: 0L
        if (pos <= 0 || max <= 0) {
            h.tmdbWatchContinueCard.isVisible = false
            return
        }
        val detail = detail ?: return
        h.tmdbWatchContinueCard.isVisible = true
        h.tmdbWatchContinueImage.loadImage(Tmdb.imageUrl(detail.backdropPath ?: detail.posterPath, 780))
        val (s, e) = lastPlayed() ?: (selectedSeason to 1)
        val mm = TimeUnit.MILLISECONDS.toMinutes(pos)
        val ss = TimeUnit.MILLISECONDS.toSeconds(pos) % 60
        val episodeTitle = episodes.firstOrNull { it.seasonNumber == s && it.episodeNumber == e }?.name
            ?: (if (mediaType == "tv") "S${s} E$e" else detail.displayTitle)
        h.tmdbWatchContinueText.text = episodeTitle
        h.tmdbWatchContinueDetail.text = getString(R.string.tmdb_watch_continue_detail, s, e, mm, ss)
    }

    private fun openWatch(type: String, id: Int) {
        startActivity(
            Intent(this, TmdbWatchActivity::class.java)
                .putExtra(ARG_MEDIA_TYPE, type)
                .putExtra(ARG_MEDIA_ID, id)
        )
    }

    // ── adapter ─────────────────────────────────────────────────────────────

    private class EpisodeListAdapter(
        private var style: Int,
        private var items: List<TmdbEpisode>,
        private val onClick: (TmdbEpisode) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var header: View? = null

        fun setHeader(view: View) {
            header = view
            notifyItemInserted(0)
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
            } else {
                when (style) {
                    1 -> ListVH(ItemEpisodeListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                    2 -> GridVH(ItemEpisodeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                    3 -> CompactVH(ItemEpisodeCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                    else -> BarsVH(ItemTmdbEpisodeBarBinding.inflate(LayoutInflater.from(parent.context), parent, false))
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 0) return
            val ep = items[position - (if (header != null) 1 else 0)]
            val title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}"
            val date = ep.airDate.orEmpty()
            val image = Tmdb.imageUrl(ep.stillPath, 500)
            when (holder) {
                is BarsVH -> {
                    holder.binding.itemBarTitle.text = title
                    holder.binding.itemBarMeta.text =
                        holder.binding.root.context.getString(R.string.tmdb_watch_bar_meta, ep.episodeNumber)
                    holder.binding.itemBarImage.loadImage(image)
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is GridVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    holder.binding.itemMediaImage.loadImage(image)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is ListVH -> {
                    holder.binding.itemEpisodeTitle.text = title
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.itemEpisodeDate.text = date
                    holder.binding.itemEpisodeDate.isVisible = date.isNotBlank()
                    holder.binding.itemMediaImage.loadImage(image)
                    holder.binding.itemMediaProgressCont.isVisible = false
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                is CompactVH -> {
                    holder.binding.itemEpisodeNumber.text = ep.episodeNumber.toString()
                    holder.binding.root.setOnClickListener { onClick(ep) }
                    FocusEffectUtil.applyFocusListener(holder.binding.root)
                }
                else -> {}
            }
        }

        class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView)
        class BarsVH(val binding: ItemTmdbEpisodeBarBinding) : RecyclerView.ViewHolder(binding.root)
        class GridVH(val binding: ItemEpisodeGridBinding) : RecyclerView.ViewHolder(binding.root)
        class ListVH(val binding: ItemEpisodeListBinding) : RecyclerView.ViewHolder(binding.root)
        class CompactVH(val binding: ItemEpisodeCompactBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
