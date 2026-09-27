package ani.sanin.home

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LayoutAnimationController
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.bannerCardSizePx
import ani.sanin.cloudstream.StreamingServicesAdapter
import ani.sanin.cloudstream.TmdbAllServicesActivity
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbServiceCatalogueActivity
import ani.sanin.cloudstream.TmdbWatchActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.FragmentTmdbExploreBinding
import ani.sanin.databinding.ItemExploreRowBinding
import ani.sanin.databinding.ItemTmdbExplorePageBinding
import ani.sanin.databinding.LayoutTrendingBinding
import ani.sanin.getThemeColor
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import ani.sanin.isTvDevice
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaListViewActivity
import ani.sanin.setSafeOnClickListener
import ani.sanin.setSlideIn
import ani.sanin.setSlideUp
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min

/** Folds a TMDB title into the shared domain Media the banner carousel binds. */
private fun TmdbMedia.toExploreMedia(): Media = Media(
    id = id,
    name = displayTitle,
    nameRomaji = displayTitle,
    userPreferredName = displayTitle,
    isAdult = false,
    banner = Tmdb.imageUrl(backdropPath, 1280) ?: Tmdb.imageUrl(posterPath, 780),
    cover = Tmdb.imageUrl(posterPath, 500),
    description = overview,
    // TMDB votes are 0-10; the banner's score chip is a 0-100 percentage.
    meanScore = if (voteAverage > 0) (voteAverage * 10).toInt() else null,
    tmdbType = type
)

/**
 * The TMDB (movie) mode's Explore tab: the anime explore's banner and chip row, fed by
 * TMDB instead of AniList, with the streaming-services rail beneath the type chips.
 *
 * The banner reuses `layout_trending` and `BannerCarouselAdapter` exactly as the anime
 * page does, so the user's banner settings — classic / modern / compact, the blur slider,
 * focus, D-pad movement and click behaviour — all apply here too. Only the data source
 * differs: each TMDB title is folded into the domain [Media] the carousel already binds.
 *
 * The three chips replace the season selector; picking one reloads the banner for that
 * type. Portion 1 carries the banner, the chips and the rail; the content rows below the
 * rail are a later portion.
 */
class TmdbExploreFragment : Fragment() {

    private var _binding: FragmentTmdbExploreBinding? = null
    private val binding get() = _binding!!

    private lateinit var trendingBinding: LayoutTrendingBinding
    private lateinit var streamingAdapter: StreamingServicesAdapter

    /** The single page item; every chip and rail id lives in here, not in the fragment. */
    private var pageBinding: ItemTmdbExplorePageBinding? = null
    private val page get() = requireNotNull(pageBinding) { "explore page not bound" }
    private lateinit var pageAdapter: TmdbExplorePageAdapter
    private lateinit var popularAdapter: MediaAdaptor
    private val popularMedia = ArrayList<Media>()


    private var trendingMedia: List<TmdbMedia> = emptyList()
    private var bannerAdapter: BannerCarouselAdapter? = null
    private var bannerAdapterType = -1
    private var bannerSnap: PagerSnapHelper? = null
    private var trendingAutoIndex = 0
    private var trendingAutoScrollHandler: Handler? = null
    private var trendingAutoScrollRunnable: Runnable? = null

    private var selectedType: ExploreType = ExploreType.MOVIE
    private var selectedChip: Chip? = null
    private var unselectedChipBackground: ColorStateList? = null

    private enum class ExploreType {
        MOVIE, TV, ANIMATION
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbExploreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        pageBinding = null
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Same assembly as the anime explore: the page is one item, the Popular list hangs
        // off the end of it, and the pager's progress bar off that.
        pageAdapter = TmdbExplorePageAdapter()
        popularAdapter = MediaAdaptor(1, popularMedia, requireActivity())
        binding.tmdbExploreRecyclerView.adapter =
            ConcatAdapter(pageAdapter, popularAdapter)

        pageAdapter.onPageBound = { page ->
            pageBinding = page
            trendingBinding = LayoutTrendingBinding.bind(page.root)
            setupChips()
            setupStreamingRail()
            setupFocusChain()
            setupPopularHeader()
            selectType(ExploreType.MOVIE)
            loadStreamingServices()
        }
    }

    // ---- Banner: the reused layout_trending carousel, driven exactly like the anime page ----

