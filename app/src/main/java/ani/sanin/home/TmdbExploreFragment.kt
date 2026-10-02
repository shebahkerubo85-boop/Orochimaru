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
import ani.sanin.databinding.FragmentTmdbExploreBinding
import ani.sanin.databinding.ItemExploreRowBinding
import ani.sanin.databinding.ItemTmdbExplorePageBinding
import ani.sanin.databinding.LayoutTrendingBinding
import ani.sanin.getThemeColor
import ani.sanin.isClassicBanner
import ani.sanin.isModernBanner
import ani.sanin.isTvDevice
import ani.sanin.limitedAsyncMap
import ani.sanin.settings.saving.PrefManager
import ani.sanin.snackString
import ani.sanin.connections.trakt.Trakt
import androidx.core.app.ActivityOptionsCompat
import ani.sanin.media.Franchise
import ani.sanin.media.FranchiseActivity
import ani.sanin.media.FranchiseAdaptor
import ani.sanin.media.FranchiseListPrefs
import ani.sanin.media.FranchiseSort
import ani.sanin.media.showFranchiseSortDialog
import ani.sanin.media.read
import ani.sanin.media.FranchiseSorter
import ani.sanin.media.Media
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.MediaListViewActivity
import ani.sanin.setSafeOnClickListener
import ani.sanin.setSlideIn
import ani.sanin.setSlideUp
import ani.sanin.settings.saving.PrefName
import ani.sanin.sizeBannerCard
import ani.sanin.util.FocusEffectUtil
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min

