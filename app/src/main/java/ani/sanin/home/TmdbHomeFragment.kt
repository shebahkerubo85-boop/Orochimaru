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
import ani.sanin.Refresh
import ani.sanin.connections.simkl.Simkl
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
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbHomeFragment : Fragment() {

    /** Wrapper for banner items: either TMDB trending or plugin live items. */
    sealed class BannerItem {
        data class Tmdb(val media: TmdbMedia) : BannerItem()
        data class Plugin(
            val response: SearchResponse,
            val sourceId: String,
            val backdropUrl: String? = null,
            val tmdbId: Int? = null,
            val tmdbType: String? = null
        ) : BannerItem()

        val title: String get() = when (this) {
            is Tmdb -> media.displayTitle
            is Plugin -> response.name
        }
        val bannerUrl: String? get() = when (this) {
            is Tmdb -> media.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
            is Plugin -> backdropUrl ?: response.posterUrl
        }
        val year: String get() = when (this) {
            is Tmdb -> media.year
            is Plugin -> ""
        }
        val type: String get() = when (this) {
            is Tmdb -> media.type
            is Plugin -> ""
        }
        val overview: String? get() = when (this) {
            is Tmdb -> media.overview
            is Plugin -> null
        }
    }

    private var _binding: FragmentTmdbHomeBinding? = null
    private val binding get() = _binding!!
    private val bannerItems = mutableListOf<BannerItem>()
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
            val item = bannerItems.getOrNull(bannerIndex) ?: return@setOnClickListener
            when (item) {
                is BannerItem.Tmdb -> openDetails(item.media.type, item.media.id)
                is BannerItem.Plugin -> {
                    if (item.tmdbId != null && item.tmdbType != null) {
                        openDetails(item.tmdbType, item.tmdbId)
                    } else {
                        startActivity(
                            Intent(requireContext(), TmdbDetailsActivity::class.java)
                                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, item.sourceId)
                                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.response.url)
                        )
                    }
                }
            }
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
        // Observe Refresh signals so CW/sections reload after scrobble or list edits
        val live = Refresh.activity.getOrPut(requireActivity().hashCode()) { androidx.lifecycle.MutableLiveData(true) }
        live.observe(viewLifecycleOwner) { if (it == true) { load(); live.postValue(false) } }
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
        val plugin = if (sourceId == "tmdb") null
            else CsRepos.installed(requireContext()).firstOrNull { it.id == sourceId }
        // Banner: always TMDB trending (plugin hero is rendered as first section).
        // Sections: plugin's own if available, else TMDB fallback — never empty.
        viewLifecycleOwner.lifecycleScope.launch {
            loadBanner(plugin)
            loadSimklContinueWatching()
            if (plugin != null) {
                val gotSections = loadPluginSections(plugin)
                if (!gotSections) loadTmdbSections()
            } else {
                loadTmdbSections()
            }
        }
    }

    /** Loads banner: plugin items for live sources, TMDB trending otherwise. */
    private suspend fun loadBanner(plugin: CsInstalledSource? = null) {
        genreNames = withContext(Dispatchers.IO) { Tmdb.genres().associate { it.id to it.name } }
        bannerItems.clear()
        if (plugin != null) {
            val apis = withContext(Dispatchers.IO) {
                CsRuntime.apisFor(requireContext(), plugin)
            }
            for (api in apis) {
                // 1) Try provider's declared mainPage entries
                val pages = api.mainPage.filter { it.data.isNotBlank() }
                for (page in pages) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        list.list.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                    if (bannerItems.isNotEmpty()) break
                }
                // 2) Fallback: synthetic getMainPage
                if (bannerItems.isEmpty()) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest("Home", "", false))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        list.list.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                }
                // 3) Final fallback: quickSearch
                if (bannerItems.isEmpty()) {
                    val quick = runCatching {
                        withContext(Dispatchers.IO) { api.quickSearch("") }
                    }.getOrNull()
                    if (!quick.isNullOrEmpty()) {
                        quick.take(10).forEach { sr ->
                            bannerItems.add(BannerItem.Plugin(sr, plugin.id))
                        }
                    }
                }
                if (bannerItems.isNotEmpty()) break
            }
            // Like anime mode: plugin = AniList (data), TMDB = AniZip (backdrop images)
            // Fetch TMDB backdrop for each plugin item by name search
            val itemsToLookup = bannerItems.filterIsInstance<BannerItem.Plugin>().take(10)
            bannerItems.clear()
            for (pluginItem in itemsToLookup) {
                val tmdbResults = withContext(Dispatchers.IO) {
                    runCatching { Tmdb.search(pluginItem.response.name) }.getOrNull()
                }
                val match = tmdbResults?.firstOrNull()
                if (match != null) {
                    val backdrop = match.backdropPath?.let { Tmdb.imageUrl(it, 780) }
                    bannerItems.add(pluginItem.copy(
                        backdropUrl = backdrop,
                        tmdbId = match.id,
                        tmdbType = match.type
                    ))
                } else {
                    bannerItems.add(pluginItem)
                }
            }
        } else {
            val trendingSeries = withContext(Dispatchers.IO) { Tmdb.trending("tv", "week") }
            val trendingMovies = withContext(Dispatchers.IO) { Tmdb.trending("movie", "week") }
            bannerItems.addAll(trendingSeries.map { BannerItem.Tmdb(it) })
            bannerItems.addAll(trendingMovies.map { BannerItem.Tmdb(it) })
        }
        if (bannerItems.isNotEmpty()) showBanner(0)
    }

    /** Fetches TMDB browse rows as the default home content. */
    private suspend fun loadTmdbSections() {
        val trendingSeries = withContext(Dispatchers.IO) { Tmdb.trending("tv", "week") }
        val trendingMovies = withContext(Dispatchers.IO) { Tmdb.trending("movie", "week") }
        val latestSeries = withContext(Dispatchers.IO) { Tmdb.latestSeries() }
        val latestMovies = withContext(Dispatchers.IO) { Tmdb.latestMovies() }
        val popular = withContext(Dispatchers.IO) { Tmdb.popular() }
        val topRated = withContext(Dispatchers.IO) { Tmdb.topRated() }
        addSection("Trending Series", trendingSeries)
        addSection("Trending Movies", trendingMovies)
        addSection("Latest Series", latestSeries)
        addSection("Latest Movies", latestMovies)
        addSection("Popular", popular)
        addSection("Top Rated", topRated)
        startAutoAdvance()
    }

    /** Loads plugin home sections (CloudStream-style). Returns true if any
     *  sections were added. Mimics Zangetsu: first section → hero carousel,
     *  remaining → browse rows; providers with no mainPage get a synthetic
     *  request fallback, and a quickSearch fallback synthesises trending rows. */
    private suspend fun loadPluginSections(source: CsInstalledSource): Boolean {
        val apis = withContext(Dispatchers.IO) {
            CsRuntime.apisFor(requireContext(), source)
        }
        if (apis.isEmpty()) return false
        var added = false
        for (api in apis) {
            // 1) Try the provider's declared mainPage entries.
            val pages = api.mainPage.filter { it.data.isNotBlank() }
            if (pages.isNotEmpty()) {
                for (page in pages) {
                    val resp = runCatching {
                        withContext(Dispatchers.IO) {
                            api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
                        }
                    }.getOrNull()
                    resp?.items?.forEach { list ->
                        if (list.list.isNotEmpty()) {
                            addPluginSection(list.name, list.list, source)
                            added = true
                        }
                    }
                }
            } else {
                // 2) Fallback: try a synthetic getMainPage (some providers override it
                //    with hardcoded logic even when mainPage is empty).
                val resp = runCatching {
                    withContext(Dispatchers.IO) {
                        api.getMainPage(1, MainPageRequest("Home", "", false))
                    }
                }.getOrNull()
                resp?.items?.forEach { list ->
                    if (list.list.isNotEmpty()) {
                        addPluginSection(list.name, list.list, source)
                        added = true
                    }
                }
            }
            // 3) Final fallback: quickSearch synthesises trending rows (Zangetsu-style).
            if (!added) {
                val quick = runCatching {
                    withContext(Dispatchers.IO) { api.quickSearch("") }
                }.getOrNull()
                if (!quick.isNullOrEmpty()) {
                    addPluginSection("Popular", quick, source)
                    added = true
                }
            }
        }
        return added
    }

    /** Loads Simkl "continue watching" items (TV/movie only, not anime). */
    private suspend fun loadSimklContinueWatching() {
        if (Simkl.token == null) return
        val items = withContext(Dispatchers.IO) { Simkl.getContinueWatching() }
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = "Continue Watching"
            setPadding(24, 14, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = SimklContinueWatchingLandscapeAdapter(items) { item ->
                val tmdbId = item.ids?.tmdb ?: return@SimklContinueWatchingLandscapeAdapter
                val mediaType = item.mediaType ?: "tv"
                startActivity(
                    Intent(requireContext(), ani.sanin.cloudstream.TmdbWatchActivity::class.java)
                        .putExtra(ani.sanin.cloudstream.TmdbWatchActivity.ARG_MEDIA_TYPE, mediaType)
                        .putExtra(ani.sanin.cloudstream.TmdbWatchActivity.ARG_MEDIA_ID, tmdbId)
                )
            }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
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
            setPadding(24, 14, 24, 8)
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
            setPadding(24, 14, 24, 8)
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
        binding.tmdbBannerImage.loadImage(item.bannerUrl)
        // Default centerCrop works for both TMDB backdrops and plugin TMDB-looked-up backdrops
        binding.tmdbBannerImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        val meta = buildString {
            if (item is BannerItem.Tmdb && item.media.voteAverage > 0)
                append("★ ").append(String.format("%.1f", item.media.voteAverage)).append("  •  ")
            if (item.year.isNotBlank()) append(item.year).append("  •  ")
            append(item.type.replaceFirstChar { it.uppercase() })
        }
        binding.tmdbBannerTitle.text = item.title
        binding.tmdbBannerSideMeta.text = meta
        binding.tmdbBannerRating.text = meta
        val synopsis = item.overview?.takeIf { it.isNotBlank() } ?: ""
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.tmdbBannerSynopsis.text = synopsis
        binding.tmdbBannerSynopsis.isVisible = !isPortrait && synopsis.isNotBlank()
        binding.tmdbBannerSideSynopsis.text = synopsis
        binding.tmdbBannerSideSynopsis.isVisible = synopsis.isNotBlank()
        val genreText = when (item) {
            is BannerItem.Tmdb -> item.media.genreIds.take(3).mapNotNull { genreNames[it] }.joinToString("  •  ")
            is BannerItem.Plugin -> ""
        }
        binding.tmdbBannerGenres.text = genreText
        binding.tmdbBannerGenres.isVisible = genreText.isNotBlank()
        binding.tmdbBannerStatus.isVisible = false
        binding.tmdbBannerStatusDivider.isVisible = false
        binding.tmdbBannerMetaDivider.isVisible = genreText.isNotBlank()
        when (item) {
            is BannerItem.Tmdb -> {
                populateSideChips(item.media)
                loadBannerLogo(item.media)
            }
            is BannerItem.Plugin -> {
                binding.tmdbBannerSideChips.removeAllViews()
                if (item.tmdbId != null && item.tmdbType != null) {
                    loadBannerLogoByType(item.tmdbType, item.tmdbId)
                } else {
                    logoJob?.cancel()
                    binding.tmdbBannerLogo.isVisible = false
                    binding.tmdbBannerPortraitLogo.isVisible = false
                }
            }
        }
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

    private fun loadBannerLogoByType(type: String, id: Int) {
        logoJob?.cancel()
        logoJob = viewLifecycleOwner.lifecycleScope.launch {
            val detail = Tmdb.detail(type, id)
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

        private val cardScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        private val titlePos: Int get() = PrefManager.getVal(PrefName.CardTitlePosition)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val isLive = item is LiveSearchResponse
            val (w, h) = if (landscape || isLive) {
                (260f * size).toInt() to (148f * size).toInt()
            } else {
                (102f * size).toInt() to (154f * size).toInt()
            }
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            b.tmdbCard.radius = TmdbCards.roundness()
            b.tmdbCardPoster.loadImage(item.posterUrl, if (landscape || isLive) 780 else 300)

            // Reset before async loads
            b.tmdbCardLogo.isVisible = false
            b.tmdbCardOverlayTitle.isVisible = false

            if (landscape) {
                b.tmdbCardPoster.loadImage(item.posterUrl, 780)
                b.tmdbCardPoster.tag = item.name
                when (titlePos) {
                    0 -> {
                        b.tmdbCardGradient.isVisible = true
                        b.tmdbCardGradient.updateLayoutParams<ViewGroup.LayoutParams> {
                            width = w; height = h
                        }
                        TmdbCards.setCardGradient(b.tmdbCardGradient)
                        b.tmdbCardOverlayTitle.isVisible = true
                        b.tmdbCardOverlayTitle.text = item.name
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    2 -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    else -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardTitle.isVisible = true
                        b.tmdbCardTitle.text = item.name
                        b.tmdbCardYear.isVisible = false
                    }
                }
                // Async TMDB backdrop + logo for plugin items
                cardScope.launch {
                    val name = item.name ?: return@launch
                    val results = runCatching { ani.sanin.connections.tmdb.Tmdb.search(name) }.getOrNull()
                    val match = results?.firstOrNull() ?: return@launch
                    val backdrop = match.backdropPath?.let { ani.sanin.connections.tmdb.Tmdb.imageUrl(it, 780) }
                    val logoUrl = runCatching { ani.sanin.connections.tmdb.Tmdb.logoUrl(match.type, match.id) }.getOrNull()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (holder.binding.tmdbCardPoster.tag != item.name) return@withContext
                        if (!backdrop.isNullOrBlank()) b.tmdbCardPoster.loadImage(backdrop)
                        if (!logoUrl.isNullOrBlank() && titlePos == 0) {
                            b.tmdbCardLogo.isVisible = true
                            b.tmdbCardLogo.loadImage(logoUrl)
                            b.tmdbCardOverlayTitle.isVisible = false
                            b.tmdbCardTitle.isVisible = false
                        }
                    }
                }
            } else {
                b.tmdbCardPoster.loadImage(item.posterUrl, 300)
                // Portrait: always title below
                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
                b.tmdbCardTitle.isVisible = true
                b.tmdbCardTitle.text = item.name
                b.tmdbCardYear.isVisible = false
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