    private fun setupBanner(media: List<TmdbMedia>) {
        trendingMedia = media
        trendingBinding.trendingProgressBar.visibility = View.GONE
        val rv = trendingBinding.trendingViewPager
        rv.layoutManager = LinearLayoutManager(rv.context, LinearLayoutManager.HORIZONTAL, false)
        // Detach the previous snap helper first; each chip pick rebuilds the carousel.
        bannerSnap?.attachToRecyclerView(null)
        bannerSnap = PagerSnapHelper().also { it.attachToRecyclerView(rv) }
        rv.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        rv.isFocusable = true
        rv.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rv.nextFocusDownId = R.id.tmdbExploreTypeScroll

        val bannerMedia = media.map { it.toExploreMedia() }
        bannerAdapter = BannerCarouselAdapter(
            items = bannerMedia,
            scope = CoroutineScope(Dispatchers.Main),
            onItemClick = { item -> openDetails(item) },
            nextFocusDownId = R.id.tmdbExploreTypeScroll,
            layoutRes = layoutForBannerType(isModernBanner()),
            // Classic and Modern carry their own watch pill, so the cardMode path that
            // hides it only applies to Compact.
            cardMode = !isModernBanner() && !isClassicBanner(),
            hideDescription = !isModernBanner(),
            modernMode = isModernBanner()
        )
        bannerAdapterType = ani.sanin.bannerType()
        rv.adapter = bannerAdapter

        val start = if (media.isEmpty()) 0
        else Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % media.size)
        rv.scrollToPosition(start)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var lastTarget = -1

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val overlay = trendingBinding.trendingOverlay
                if (!overlay.isVisible) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val child = lm.getChildAt(0) ?: return
                val pos = lm.getPosition(child)
                if (pos == RecyclerView.NO_POSITION) return
                val cardW = child.width
                val stripW = overlay.width - cardW / 4
                if (cardW <= 0 || stripW <= 0) return
                val progress = (-child.left).toFloat() / cardW
                val real = pos % media.size
                val target = if (progress < 0.5f) real else (real + 1) % media.size
                if (target != lastTarget) {
                    lastTarget = target
                    updateTrendingOverlay(media[target])
                }
                val scale = stripW.toFloat() / cardW
                overlay.translationX =
                    (if (progress < 0.5f) child.left else child.left + cardW) * scale
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    lastTarget = -1
                    trendingBinding.trendingOverlay.translationX = 0f
                    updateTrendingOverlayForCurrent()
                    resetTrendingAutoScroll()
                }
            }
        })
        setupTrendingDots(media.size)
        updateTrendingOverlayForCurrent()
        applyTrendingBannerMode()

        if (media.isNotEmpty()) {
            rv.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
            trendingBinding.titleContainer.startAnimation(setSlideUp())
            startTrendingAutoScroll(rv, start)
        }
    }

    private fun applyTrendingBannerMode() {
        val ctx = trendingBinding.root.context
        val isLandscape =
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val (cardW, cardH) = trendingBinding.trendingCard.bannerCardSizePx(0.65f)

        val cardLp = trendingBinding.trendingCard.layoutParams as ConstraintLayout.LayoutParams
        cardLp.startToStart = if (isLandscape) ConstraintSet.UNSET
        else ConstraintLayout.LayoutParams.PARENT_ID
        cardLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        trendingBinding.trendingCard.layoutParams = cardLp

        // Modern and Classic fill the whole width, so there is no left strip to fade and
        // no side panel — the banner carries the logo, chips and watch pill instead. Only
        // Compact keeps the side panel.
        val fullBleed = isModernBanner() || isClassicBanner()

        trendingBinding.trendingLeftFade.isVisible = isLandscape && !fullBleed
        if (isLandscape) {
            trendingBinding.trendingLeftFade.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = ctx.resources.displayMetrics.widthPixels - cardW
                height = cardH
            }
        }

        val overlay = trendingBinding.trendingOverlay
        if (isLandscape && !fullBleed) {
            overlay.isVisible = true
            val density = ctx.resources.displayMetrics.density
            val sidePad = (24 * density).toInt()
            val stripW = ctx.resources.displayMetrics.widthPixels - cardW
            overlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = stripW + cardW / 4
                height = cardH
            }
            overlay.setPadding(sidePad, 0, sidePad, 0)
            trendingBinding.trendingOverlayLogo.maxWidth =
                (stripW - sidePad * 2).coerceAtLeast(1)
            trendingBinding.trendingOverlayLogo.maxHeight = (cardH * 0.30f).toInt()
            trendingBinding.trendingOverlaySynopsis.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = (stripW - sidePad * 2 + cardW / 4).coerceAtLeast(1)
            }
            updateTrendingOverlayForCurrent()
        } else {
            overlay.isVisible = false
        }

        bannerAdapter?.setLandscapeMode(isLandscape, cardW)
        trendingBinding.trendingWatchBtn.isVisible = isLandscape && !fullBleed
        setupTrendingWatchBtn()
    }

    private fun setupTrendingWatchBtn() {
        val btn = trendingBinding.trendingWatchBtn
        btn.setOnClickListener { currentTrendingMedia()?.let { openWatch(it) } }
        btn.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    currentTrendingMedia()?.let { openWatch(it) }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveTrendingCarousel(keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    true
                }
                else -> false
            }
        }
        FocusEffectUtil.applyFocusListener(btn)
    }

    private fun currentTrendingMedia(): TmdbMedia? {
        if (trendingMedia.isEmpty()) return null
        val lm = trendingBinding.trendingViewPager.layoutManager as? LinearLayoutManager ?: return null
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos < 0) return null
        return trendingMedia[pos % trendingMedia.size]
    }

    private fun moveTrendingCarousel(forward: Boolean) {
        val rv = trendingBinding.trendingViewPager
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        rv.smoothScrollToPosition(pos + (if (forward) 1 else -1))
    }

    private fun updateTrendingOverlayForCurrent() {
        if (!::trendingBinding.isInitialized || trendingMedia.isEmpty()) return
        currentTrendingMedia()?.let { updateTrendingOverlay(it) }
    }

    private fun updateTrendingOverlay(media: TmdbMedia) {
        val overlay = trendingBinding.trendingOverlay
        if (!overlay.isVisible) return
        val title = trendingBinding.trendingOverlayTitle
        val logo = trendingBinding.trendingOverlayLogo
        // The TMDB page shows the title (TMDB backdrops have no clearlogo here), so the
        // title TextView stays visible and the logo slot stays empty.
        logo.isVisible = false
        title.isVisible = true
        title.text = media.displayTitle
        val chips = trendingBinding.trendingOverlayChips
        chips.removeAllViews()
        val meta = buildString {
            if (media.voteAverage > 0) {
                append("★ ").append(String.format(java.util.Locale.US, "%.1f", media.voteAverage))
                append("  •  ")
            }
            if (media.year.isNotBlank()) append(media.year).append("  •  ")
            append(media.type.replaceFirstChar { it.uppercase() })
        }
        addOverlayChip(chips, meta)
        val genres = trendingBinding.trendingOverlayGenres
        genres.removeAllViews()
        genres.isVisible = false
        val synopsis = trendingBinding.trendingOverlaySynopsis
        val desc = media.overview?.takeIf { it.isNotBlank() }
        synopsis.isVisible = !isModernBanner() && desc != null
        if (desc != null) synopsis.text = desc
    }

    private fun addOverlayChip(container: ViewGroup, text: String) {
        val chip = Chip(container.context).apply {
            this.text = text
            isClickable = false
            isCheckable = false
            isFocusable = false
            chipStrokeWidth = 0f
            setTextColor(container.context.getThemeColor(android.R.attr.textColorPrimary))
            chipBackgroundColor = ColorStateList.valueOf(0x33FFFFFF)
        }
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = (6 * chip.resources.displayMetrics.density).toInt()
        chip.layoutParams = lp
        container.addView(chip)
    }

    private fun setupTrendingDots(itemCount: Int) {
        val dots = trendingBinding.trendingDots
        dots.removeAllViews()
        if (itemCount <= 1) {
            dots.visibility = View.GONE
            return
        }
        val density = dots.resources.displayMetrics.density
        val isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val shown = if (isPortrait) min(itemCount, 7) else itemCount
        for (i in 0 until shown) {
            val dot = View(dots.context)
            val w = if (i == 0) (32 * density).toInt() else (12 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(w, (4 * density).toInt())
            lp.marginEnd = (6 * density).toInt()
            dot.layoutParams = lp
            dot.background = ContextCompat.getDrawable(
                dots.context,
                if (i == 0) R.drawable.banner_dot_active else R.drawable.banner_dot_inactive
            )
            dots.addView(dot)
        }
        dots.visibility = View.VISIBLE
    }

    private fun startTrendingAutoScroll(rv: RecyclerView, start: Int) {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler = Handler(Looper.getMainLooper())
        trendingAutoIndex = start
        trendingAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (trendingMedia.isEmpty()) return
                val focus = (binding.root.context as? androidx.appcompat.app.AppCompatActivity)?.currentFocus
                val onBannerControl = focus != null && (
                    focus.id == R.id.trendingWatchBtn ||
                        focus.id == R.id.trendingViewPager ||
                        trendingBinding.trendingViewPager.findContainingViewHolder(focus) != null
                    )
                if (!onBannerControl) {
                    trendingAutoIndex++
                    rv.smoothScrollToPosition(trendingAutoIndex)
                }
                trendingAutoScrollHandler?.postDelayed(this, 5000L)
            }
        }
        trendingAutoScrollHandler?.postDelayed(trendingAutoScrollRunnable!!, 5000L)
    }

    private fun resetTrendingAutoScroll() {
        val runnable = trendingAutoScrollRunnable ?: return
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler?.postDelayed(runnable, 5000L)
    }

    // ---- Type chips: Movie / TV show / Animation ----

    private fun setupChips() {
        page.tmdbExploreChipMovie.setSafeOnClickListener { selectType(ExploreType.MOVIE) }
        page.tmdbExploreChipTv.setSafeOnClickListener { selectType(ExploreType.TV) }
        page.tmdbExploreChipAnimation.setSafeOnClickListener {
            selectType(ExploreType.ANIMATION)
        }
    }

    private fun selectType(type: ExploreType) {
        selectedType = type
        val chip = when (type) {
            ExploreType.MOVIE -> page.tmdbExploreChipMovie
            ExploreType.TV -> page.tmdbExploreChipTv
            ExploreType.ANIMATION -> page.tmdbExploreChipAnimation
        }
        selectedChip?.takeIf { it !== chip }?.let { setChipSelected(it, false) }
        setChipSelected(chip, true)
        selectedChip = chip
        loadBannerFor(type)
        // The rows and the Popular list follow the chip, same as the banner.
        loadRows(type)
        loadPopular()
    }

    /** Same translucent-primary selected fill the season selector uses. */
    private fun setChipSelected(chip: Chip, selected: Boolean) {
        if (unselectedChipBackground == null) {
            unselectedChipBackground = chip.chipBackgroundColor
        }
        chip.chipBackgroundColor = if (selected) {
            ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    chip.context.getThemeColor(com.google.android.material.R.attr.colorPrimary),
                    SELECTED_FILL_ALPHA
                )
            )
        } else {
            unselectedChipBackground
                ?: ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        }
        chip.isSelected = selected
    }

    private fun loadBannerFor(type: ExploreType) {
        viewLifecycleOwner.lifecycleScope.launch {
            val media = when (type) {
                ExploreType.MOVIE -> Tmdb.trending("movie")
                ExploreType.TV -> Tmdb.trending("tv")
                // Animated movies AND animated shows — genre 16 on both. Anime sneaks in
                // here, but the point of the chip is animation, not anime.
                ExploreType.ANIMATION ->
                    Tmdb.discover("movie", genres = "16", sort = "popularity.desc") +
                        Tmdb.discover("tv", genres = "16", sort = "popularity.desc")
            }
            setupBanner(media)
        }
    }

    // ---- Streaming services rail ----

    private fun setupStreamingRail() {
        streamingAdapter = StreamingServicesAdapter(
            isTv = isTvDevice(requireContext()),
            onServiceClick = { provider -> openServiceCatalogue(provider) },
            onSeeAll = {
                startActivity(Intent(requireContext(), TmdbAllServicesActivity::class.java))
            }
        )
        page.tmdbExploreStreamingRail.adapter = streamingAdapter
    }

    /**
     * Opens a service's own catalogue, landing on the filter that matches the chip in
     * use: the TV chip carries the user to a service's shows, and the other two to its
     * films. Animation mixes both, so it starts on Movie and the filter does the rest.
     */
    private fun openServiceCatalogue(provider: TmdbProvider) {
        startActivity(
            Intent(requireContext(), TmdbServiceCatalogueActivity::class.java)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_ID, provider.id)
                .putExtra(TmdbServiceCatalogueActivity.ARG_PROVIDER_NAME, provider.displayName)
                .putExtra(
                    TmdbServiceCatalogueActivity.ARG_MEDIA_TYPE,
                    if (selectedType == ExploreType.TV) "tv" else "movie"
                )
        )
    }

    private fun loadStreamingServices() {
        viewLifecycleOwner.lifecycleScope.launch {
            val providers = Tmdb.watchProviders()
            // Nothing for the region: collapse rather than leave a titled empty strip.
            page.tmdbExploreStreamingRail.isVisible = providers.isNotEmpty()
            streamingAdapter.submit(providers)
        }
    }

    /**
     * The D-pad chain down the page: banner, type chips, services rail, then each row in
     * turn, then the Popular switch.
     *
     * Anime mode points every row's `nextFocusUp` at the id of the row above
     * (item_anime_page.xml). These five rows are five includes of one layout, so their
     * child ids repeat and an id would land on the first match rather than the row
     * meant — so each rail is tagged with its own id from ids.xml and the chain is
     * built from those. Down is left to the outer scroller's spatial search, which is
     * what the anime rows rely on too.
     */
    private fun setupFocusChain() {
        // Each row hangs off whatever is directly above it; the row's arrow shares the
        // rail's target so the header button does not jump a row when focus moves up.
        var aboveId: Int = R.id.tmdbExploreStreamingRail
        for (row in railRows) {
            val (rowBinding, rowId) = when (row) {
                Tmdb.ExploreRow.IN_CINEMA -> page.rowInCinema to R.id.tmdbRowInCinema
                Tmdb.ExploreRow.TRENDING -> page.rowTrending to R.id.tmdbRowTrending
                Tmdb.ExploreRow.TOP_RATED -> page.rowTopRated to R.id.tmdbRowTopRated
                Tmdb.ExploreRow.MOST_FAVOURITE ->
                    page.rowMostFavourite to R.id.tmdbRowMostFavourite
                Tmdb.ExploreRow.LATEST_RELEASE ->
                    page.rowLatestRelease to R.id.tmdbRowLatestRelease
                Tmdb.ExploreRow.POPULAR -> continue
            }
            rowBinding.rowRecyclerView.id = rowId
            rowBinding.rowRecyclerView.nextFocusUpId = aboveId
            rowBinding.rowMore.nextFocusUpId = aboveId
            aboveId = rowId
        }
        // The Popular switch sits under the last rail.
        page.tmdbIncludeList.nextFocusUpId = aboveId
    }

    // ---- Rows: five rails plus the Popular list, all driven by the type chip ----

    /** The five rails, in page order, each an include of the one shared row layout. */
    private fun rowFor(row: Tmdb.ExploreRow): Pair<ItemExploreRowBinding, Int>? {
        val page = pageBinding ?: return null
        val binding = when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> page.rowInCinema
            Tmdb.ExploreRow.TRENDING -> page.rowTrending
            Tmdb.ExploreRow.TOP_RATED -> page.rowTopRated
            Tmdb.ExploreRow.MOST_FAVOURITE -> page.rowMostFavourite
            Tmdb.ExploreRow.LATEST_RELEASE -> page.rowLatestRelease
            Tmdb.ExploreRow.POPULAR -> return null
        }
        return binding to when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> R.string.in_cinema
            Tmdb.ExploreRow.TRENDING -> R.string.row_trending
            Tmdb.ExploreRow.TOP_RATED -> R.string.top_rated
            Tmdb.ExploreRow.MOST_FAVOURITE -> R.string.most_favourite
            Tmdb.ExploreRow.LATEST_RELEASE -> R.string.latest_release
            Tmdb.ExploreRow.POPULAR -> R.string.row_popular
        }
    }

    private val railRows = listOf(
        Tmdb.ExploreRow.IN_CINEMA,
        Tmdb.ExploreRow.TRENDING,
        Tmdb.ExploreRow.TOP_RATED,
        Tmdb.ExploreRow.MOST_FAVOURITE,
        Tmdb.ExploreRow.LATEST_RELEASE
    )

    /**
     * Reloads every row for [type]. Each row is its own request so a slow one cannot hold
     * up the rest, which is why this fans out instead of awaiting in sequence.
     */
    private fun loadRows(type: ExploreType) {
        val tmdbType = when (type) {
            ExploreType.MOVIE -> "movie"
            ExploreType.TV -> "tv"
            ExploreType.ANIMATION -> "animation"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            railRows.forEach { row ->
                launch {
                    val media = runCatching { Tmdb.exploreRow(row, tmdbType) }.getOrDefault(emptyList())
                    bindRow(row, media.map { it.toExploreMedia() })
                }
            }
        }
    }

    /**
     * Shows a row, or hides it when the request came back empty — a titled rail with
     * nothing in it is worse than no row.
     */
    private fun bindRow(row: Tmdb.ExploreRow, media: List<Media>) {
        val target = rowFor(row) ?: return
        val (rowBinding, titleRes) = target
        if (media.isEmpty()) {
            rowBinding.root.isVisible = false
            return
        }
        rowBinding.root.isVisible = true
        rowBinding.rowProgress.visibility = View.GONE
        rowBinding.rowTitle.setText(titleRes)
        rowBinding.rowRecyclerView.adapter = MediaAdaptor(0, ArrayList(media), requireActivity())
        rowBinding.rowRecyclerView.layoutManager = LinearLayoutManager(
            rowBinding.root.context, LinearLayoutManager.HORIZONTAL, false
        )
        // The row's arrow opens the same screen every other row's arrow opens.
        rowBinding.rowMore.setSafeOnClickListener {
            MediaListViewActivity.passedMedia = ArrayList(media)
            startActivity(
                Intent(requireContext(), MediaListViewActivity::class.java)
                    .putExtra("title", getString(titleRes))
            )
        }
        rowBinding.rowTitle.isVisible = true
        rowBinding.rowMore.isVisible = true
        rowBinding.rowRecyclerView.isVisible = true
        rowBinding.rowTitle.startAnimation(setSlideUp())
        rowBinding.rowMore.startAnimation(setSlideUp())
    }

    // ---- Popular: a list, not a rail, with the include-list switch ----

    private fun setupPopularHeader() {
        val header = page.tmdbPopularHeader
        val toggle = page.tmdbIncludeList
        toggle.isChecked = PrefManager.getVal<Boolean>(PrefName.PopularMovieList)
        header.isVisible = true
        toggle.setOnCheckedChangeListener { _, checked ->
            PrefManager.setVal(PrefName.PopularMovieList, checked)
            loadPopular()
        }
        // The first load comes from selectType(), which runs right after this.
    }

    /**
     * The Popular list. With the switch on, the viewer's own library joins TMDB's
     * popularity list; the two are different things, so the row is not just a re-skin of
     * Most Favourite.
     */
    private fun loadPopular() {
        val tmdbType = when (selectedType) {
            ExploreType.MOVIE -> "movie"
            ExploreType.TV -> "tv"
            ExploreType.ANIMATION -> "animation"
        }
        val includeList = PrefManager.getVal<Boolean>(PrefName.PopularMovieList)
        viewLifecycleOwner.lifecycleScope.launch {
            popularMedia.clear()
            popularMedia.addAll(
                runCatching { Tmdb.exploreRow(Tmdb.ExploreRow.POPULAR, tmdbType) }
                    .getOrDefault(emptyList())
                    .map { it.toExploreMedia() }
            )
            if (includeList) {
                popularMedia.addAll(viewerListMedia())
            }
            popularAdapter.notifyDataSetChanged()
        }
    }

    /**
     * The viewer's saved movie-mode titles, which carry TMDB ids. Movie mode's library is
     * Simkl-backed, so that is where the viewer's own list comes from.
     */
    private suspend fun viewerListMedia(): List<Media> {
        if (Simkl.token == null) return emptyList()
        val items = runCatching {
            Simkl.getMovieLibrary() + Simkl.getShowLibrary()
        }.getOrDefault(emptyList())
        return items.mapNotNull { item ->
            val id = item.ids?.tmdb ?: return@mapNotNull null
            val type = item.mediaType ?: return@mapNotNull null
            if (type != "movie" && type != "tv") return@mapNotNull null
            Media(
                id = id,
                name = item.title ?: "",
                nameRomaji = item.title ?: "",
                userPreferredName = item.title ?: "",
                isAdult = false,
                cover = item.poster,
                tmdbType = type
            )
        }
    }


    // ---- Navigation: banner click + watch both open the TMDB screens ----

    private fun openDetails(media: Media) {
        val type = media.tmdbType ?: "movie"
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun openWatch(media: TmdbMedia) {
        startActivity(
            Intent(requireContext(), TmdbWatchActivity::class.java)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_TYPE, media.type)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private companion object {
        const val SELECTED_FILL_ALPHA = 0x4D
    }
}
