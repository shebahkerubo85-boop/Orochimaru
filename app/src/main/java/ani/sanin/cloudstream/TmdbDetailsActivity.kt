package ani.sanin.cloudstream

import android.content.Intent
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
import ani.sanin.media.SheetSourceSelector
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
        binding = ActivityTmdbDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1)

        binding.tmdbDetailBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailBack)
        binding.tmdbDetailPlayCard.setOnClickListener { onPlayClick() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailPlayCard)

        load()
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
        sheet.show(supportFragmentManager, "tmdbSourceSelector")
    }

    private fun playFromSource(source: CsInstalledSource, season: Int?, episodeNumber: Int?) {
        val d = detail ?: return
        lifecycleScope.launch {
            val js = runCatching {
                CsRepos.installedFile(this@TmdbDetailsActivity, source).readText()
            }.getOrNull()
            if (js.isNullOrBlank()) {
                snackString("Source file missing: ${source.name}")
                return@launch
            }
            val matchUrl = runCatching {
                val raw = CsEngine.call(source.id, "search", listOf(d.displayTitle), js) ?: return@runCatching null
                val arr = JSONArray(raw)
                var best: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "")
                    if (title.isBlank()) continue
                    if (best == null) best = obj
                    if (looseMatch(title, d.displayTitle)) { best = obj; break }
                }
                best?.optString("url", "")
            }.getOrNull()
            if (matchUrl.isNullOrBlank()) {
                snackString("No match for '${d.displayTitle}' on ${source.name}")
                return@launch
            }

            val episodeUrl = if (season != null && episodeNumber != null) {
                runCatching {
                    val raw = CsEngine.call(source.id, "getEpisodes", listOf(matchUrl), js) ?: return@runCatching null
                    val arr = JSONArray(raw)
                    var ep: JSONObject? = null
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optString("id", "") == "S${season}E$episodeNumber") { ep = obj; break }
                    }
                    ep?.optString("url", "")
                }.getOrNull()
            } else {
                runCatching {
                    val raw = CsEngine.call(source.id, "getDetail", listOf(matchUrl), js) ?: return@runCatching null
                    val obj = JSONObject(raw)
                    obj.optJSONArray("episodes")?.optJSONObject(0)?.optString("url", "")
                }.getOrNull()
            }
            if (episodeUrl.isNullOrBlank()) {
                snackString("No episode found on ${source.name}")
                return@launch
            }

            val streams = runCatching {
                val raw = CsEngine.call(source.id, "getVideoSources", listOf(episodeUrl), js)
                    ?: return@runCatching emptyList()
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = obj.optString("url", "")
                    if (url.isBlank()) null
                    else url to obj.optString("quality", "").ifBlank { "Stream ${i + 1}" }
                }
            }.getOrElse { emptyList() }

            if (streams.isEmpty()) {
                snackString("No playable streams found on ${source.name}")
                return@launch
            }
            val labels = ArrayList(streams.map { "${it.second}  •  ${source.name}" })
            val picker = SheetSourceSelector.newInstance(labels, onSelect = { idx ->
                startActivity(
                    Intent(this@TmdbDetailsActivity, TmdbPlayerActivity::class.java)
                        .putExtra(TmdbPlayerActivity.EXTRA_URL, streams[idx].first)
                        .putExtra(TmdbPlayerActivity.EXTRA_TITLE, d.displayTitle)
                )
            })
            picker.show(supportFragmentManager, "tmdbQualitySelector")
        }
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
            holder.binding.tmdbCardPoster.loadImage(Tmdb.imageUrl(item.posterPath, 300))
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
