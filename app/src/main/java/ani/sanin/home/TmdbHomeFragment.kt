package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.bannerCardSizePx
import ani.sanin.cloudstream.CsInstalledSource
import ani.sanin.cloudstream.CsRepos
import ani.sanin.cloudstream.CsRuntime
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbWatchActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.FragmentTmdbHomeBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.sizeBannerCard
import ani.sanin.util.FocusEffectUtil
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbHomeFragment : Fragment() {

    private var _binding: FragmentTmdbHomeBinding? = null
    private val binding get() = _binding!!
    private val bannerItems = mutableListOf<TmdbMedia>()
    private var bannerIndex = 0
    private val bannerHandler = Handler(Looper.getMainLooper())
    private var genreNames: Map<Int, String> = emptyMap()
    private var logoJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tmdbBannerFrame.setOnClickListener {
            bannerItems.getOrNull(bannerIndex)?.let { openDetails(it.type, it.id) }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbBannerFrame)
        applyBannerLayout()
        load()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) applyBannerLayout()
    }

    override fun onResume() {
        super.onResume()
        startAutoAdvance()
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerHandler.removeCallbacksAndMessages(null)
        logoJob?.cancel()
        _binding = null
    }

    private fun load() {
        val sourceId = PrefManager.getVal<String>(PrefName.ContentSource)
        val plugin = if (sourceId == "tmdb") {
            null
        } else {
            CsRepos.installed(requireContext()).firstOrNull { it.id == sourceId }
        }
        if (plugin != null) {
            loadPluginHome(plugin)
        } else {
            loadTmdbHome()
        }
    }

    private fun loadTmdbHome() {
        viewLifecycleOwner.lifecycleScope.launch {
            val trendingSeries = Tmdb.trending("tv", "week")
            val trendingMovies = Tmdb.trending("movie", "week")
            val latestSeries = Tmdb.latestSeries()
            val latestMovies = Tmdb.latestMovies()
            val popular = Tmdb.popular()
            val topRated = Tmdb.topRated()
            val trending = trendingSeries + trendingMovies
            genreNames = Tmdb.genres().associate { it.id to it.name }
            bannerItems.clear()
            bannerItems.addAll(trending)
            if (trending.isNotEmpty()) showBanner(0)
            addSection("Trending Series", trendingSeries)
            addSection("Trending Movies", trendingMovies)
            addSection("Latest Series", latestSeries)
            addSection("Latest Movies", latestMovies)
            addSection("Popular", popular)
            addSection("Top Rated", topRated)
            startAutoAdvance()
        }
    }

    /** Movie & TV home driven by the selected CS3 plugin (CloudStream-style):
     *  each provider's own main-page sections and cards, never TMDB's. */
    private fun loadPluginHome(source: CsInstalledSource) {
        binding.tmdbBannerFrame.isVisible = false
        binding.tmdbHomeSections.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val apis = withContext(Dispatchers.IO) {
                CsRuntime.apisFor(requireContext(), source)
            }
            if (apis.isEmpty()) {
                addEmptyState(
                    "Plugin '${source.name}' failed to load — check Settings → CloudStream."
                )
                return@launch
            }
            val requests = apis.flatMap { api ->
                val pages = api.mainPage.filter { it.data.isNotBlank() }
                if (pages.isNotEmpty()) {
                    pages.map { api to it }
                } else {
                    // Fallback: some providers override getMainPage with hardcoded logic
                    // even when mainPage property is the default. Try a synthetic request.
                    listOf(api to com.lagradost.cloudstream3.MainPageRequest("Home", "", false))
                }
            }
            if (requests.isEmpty()) {
                addEmptyState("Plugin '${source.name}' exposes no home sections.")
                return@launch
            }
            for ((api, page) in requests) {
                val resp = runCatching {
                    withContext(Dispatchers.IO) {
                        api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                    }
                }.getOrNull()
                resp?.items?.forEach { list ->
                    if (list.list.isNotEmpty()) addPluginSection(list.name, list.list, source)
                }
            }
            if (binding.tmdbHomeSections.childCount == 0) {
                addEmptyState("Plugin '${source.name}' returned no content.")
            }
        }
    }

    private fun addEmptyState(text: String) {
        binding.tmdbHomeSections.addView(
            TextView(requireContext()).apply {
                this.text = text
                setPadding(48, 24, 48, 24)
                textSize = 15f
                alpha = 0.7f
                setTextColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
        )
    }

    private fun addPluginSection(
        title: String,
        items: List<SearchResponse>,
        source: CsInstalledSource
    ) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 20, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = PluginRowAdapter(items) { item ->
                startActivity(
                    Intent(requireContext(), TmdbDetailsActivity::class.java)
                        .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, source.id)
                        .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.url)
                )
            }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
    }

    private fun addSection(title: String, items: List<TmdbMedia>) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 20, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = TmdbRowAdapter(items) { media -> openDetails(media.type, media.id) }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
    }

    private fun showBanner(index: Int) {
        val item = bannerItems.getOrNull(index) ?: return
        bannerIndex = index
        binding.tmdbBannerImage.loadImage(Tmdb.imageUrl(item.backdropPath, 780))
        val meta = buildString {
            if (item.voteAverage > 0) append("★ ").append(String.format("%.1f", item.voteAverage)).append("  •  ")
            if (item.year.isNotBlank()) append(item.year).append("  •  ")
            append(item.type.replaceFirstChar { it.uppercase() })
        }
        binding.tmdbBannerTitle.text = item.displayTitle
        binding.tmdbBannerSideMeta.text = meta
        binding.tmdbBannerRating.text = meta
        val synopsis = item.overview?.takeIf { it.isNotBlank() } ?: ""
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.tmdbBannerSynopsis.text = synopsis
        binding.tmdbBannerSynopsis.isVisible = !isPortrait && synopsis.isNotBlank()
        binding.tmdbBannerSideSynopsis.text = synopsis
        binding.tmdbBannerSideSynopsis.isVisible = synopsis.isNotBlank()
        val genreText = item.genreIds.take(3).mapNotNull { genreNames[it] }.joinToString("  •  ")
        binding.tmdbBannerGenres.text = genreText
        binding.tmdbBannerGenres.isVisible = genreText.isNotBlank()
        binding.tmdbBannerStatus.isVisible = false
        binding.tmdbBannerStatusDivider.isVisible = false
        binding.tmdbBannerMetaDivider.isVisible = genreText.isNotBlank()
        populateSideChips(item)
        loadBannerLogo(item)
    }

    private fun populateSideChips(item: TmdbMedia) {
        val group = binding.tmdbBannerSideChips
        group.removeAllViews()
        item.genreIds.take(3).mapNotNull { genreNames[it] }.forEach { name ->
            val chip = TextView(requireContext()).apply {
                text = name
                textSize = 12f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setBackgroundResource(R.drawable.tmdb_chip_bg)
                setPadding(36, 10, 36, 10)
            }
            group.addView(chip)
        }
    }

    private fun loadBannerLogo(item: TmdbMedia) {
        logoJob?.cancel()
        logoJob = viewLifecycleOwner.lifecycleScope.launch {
            val detail = Tmdb.detail(item.type, item.id)
            val logo = detail?.let { Tmdb.logoUrl(it) }
            binding.tmdbBannerLogo.isVisible = logo != null
            if (logo != null) binding.tmdbBannerLogo.loadImage(logo)
            val portraitLogo = binding.tmdbBannerPortraitLogo
            portraitLogo.isVisible = logo != null
            binding.tmdbBannerTitle.isVisible = logo == null
            if (logo != null) portraitLogo.loadImage(logo)
            val status = detail?.status?.let { statusLabel(it) }.orEmpty()
            binding.tmdbBannerStatus.text = status
            binding.tmdbBannerStatus.isVisible = status.isNotBlank()
            binding.tmdbBannerStatusDivider.isVisible = status.isNotBlank()
        }
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "returning series", "returning" -> "Ongoing"
        "released" -> "Released"
        "planned" -> "Upcoming"
        "in production" -> "In Production"
        "ended", "canceled", "cancelled" -> "Completed"
        else -> status
    }

    private fun applyBannerLayout() {
        val ctx = requireContext()
        val isLandscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val card = binding.tmdbBannerCard
        val density = ctx.resources.displayMetrics.density

        if (isLandscape) {
            card.sizeBannerCard(0.65f)
            val (cardW, cardH) = card.bannerCardSizePx(0.65f)
            card.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.END or Gravity.TOP
            }
            val stripW = ctx.resources.displayMetrics.widthPixels - cardW
            // Exact anime landscape gradient: strip fade on the left + scrim over the card's left half
            binding.tmdbBannerFade.isVisible = true
            binding.tmdbBannerFade.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stripW
                height = cardH
                gravity = Gravity.START or Gravity.TOP
            }
            binding.tmdbBannerFade.bringToFront()
            binding.tmdbBannerFade.z = 10f
            binding.tmdbBannerCardScrim.isVisible = true
            binding.tmdbBannerCardScrim.layoutParams = binding.tmdbBannerCardScrim.layoutParams.apply {
                width = cardW / 2
                height = cardH
            }
            binding.tmdbBannerSide.isVisible = true
            binding.tmdbBannerSide.updateLayoutParams<FrameLayout.LayoutParams> {
                width = stripW + cardW / 4
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            binding.tmdbBannerSide.bringToFront()
            binding.tmdbBannerSide.z = 11f
            binding.tmdbBannerSideSynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = (stripW - 48 * density + cardW / 4).toInt().coerceAtLeast(1)
            }
            binding.tmdbBannerLogo.maxWidth = (stripW - 48 * density).toInt().coerceAtLeast(1)
            binding.tmdbBannerLogo.maxHeight = (cardH * 0.30f).toInt()
            binding.tmdbBannerImage.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.tmdbBannerContent.isVisible = false
        } else {
            card.sizeBannerCard()
            val (cardW, cardH) = card.bannerCardSizePx()
            card.updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = Gravity.CENTER
            }
            binding.tmdbBannerContent.updateLayoutParams<FrameLayout.LayoutParams> {
                width = cardW
                height = cardH
            }
            binding.tmdbBannerPortraitLogo.maxWidth = (cardW - 48 * density).toInt().coerceAtLeast(1)
            binding.tmdbBannerPortraitLogo.maxHeight = (cardH * 0.22f).toInt()
            binding.tmdbBannerFade.isVisible = false
            binding.tmdbBannerCardScrim.isVisible = false
            binding.tmdbBannerSide.isVisible = false
            binding.tmdbBannerContent.isVisible = true
            binding.tmdbBannerImage.scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun startAutoAdvance() {
        bannerHandler.removeCallbacksAndMessages(null)
        val mode = PrefManager.getVal<Int>(PrefName.HomeBannerMode)
        if (mode != 0 || bannerItems.size < 2) return
        bannerHandler.postDelayed(object : Runnable {
            override fun run() {
                showBanner((bannerIndex + 1) % bannerItems.size)
                bannerHandler.postDelayed(this, 6000)
            }
        }, 6000)
    }

    private fun openDetails(mediaType: String, id: Int) {
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
        )
    }

    class TmdbRowAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbRowAdapter.VH>() {

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

    /** Cards for plugin main-page rows — same layout and sizing as TMDB cards,
     *  but images come straight from the plugin (posterUrl is a full URL). */
    class PluginRowAdapter(
        private val items: List<SearchResponse>,
        private val onClick: (SearchResponse) -> Unit
    ) : RecyclerView.Adapter<PluginRowAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val (w, h) = if (landscape) {
                (260f * size).toInt() to (148f * size).toInt()
            } else {
                (102f * size).toInt() to (154f * size).toInt()
            }
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            b.tmdbCard.radius = TmdbCards.roundness()
            b.tmdbCardPoster.loadImage(item.posterUrl, if (landscape) 780 else 300)
            b.tmdbCardTitle.text = item.name
            b.tmdbCardTitle.isVisible = true
            b.tmdbCardYear.text = ""
            b.tmdbCardYear.isVisible = false
            b.tmdbCardGradient.isVisible = false
            b.tmdbCardLogo.isVisible = false
            b.tmdbCardOverlayTitle.isVisible = false
            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