/** Folds a TMDB title into the shared domain Media the banner carousel binds. */
internal fun TmdbMedia.toExploreMedia(): Media = Media(
    id = id,
    name = displayTitle,
    nameRomaji = displayTitle,
    userPreferredName = displayTitle,
    isAdult = false,
    banner = Tmdb.imageUrl(backdropPath, 1280) ?: Tmdb.imageUrl(posterPath, 780),
    cover = Tmdb.imageUrl(posterPath, 500),
    description = overview,
    // The banner's first chip is the format, and it comes from Media.format, which TMDB
    // never populated here — so the row opened on the score chip instead of saying what
    // kind of title it was. Upper case matches how the anime side stores it, and "TV" is
    // the value BannerCarouselAdapter already renders as "TV Series"; "movie" falls
    // through its own mapping untouched.
    format = type.uppercase(),
    // The classic banner builds its centred chip row from format, status, season, score
    // and genres. TMDB has no season and no clear logo on a list response, but genres are
    // there as ids — without them the banner's middle is empty but for a score chip.
    genres = ArrayList(Tmdb.genreNames(this)),
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

    /**
     * The Franchise cards that replaced the Popular list. Each card is one franchise; the
     * cards themselves follow the type chip like the rails do.
     */
    private lateinit var franchiseAdapter: FranchiseAdaptor

    /**
     * Every card the source returned, unsorted and unfiltered.
     *
     * The sort dialog reorders and re-filters this list locally instead of refetching: the
     * ranking lives on each card, so every ordering is a permutation of what is already here.
     */
    private var allFranchiseCards: List<Franchise> = emptyList()

    /**
     * One adapter and one backing list per row, created on the row's first load and
     * updated in place after that.
     *
     * Handing a rail a fresh MediaAdaptor on every load throws away every card it had
     * already inflated, so the whole row — and the page item around it — re-measures and
     * re-binds from scratch. That is the visible twitch on a chip change.
     */
    private val rowAdapters = mutableMapOf<Tmdb.ExploreRow, MediaAdaptor>()
    private val rowMedia = mutableMapOf<Tmdb.ExploreRow, ArrayList<Media>>()

    /** Guards the one-time setup in [TmdbExplorePageAdapter.onPageBound]. */
    private var pageBound = false

    /**
     * Bumped on every chip pick. A request that comes back after the viewer has already
     * moved on is dropped, so a slow row cannot land on top of the new chip's data.
     */
    private var loadGeneration = 0

    private var trendingMedia: List<TmdbMedia> = emptyList()
    private var bannerAdapter: BannerCarouselAdapter? = null
    private var bannerAdapterType = -1
    private var bannerSnap: PagerSnapHelper? = null
    private var bannerScrollListener: RecyclerView.OnScrollListener? = null
    private var bannerTitleShown = false
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
        // The rails are rebuilt against the new page item next time; keeping the old
        // adapters would hand a new page the previous view's list.
        rowAdapters.clear()
        rowMedia.clear()
        pageBound = false
        bannerTitleShown = false
        super.onDestroyView()
        _binding = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeBanner()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pageBound = false
        loadGeneration++
        bannerTitleShown = false

        // The page is one item and the Franchise cards hang off the end of it, the same
        // shape the Popular list used to have.
        pageAdapter = TmdbExplorePageAdapter()
        // The card opens the franchise screen, which is where the entries that did not fit this
        // row are reached. The view is passed for the shared-element transition into its hero.
        franchiseAdapter = FranchiseAdaptor { card, view -> openFranchise(card, view) }
        binding.tmdbExploreRecyclerView.adapter =
            ConcatAdapter(pageAdapter, franchiseAdapter)

        pageAdapter.onPageBound = { page ->
            pageBinding = page
            trendingBinding = LayoutTrendingBinding.bind(page.root)
            // Cheap and idempotent: put each rail's existing adapter back on this page's
            // rail. A recycled holder has freshly inflated, adapter-less rails, and
            // without this they would sit there empty.
            reattachRowAdapters()
            // ConcatAdapter rebinds the page whenever the Franchise cards below it change,
            // so this runs again on every reload. Re-running the setup from here would
            // reload again, which rebinds the page again: a loop that reloads the whole tab
            // forever. The wiring is per view, not per bind, so it runs once.
            if (!pageBound) {
                pageBound = true
                setupChips()
                setupStreamingRail()
                setupFocusChain()
                setupFranchiseHeader()
                selectType(ExploreType.MOVIE)
                loadStreamingServices()
            }
        }
    }

    /**
     * Re-points each row's RecyclerView at the adapter it already owns. No data is
     * touched, so a rebind of the page repaints without reloading anything.
     */
    private fun reattachRowAdapters() {
        for (row in railRows) {
            if (row == Tmdb.ExploreRow.POPULAR) continue
            val adapter = rowAdapters[row] ?: continue
            val rail = rowFor(row)?.first ?: continue
            rail.rowRecyclerView.layoutManager =
                LinearLayoutManager(rail.root.context, LinearLayoutManager.HORIZONTAL, false)
            rail.rowRecyclerView.adapter = adapter
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
        trendingBinding.trendingCard.sizeBannerCard(0.65f)
        // Before the scroll, same as the anime page: this sets the card's constraints and
        // the watch button's visibility, and doing it after the carousel has jumped to the
        // middle makes the banner re-layout under the viewer's thumb.
        applyTrendingBannerMode()
        applyTypeSelectorSpacing()

        val start = if (media.isEmpty()) 0
        else Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % media.size)
        rv.scrollToPosition(start)
        bannerScrollListener?.let { rv.removeOnScrollListener(it) }
        bannerScrollListener = object : RecyclerView.OnScrollListener() {
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
        }
        rv.addOnScrollListener(bannerScrollListener!!)
        setupTrendingDots(media.size)
        // The banner mode above ran before the carousel had a position, so the overlay for
        // the first visible card is filled in now.
        updateTrendingOverlayForCurrent()

        if (media.isNotEmpty()) {
            // Layout animation is a one-shot on the carousel's children. Re-assigning it
            // on every chip switch would replay the slide-in over and over.
            if (rv.layoutAnimation == null) {
                rv.layoutAnimation = LayoutAnimationController(setSlideIn(), 0.25f)
            }
            if (!bannerTitleShown) {
                bannerTitleShown = true
                trendingBinding.titleContainer.startAnimation(setSlideUp())
            }
            startTrendingAutoScroll(rv)
        }
        loadBannerLogos(bannerMedia)
    }

    /**
     * TMDB list responses carry no logo artwork, and BannerCarouselAdapter only shows the
     * clearlogo when it is handed one — otherwise it falls back to the plain text title.
     * Anime mode gets its logos from AniZip for free, so without this the movie banner
     * would be the only one on the app with no logo. Bounded parallelism keeps it from
     * firing one request per item at once.
     */
    private fun loadBannerLogos(bannerMedia: List<Media>) {
        if (bannerMedia.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val logos = bannerMedia.limitedAsyncMap(concurrency = 6) { media ->
                val type = media.tmdbType ?: return@limitedAsyncMap null
                media.id to runCatching { Tmdb.logoUrl(type, media.id) }.getOrNull()
            }.mapNotNull { it }.toMap()
            if (logos.isEmpty() || _binding == null) return@launch
            bannerAdapter?.updateUrls(emptyMap(), logos)
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

    private fun resizeBanner() {
        if (!::trendingBinding.isInitialized || trendingMedia.isEmpty()) return
        trendingBinding.trendingCard.sizeBannerCard(0.65f)
        trendingBinding.trendingContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 0
        }
        applyTrendingBannerMode()
        applyTypeSelectorSpacing()
        bannerAdapter?.notifyDataSetChanged()
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

    /**
     * The chip row's gap under the banner. The anime page needs 120dp here because its
     * season row is a wide, sparse strip; three short chips sitting that far from the
     * banner read as a hole rather than a group, so the gap is dropped and the banner
     * container's own 16dp bottom margin is the whole separation.
     */
    private fun applyTypeSelectorSpacing() {
        if (pageBinding == null) return
        page.tmdbExploreTypeScroll.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 0
        }
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

    private fun startTrendingAutoScroll(rv: RecyclerView) {
        trendingAutoScrollHandler?.removeCallbacksAndMessages(null)
        trendingAutoScrollHandler = Handler(Looper.getMainLooper())
        trendingAutoScrollRunnable = object : Runnable {
            override fun run() {
                if (trendingMedia.isEmpty()) return
                // A tick used to be able to land while the previous slide was still
                // travelling, and startSmoothScroll replaces the in-flight scroller, so
                // each tick cut the last one short and aimed again, so the banner crept
                // forward and never came to rest. Waiting for the settle forces one
                // slide, one pause.
                val lm = rv.layoutManager as? LinearLayoutManager
                if (lm != null && lm.isSmoothScrolling()) {
                    trendingAutoScrollHandler?.postDelayed(this, BANNER_SETTLE_POLL_MS)
                    return
                }
                val focus = (binding.root.context as? androidx.appcompat.app.AppCompatActivity)?.currentFocus
                val onBannerControl = focus != null && (
                    focus.id == R.id.trendingWatchBtn ||
                        focus.id == R.id.trendingViewPager ||
                        trendingBinding.trendingViewPager.findContainingViewHolder(focus) != null
                    )
                if (!onBannerControl) {
                    val pos = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION) rv.smoothScrollToPosition(pos + 1)
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
        // Everything already in flight belongs to the previous chip; drop it on arrival
        // rather than letting it land on top of this one.
        val generation = ++loadGeneration
        loadBannerFor(type, generation)
        // The rows and the Franchise cards follow the chip, same as the banner.
        loadRows(type, generation)
        loadFranchises(type, generation)
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

    private fun loadBannerFor(type: ExploreType, generation: Int) {
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
            if (generation != loadGeneration) return@launch
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
                Tmdb.ExploreRow.LATEST_RELEASE ->
                    page.rowLatestRelease to R.id.tmdbRowLatestRelease
                Tmdb.ExploreRow.UPCOMING -> page.rowUpcoming to R.id.tmdbRowUpcoming
                Tmdb.ExploreRow.POPULAR -> continue
            }
            rowBinding.rowRecyclerView.id = rowId
            rowBinding.rowRecyclerView.nextFocusUpId = aboveId
            rowBinding.rowMore.nextFocusUpId = aboveId
            aboveId = rowId
        }
        // The Franchise section's first card sits under the last rail.
        page.tmdbFranchiseHeader.nextFocusUpId = aboveId
    }

    /**
     * Replaces an adapter's contents without ever leaving the count and the list out of
     * step.
     *
     * MediaAdaptor binds with a hard [MutableList.get], so an item count that runs ahead
     * of the list crashes the prefetcher. Clearing the list *before* awaiting a request
     * opens exactly that window — the list is empty while the adapter still reports the
     * old count, and the next frame binds an index that is not there. So the caller
     * assembles the new items first, and this swaps them in with no suspension point
     * between the clear and the notify.
     */
    private fun swapAdapterList(
        list: MutableList<Media>,
        adapter: RecyclerView.Adapter<*>,
        items: List<Media>
    ) {
        val oldSize = list.size
        if (oldSize > 0) {
            list.clear()
            adapter.notifyItemRangeRemoved(0, oldSize)
        }
        if (items.isNotEmpty()) {
            list.addAll(items)
            adapter.notifyItemRangeInserted(0, items.size)
        }
    }

    // ---- Rows: five rails plus the Popular list, all driven by the type chip ----

    /** The five rails, in page order, each an include of the one shared row layout. */
    private fun rowFor(row: Tmdb.ExploreRow): Pair<ItemExploreRowBinding, Int>? {
        val page = pageBinding ?: return null
        val binding = when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> page.rowInCinema
            Tmdb.ExploreRow.TRENDING -> page.rowTrending
            Tmdb.ExploreRow.TOP_RATED -> page.rowTopRated
            Tmdb.ExploreRow.LATEST_RELEASE -> page.rowLatestRelease
            Tmdb.ExploreRow.UPCOMING -> page.rowUpcoming
            Tmdb.ExploreRow.POPULAR -> return null
        }
        return binding to when (row) {
            Tmdb.ExploreRow.IN_CINEMA -> R.string.in_cinema
            Tmdb.ExploreRow.TRENDING -> R.string.row_trending
            Tmdb.ExploreRow.TOP_RATED -> R.string.top_rated
            Tmdb.ExploreRow.LATEST_RELEASE -> R.string.latest_release
            Tmdb.ExploreRow.UPCOMING -> R.string.upcoming
            // Popular is no longer a row: the Franchise cards replaced it.
            Tmdb.ExploreRow.POPULAR -> R.string.row_franchise
        }
    }

    private val railRows = listOf(
        Tmdb.ExploreRow.IN_CINEMA,
        Tmdb.ExploreRow.TRENDING,
        Tmdb.ExploreRow.TOP_RATED,
        Tmdb.ExploreRow.LATEST_RELEASE,
        Tmdb.ExploreRow.UPCOMING
    )

    /**
     * Reloads every row for [type]. Each row is its own request so a slow one cannot hold
     * up the rest, which is why this fans out instead of awaiting in sequence.
     */
    private fun loadRows(type: ExploreType, generation: Int) {
        val tmdbType = when (type) {
            ExploreType.MOVIE -> "movie"
            ExploreType.TV -> "tv"
            ExploreType.ANIMATION -> "animation"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            railRows.forEach { row ->
                launch {
                    val media = runCatching { Tmdb.exploreRow(row, tmdbType) }.getOrDefault(emptyList())
                    if (generation != loadGeneration) return@launch
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
        // Only the first load of a row slides in. Re-animating on every chip change puts
        // ten running animations on screen at once, which reads as flicker.
        val firstLoad = row !in rowAdapters
        val wasHidden = !rowBinding.root.isVisible
        rowBinding.root.isVisible = true
        rowBinding.rowProgress.visibility = View.GONE
        rowBinding.rowTitle.setText(titleRes)
        val list = rowMedia.getOrPut(row) { ArrayList() }
        val adapter = rowAdapters.getOrPut(row) {
            val created = MediaAdaptor(0, list, requireActivity())
            rowBinding.rowRecyclerView.layoutManager = LinearLayoutManager(
                rowBinding.root.context, LinearLayoutManager.HORIZONTAL, false
            )
            rowBinding.rowRecyclerView.adapter = created
            created
        }
        // The list is filled and swapped without a suspension point, so the adapter's
        // count never runs ahead of the cards it can bind.
        swapAdapterList(list, adapter, media)
        // The row's arrow opens the same screen every other row's arrow opens.
        rowBinding.rowMore.setSafeOnClickListener {
            MediaListViewActivity.passedMedia = ArrayList(list)
            MediaListViewActivity.passedExploreRow = row
            MediaListViewActivity.passedExploreType = when (selectedType) {
                ExploreType.MOVIE -> "movie"
                ExploreType.TV -> "tv"
                ExploreType.ANIMATION -> "animation"
            }
            startActivity(
                Intent(requireContext(), MediaListViewActivity::class.java)
                    .putExtra("title", getString(titleRes))
            )
        }
        rowBinding.rowTitle.isVisible = true
        rowBinding.rowMore.isVisible = true
        rowBinding.rowRecyclerView.isVisible = true
        if (firstLoad || wasHidden) {
            rowBinding.rowTitle.startAnimation(setSlideUp())
            rowBinding.rowMore.startAnimation(setSlideUp())
        }
    }

    // ---- Franchise: cards, not a paginated catalogue ----

    private fun setupFranchiseHeader() {
        page.tmdbFranchiseHeader.isVisible = true

        // The pill shows the selected sort and opens the dropdown; the direction button is
        // separate, because inverting is a different action from choosing a sort.
        page.tmdbFranchiseSortPill.setSafeOnClickListener { openFranchiseSortDialog() }
        page.tmdbFranchiseSortDirection.setSafeOnClickListener { invertFranchiseSort() }
        updateFranchiseSortUi()
    }

    /**
     * The row's current sort, direction and single-entry choice, read from preferences.
     *
     * Read on demand rather than held in a field: the sort dialog is the only writer, and a
     * cached copy would go stale the moment it is dismissed without a change.
     */
    private fun franchisePrefs() = FranchiseListPrefs.read()

    /** Writes the pill's label from the persisted sort. */
    private fun updateFranchiseSortUi() {
        page.tmdbFranchiseSortPill.setText(franchisePrefs().sort.labelRes())
    }

    /**
     * Re-applies the current sort to the cards already loaded.
     *
     * Sorting is local, so it must not refetch: the ranking lives on the card and reordering is
     * just a permutation of the list in hand.
     */
    private fun applyFranchiseSort() {
        val prefs = franchisePrefs()
        val shuffled = if (prefs.sort == FranchiseSort.RANDOM) {
            allFranchiseCards.shuffled()
        } else {
            allFranchiseCards
        }
        franchiseAdapter.submit(FranchiseSorter.apply(allFranchiseCards, prefs, shuffled))
    }

    /**
     * Inverts the sort direction and says so.
     *
     * On random this cannot change the order, because a shuffle has no ascending or descending
     * form. It re-rolls and reports "Randomized" instead, so the user is never told the list
     * became ascending when what actually happened is that it was shuffled again.
     */
    private fun invertFranchiseSort() {
        val prefs = franchisePrefs()
        if (prefs.sort == FranchiseSort.RANDOM) {
            PrefManager.setVal(PrefName.FranchiseSortOrder, FranchiseSort.RANDOM.name)
            snackString(R.string.franchise_randomized, requireActivity())
        } else {
            val next = prefs.direction.inverted()
            PrefManager.setVal(PrefName.FranchiseSortDirectionPref, next.name)
            snackString(next.labelRes(), requireActivity())
        }
        applyFranchiseSort()
    }

    /** The sort dropdown, plus the "show single item entries" toggle, as specced. */
    private fun openFranchiseSortDialog() {
        showFranchiseSortDialog {
            updateFranchiseSortUi()
            applyFranchiseSort()
        }
    }
    /**
     * Loads the Franchise cards for the selected type chip.
     *
     * Replaces the old Popular list, which paged TMDB forever to stay in sync with the
     * anime list beside it. There is no pagination here: a card is a whole franchise, and
     * the entries that do not fit its row live on the dedicated franchise screen.
     */
    private fun loadFranchises(type: ExploreType, generation: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Trakt's curated lists are movies, so all three chips share one source and filter
            // client-side. TMDB cannot do this at all: `belongs_to_collection` is absent from
            // every list endpoint, and `/collection/list` is gone from v3.
            val cards = runCatching { Trakt.franchiseCards() }.getOrDefault(emptyList())
            // Everything already in flight belongs to the previous chip; drop it on arrival
            // rather than letting it land on top of this one.
            if (generation != loadGeneration) return@launch
            allFranchiseCards = cards
            applyFranchiseSort()
        }
    }


    /**
     * Opens the franchise screen for a pressed card.
     *
     * @param view the card's banner, so the screen can transition out of it rather than cutting.
     */
    private fun openFranchise(card: Franchise, view: View) {
        view.transitionName = FRANCHISE_TRANSITION
        startActivity(
            FranchiseActivity.intent(requireContext(), card),
            ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(),
                view,
                FRANCHISE_TRANSITION,
            ).toBundle()
        )
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

        /** Shared-element name for the card banner handing off to the franchise screen. */
        const val FRANCHISE_TRANSITION = "franchiseCard"
    }
}
