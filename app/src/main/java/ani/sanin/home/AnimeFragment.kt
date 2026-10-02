package ani.sanin.home

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.anilist.AnilistAnimeViewModel
import ani.sanin.connections.anilist.getUserId
import ani.sanin.databinding.FragmentAnimeBinding
import ani.sanin.connections.anilist.AniMangaSearchResults
import ani.sanin.connections.kitsu.Kitsu
import androidx.core.app.ActivityOptionsCompat
import ani.sanin.connections.anilist.AnilistFranchiseRanks
import ani.sanin.media.Franchise
import ani.sanin.util.Logger
import ani.sanin.media.FranchiseActivity
import ani.sanin.media.FranchiseAdaptor
import ani.sanin.media.FranchiseListPrefs
import ani.sanin.media.FranchiseSort
import ani.sanin.media.showFranchiseSortDialog
import ani.sanin.media.read
import ani.sanin.media.FranchiseSorter
import ani.sanin.media.FranchiseStub
import ani.sanin.media.onNearEnd
import ani.sanin.setSafeOnClickListener
import ani.sanin.media.MediaAdaptor
import ani.sanin.media.ProgressAdapter
import ani.sanin.media.SearchActivity
import ani.sanin.navBarHeight
import ani.sanin.px
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.statusBarHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min


class AnimeFragment : Fragment() {
    private var _binding: FragmentAnimeBinding? = null
    private val binding get() = _binding!!
    private lateinit var animePageAdapter: AnimePageAdapter

    /** The row's adapter, held so a page landing later can re-sort itself into place. */
    private var animeFranchiseAdapter: FranchiseAdaptor? = null

    /** The header listeners are attached once; `ready` can fire again on a rebind. */
    private var animeFranchiseSortWired = false

    /**
     * Counts ranking requests, so only the newest one may write to the row.
     *
     * Without it, picking Trending and then Most Popular quickly can let the trending reply
     * land last and overwrite the popular order the user is now looking at.
     */
    private var animeFranchiseSortGeneration = 0

    /**
     * How far into the loaded AniList results the row has claimed titles from.
     *
     * The results list grows as pages land but never reorders, so an index into it is stable
     * across pages and one cursor serves the whole session. The row claims [SEED_BATCH] of them
     * per call and takes more as the user scrolls, rather than turning a whole 50-title page
     * into Kitsu requests before the first card can appear.
     */
    private var animeFranchiseCursor = 0

    /**
     * Turns the loaded AniList titles into Franchise cards and hands them to the adapter.
     *
     * Each loaded title is a seed. Kitsu resolves the seed into its real franchise, and
     * AniList relations are only the fallback for when Kitsu is unreachable or has no
     * mapping, so the row still fills.
     *
     * @param from index of the first seed to consider, so a later AniList page only adds its
     *   own cards instead of rebuilding every card already on screen.
     */
    private fun publishFranchises(adaptor: FranchiseAdaptor, trigger: String) {
        // Snapshot on the main thread: `results` keeps growing as later pages land, and
        // reading it from a background thread would race that mutation.
        val snapshot = model.aniMangaSearchResults.results.toList()
        Logger.log(
            "Franchise(anime) publish on $trigger: cursor=$animeFranchiseCursor of " +
                "${snapshot.size} loaded results"
        )
        if (snapshot.isEmpty()) {
            adaptor.submit(emptyList())
            Logger.log("Franchise(anime) publish: no results at all, submitted empty row")
            return
        }
        // A result list that shrank is a different list, not a shorter one: a new search, genre
        // or filter replaces it, and a cursor measured against the old one points into the
        // middle of the new results and would skip them. Same thing after a rotation, where the
        // cursor survives but the adapter it was feeding does not. Either way the row restarts.
        if (snapshot.size < animeFranchiseCursor) {
            Logger.log(
                "Franchise(anime) publish: results at ${snapshot.size} but cursor is at " +
                    "$animeFranchiseCursor, so this is a new list; restarting from the top"
            )
            animeFranchiseCursor = 0
            adaptor.clear()
        }
        if (animeFranchiseCursor >= snapshot.size) {
            Logger.log("Franchise(anime) publish: cursor $animeFranchiseCursor already covers ${snapshot.size}, nothing to do")
            return
        }

        // At most one batch of seeds per call. Kitsu is five requests a seed, so claiming a
        // whole 50-title page up front is what made the first card take minutes; the row takes
        // a page's worth of titles and asks for the rest as the user scrolls.
        val end = min(animeFranchiseCursor + SEED_BATCH, snapshot.size)
        val window = (animeFranchiseCursor until end).mapNotNull { snapshot.getOrNull(it) }
        animeFranchiseCursor = end

        // A new page re-reports seeds that are already in flight or already on screen.
        // Re-walking Kitsu for those is the request cost this change exists to avoid.
        val seeds = window.filterNot { adaptor.hasCardFor(it.id) || adaptor.isPending(it.id) }
        if (seeds.isEmpty()) {
            Logger.log("Franchise(anime) publish: ${window.size} in window, all already done or pending")
            return
        }

        // markPending claims the seeds, so a concurrent page overlap cannot race in on the
        // same ones between the filter above and this call.
        val claimed = adaptor.markPending(seeds.map { it.id })
            .mapNotNull { id -> seeds.firstOrNull { it.id == id } }
        if (claimed.isEmpty()) {
            Logger.log("Franchise(anime) publish: ${seeds.size} seeds, none could be claimed")
            return
        }
        Logger.log("Franchise(anime) publish: resolving ${claimed.size} seeds ${claimed.map { it.id }}")

        // A placeholder for every seed that has relations, published before a single request
        // goes out. This is pure work on data the AniList page already carries, so the row has
        // something in it on the next frame. Waiting for Kitsu first is what made the row look
        // empty: each seed costs Kitsu five sequential requests, so a 50-seed page took minutes
        // before the first card could be built at all.
        val stubs = claimed.mapNotNull { seed ->
            FranchiseStub.fromMedia(seed)?.let { card -> seed.id to card }
        }
        val stubByName = stubs.associate { (_, card) -> card.name to card }
        val stubNames = stubs.associate { (id, card) -> id to card.name }
        if (stubNames.isNotEmpty()) {
            // Keyed by name, not by seed id: upsert replaces by the name a card went out under.
            adaptor.upsert(stubByName)
            Logger.log("Franchise(anime) published ${stubNames.size} placeholder cards immediately")
        }

        // Kitsu then upgrades the placeholders it can answer for and supplies cards for the
        // seeds that had no relations to stub from. Bounded rather than serial, because the whole
        // cost of this row is the number of round trips and each one is independent.
        lifecycleScope.launch {
            claimed.chunked(KITSU_CONCURRENCY * 2).forEach { chunk ->
                val resolved = withContext(Dispatchers.IO) {
                    val gate = Semaphore(KITSU_CONCURRENCY)
                    chunk.map { seed -> async { gate.withPermit { Kitsu.franchiseCard(seed.id) } } }
                        .awaitAll()
                }
                val upgrades = mutableMapOf<String, Franchise>()
                chunk.forEachIndexed { index, seed ->
                    val card = resolved[index] ?: return@forEachIndexed
                    // Replace the placeholder by the name it was published under, so a
                    // franchise whose resolved name differs from its seed's still lands in the
                    // right slot rather than beside it.
                    val provisional = stubNames[seed.id] ?: card.name
                    upgrades[provisional] = card
                }
                if (upgrades.isNotEmpty()) adaptor.upsert(upgrades)
                Logger.log(
                    "Franchise(anime) resolved ${resolved.count { it != null }}/${chunk.size} " +
                        "in chunk, row now ${adaptor.currentCards().size} cards"
                )
            }
            adaptor.markDone(claimed.map { it.id })
            Logger.log("Franchise(anime) built row of ${adaptor.currentCards().size} cards from ${claimed.size} seeds")
            // Re-sort once everything has landed so the row ends in the user's chosen order.
            // The incremental upserts above append in resolution order, which is arrival order.
            sortAnimeFranchises()
        }
    }

    /** The row's sort, direction and single-entry choice, read fresh from preferences. */
    private fun animeFranchisePrefs() = FranchiseListPrefs.read()

    /**
     * Re-submits every card in the row's current order.
     *
     * Sorting is local, so nothing is refetched here: the ranking lives on each card and every
     * ordering is a permutation of what the adapter already holds. The random case is the one
     * exception, since its permutation comes from a fresh shuffle.
     */
    private fun applyAnimeFranchiseSort(ranks: AnilistFranchiseRanks.Ranks? = null) {
        val adaptor = animeFranchiseAdapter ?: return
        val prefs = animeFranchisePrefs()
        val ranked = when {
            ranks == null -> adaptor.currentCards()
            prefs.sort == FranchiseSort.POPULAR ->
                AnilistFranchiseRanks.applyPopular(adaptor.currentCards(), ranks)
            else -> AnilistFranchiseRanks.applyTrending(adaptor.currentCards(), ranks)
        }
        val shuffled = if (prefs.sort == FranchiseSort.RANDOM) ranked.shuffled() else ranked
        val sorted = FranchiseSorter.apply(ranked, prefs, shuffled)
        // The order is logged, not just the counts: "Popular" and "Trending" both lean on a
        // ranking fetched off-screen, and a row that reorders is otherwise indistinguishable
        // from one that did nothing.
        Logger.log(
            "Franchise(anime) sort=${prefs.sort} dir=${prefs.direction} " +
                "showSingle=${prefs.showSingleEntry} ranked=${ranks != null}: ${ranked.size} in, " +
                "${sorted.size} out, first=${sorted.take(5).map { it.name }}"
        )
        adaptor.submit(sorted)
    }

    /**
     * Sorts the row, fetching an AniList ranking first if the chosen sort needs one.
     *
     * Kitsu groups the entries well but reports no popularity or trending figure on any of them,
     * so Trending and Most Popular have nothing to order by until AniList supplies a position.
     * That ranking is one cached query for the process, not one request per card, and it is
     * fetched only for the two sorts that need it.
     */
    private fun sortAnimeFranchises() {
        val wanted = animeFranchisePrefs().sort
        val needsRanks = wanted == FranchiseSort.TRENDING || wanted == FranchiseSort.POPULAR
        if (!needsRanks) return applyAnimeFranchiseSort()

        val generation = ++animeFranchiseSortGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            val ranks = when (wanted) {
                FranchiseSort.TRENDING -> AnilistFranchiseRanks.trending()
                FranchiseSort.POPULAR -> AnilistFranchiseRanks.popular()
                else -> return@launch
            }
            // The sort may have changed, or another ranking may have landed, while this one was
            // in flight. Only the newest request is allowed to touch the row.
            if (generation != animeFranchiseSortGeneration) return@launch
            if (animeFranchisePrefs().sort != wanted) return@launch
            applyAnimeFranchiseSort(ranks)
        }
    }

    /**
     * The sort pill, direction control and dialog for the anime Franchise row.
     *
     * Same behaviour as the movie row. The difference is in the data: these cards arrive a page
     * at a time, and Trending and Most Popular need AniList to rank them.
     */
    private fun setupAnimeFranchiseSort() {
        if (animeFranchiseSortWired) return
        // The pill and direction button live in the page item layout AnimePageAdapter inflates,
        // so they are reached through *its* binding, not the fragment's and not the franchise
        // adapter's. Only the row's own adapter is needed as a field, for re-sorting.
        val adaptor = animeFranchiseAdapter ?: return
        animeFranchiseSortWired = true
        val pill = animePageAdapter.binding.animeFranchiseSortPill
        val direction = animePageAdapter.binding.animeFranchiseSortDirection

        fun updatePill() {
            pill.setText(animeFranchisePrefs().sort.labelRes())
        }

        pill.setSafeOnClickListener {
            showFranchiseSortDialog {
                updatePill()
                sortAnimeFranchises()
            }
        }

        direction.setSafeOnClickListener {
            val current = animeFranchisePrefs()
            if (current.sort == FranchiseSort.RANDOM) {
                // A shuffle has no ascending or descending form, so this cannot change the
                // order. It re-rolls, and says so rather than claiming a direction that does
                // not apply.
                snackString(R.string.franchise_randomized, requireActivity())
            } else {
                val next = current.direction.inverted()
                PrefManager.setVal(PrefName.FranchiseSortDirectionPref, next.name)
                snackString(next.labelRes(), requireActivity())
            }
            sortAnimeFranchises()
        }

        updatePill()
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

    private companion object {
        /** Shared-element name for the card banner handing off to the franchise screen. */
        const val FRANCHISE_TRANSITION = "franchiseCard"

        /**
         * Kitsu requests in flight at once while a page's seeds are resolved.
         *
         * Each seed costs about five sequential Kitsu calls, so a 50-seed page is roughly 250
         * round trips; serially that took minutes, which is what left the row spinning. Six at a
         * time collapses the same work into a fraction of the wall clock while staying well
         * inside what the Kitsu API will serve without rate-limiting.
         */
        const val KITSU_CONCURRENCY = 6

        /**
         * Titles turned into franchise cards per batch.
         *
         * Matches [ani.sanin.connections.trakt.Trakt.BATCH_CARDS] so both rows open on the same
         * number of cards. Sized against the AniList page rather than the Kitsu cost: these are
         * resolved six at a time now, so the limit is about how much the row should claim
         * before the user has scrolled, not about how long a batch takes.
         */
        const val SEED_BATCH = 29
    }

    val model: AnilistAnimeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // The next view inflates a fresh header, so the wiring flag has to be cleared or the new
        // pill and direction button would be left with no listeners at all.
        animeFranchiseSortWired = false
        animeFranchiseAdapter = null
        animeFranchiseSortGeneration++
        // The cursor tracks how far this row's adapter has been fed, and the adapter is going
        // away with the view. Kept, the fresh adapter would be told the first titles had already
        // been turned into cards and the row would come back empty after a rotation.
        animeFranchiseCursor = 0
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scope = viewLifecycleOwner.lifecycleScope

        var height = statusBarHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val displayCutout = activity?.window?.decorView?.rootWindowInsets?.displayCutout
            if (displayCutout != null) {
                if (displayCutout.boundingRects.size > 0) {
                    height = max(
                        statusBarHeight,
                        min(
                            displayCutout.boundingRects[0].width(),
                            displayCutout.boundingRects[0].height()
                        )
                    )
                }
            }
        }
        binding.animeRefresh.setSlingshotDistance(height + 128)
        binding.animeRefresh.setProgressViewEndTarget(false, height + 128)
        binding.animeRefresh.setOnRefreshListener {
            Refresh.activity[this.hashCode()]!!.postValue(true)
        }

        binding.animePageRecyclerView.updatePaddingRelative(bottom = navBarHeight + 160f.px)

        animePageAdapter = AnimePageAdapter()

        var loading = true
        if (model.notSet) {
            model.notSet = false
            model.aniMangaSearchResults = AniMangaSearchResults(
                "ANIME",
                isAdult = false,
                onList = false,
                results = mutableListOf(),
                hasNextPage = true,
                sort = Anilist.sortBy[1]
            )
        }
        // Franchise cards replace the Popular list here exactly as they do in movie mode. The
        // model still pages AniList below because its results back the shared search state
        // too; only what the page *shows* changed.
        val franchiseAdaptor = FranchiseAdaptor { card, view -> openFranchise(card, view) }
        animeFranchiseAdapter = franchiseAdaptor
        val progressAdaptor = ProgressAdapter(searched = model.searched)
        val adapter = ConcatAdapter(animePageAdapter, franchiseAdaptor, progressAdaptor)
        binding.animePageRecyclerView.adapter = adapter
        val layout = LinearLayoutManager(requireContext())
        binding.animePageRecyclerView.layoutManager = layout

        // Scrolling the last card into view claims the next batch of titles. The row is the
        // tail of this list, so this is the gesture that pages it; the cursor makes a repeat
        // call before the next page has landed a no-op rather than a double claim.
        binding.animePageRecyclerView.onNearEnd {
            publishFranchises(franchiseAdaptor, trigger = "scroll")
        }

        var visible = false
        fun animate() {
            val start = if (visible) 0f else 1f
            val end = if (!visible) 0f else 1f
            ObjectAnimator.ofFloat(binding.animePageScrollTop, "scaleX", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
            ObjectAnimator.ofFloat(binding.animePageScrollTop, "scaleY", start, end).apply {
                duration = 300
                interpolator = OvershootInterpolator(2f)
                start()
            }
        }

        binding.animePageScrollTop.setOnClickListener {
            binding.animePageRecyclerView.scrollToPosition(4)
            binding.animePageRecyclerView.smoothScrollToPosition(0)
        }

        model.getPopular().observe(viewLifecycleOwner) {
            if (it != null) {
                val page = model.aniMangaSearchResults.results.size
                model.aniMangaSearchResults.results.addAll(it.results)
                // A later page extends the shared result list, so the new seeds at [page]
                // only are turned into cards. Republishing everything each time would
                // rebuild every card already on screen for one new franchise.
                publishFranchises(franchiseAdaptor, trigger = "page $page")
                model.aniMangaSearchResults.onList = it.onList
                model.aniMangaSearchResults.hasNextPage = it.hasNextPage
                model.aniMangaSearchResults.page = it.page
                if (it.hasNextPage)
                    progressAdaptor.bar?.visibility = View.VISIBLE
                else {
                    if (!PrefManager.getVal<Boolean>(PrefName.RescueMode)) {
                        snackString(getString(R.string.jobless_message))
                    }
                    progressAdaptor.bar?.visibility = View.GONE
                }
                loading = false
            }
        }

        binding.animePageRecyclerView.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(v: RecyclerView, dx: Int, dy: Int) {
                if (!v.canScrollVertically(1)) {
                    if (model.aniMangaSearchResults.hasNextPage && model.aniMangaSearchResults.results.isNotEmpty() && !loading) {
                        loading = true
                        scope.launch(Dispatchers.IO) {
                            model.loadNextPage(model.aniMangaSearchResults)
                        }
                    }
                }
                if (layout.findFirstVisibleItemPosition() > 1 && !visible) {
                    binding.animePageScrollTop.visibility = View.VISIBLE
                    visible = true
                    animate()
                }

                if (!v.canScrollVertically(-1)) {
                    visible = false
                    animate()
                    scope.launch {
                        delay(300)
                        binding.animePageScrollTop.visibility = View.GONE
                    }
                }

                super.onScrolled(v, dx, dy)
            }
        })
        // Scroll to top when focus moves to the header area while page is scrolled down
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && _binding != null) {
                val rv = binding.animePageRecyclerView
                if (rv.canScrollVertically(-1)) {
                    var v: View? = newFocus
                    while (v != null && v != rv) {
                        if (v.id == R.id.trendingViewPager || v.id == R.id.trendingContainer || v.id == R.id.animeSeasons) {
                            rv.post { rv.smoothScrollToPosition(0) }
                            break
                        }
                        v = v.parent as? View
                    }
                }
            }
        }
        animePageAdapter.ready.observe(viewLifecycleOwner) { i ->
            if (i) {
                // Only now does AnimePageAdapter hold an inflated binding for the page item, so
                // this is the first point the header's controls exist to be wired.
                setupAnimeFranchiseSort()
                model.getUpdated().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateRecent(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getMovies().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateMovies(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getTopRated().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateTopRated(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                model.getMostFav().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateMostFav(MediaAdaptor(0, it, requireActivity()), it)
                    }
                }
                animePageAdapter.updateHeight()
                model.getTrending().observe(viewLifecycleOwner) {
                    if (it != null) {
                        animePageAdapter.updateTrending(it)
                    }
                }
                binding.animePageScrollTop.translationY = -(navBarHeight).toFloat()
            }
        }


        fun load() = scope.launch(Dispatchers.Main) {
        }

        animePageAdapter.onSeasonClick = { i ->
            scope.launch(Dispatchers.IO) {
                model.loadTrending(i)
            }
        }

        animePageAdapter.onSeasonLongClick = { i ->
            val (season, year) = Anilist.currentSeasons[i]
            ContextCompat.startActivity(
                requireContext(),
                Intent(requireContext(), SearchActivity::class.java)
                    .putExtra("type", "ANIME")
                    .putExtra("season", season)
                    .putExtra("seasonYear", year.toString())
                    .putExtra("search", true),
                null
            )
            true
        }

        var running = false
        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(false) }
        live.observe(viewLifecycleOwner) {
            if (it && !running) {
                running = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
                        if (rescueMode) {
                            withContext(Dispatchers.Main) { load() }
                        } else {
                            Anilist.userid =
                                PrefManager.getNullableVal<String>(PrefName.AnilistUserId, null)
                                    ?.toIntOrNull()
                            if (Anilist.userid == null) {
                                getUserId(requireContext()) {
                                    load()
                                }
                            } else {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getUserId(requireContext()) {
                                        load()
                                    }
                                }
                            }
                        }
                    }
                    val loadTrending = async(Dispatchers.IO) { model.loadTrending(1) }
                    val loadAll = async(Dispatchers.IO) { model.loadAll() }
                    val loadPopular = async(Dispatchers.IO) {
                        model.loadPopular(
                            "ANIME",
                            sort = Anilist.sortBy[1],
                            onList = PrefManager.getVal(PrefName.PopularAnimeList)
                        )
                    }
                    loadTrending.await()
                    loadAll.await()
                    loadPopular.await()
                    model.loaded = true
                    live.postValue(false)
                    _binding?.animeRefresh?.isRefreshing = false
                    running = false
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::animePageAdapter.isInitialized) {
            animePageAdapter.resizeBanner()
        }
    }

    override fun onResume() {
        if (!model.loaded) Refresh.activity[this.hashCode()]!!.postValue(true)
        if (this::animePageAdapter.isInitialized && _binding != null) {
            // The banner type is changed on the settings screen, so the change
            // is only visible when the user comes back here. Rebuilding just the
            // banner adapter is enough; it no-ops if the type is unchanged.
            animePageAdapter.refreshBannerTypeIfChanged()
            binding.root.requestApplyInsets()
            binding.root.requestLayout()
        }
        super.onResume()
    }
}