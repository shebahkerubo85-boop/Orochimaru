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
import ani.sanin.media.FranchiseActivity
import ani.sanin.media.FranchiseAdaptor
import ani.sanin.media.FranchiseListPrefs
import ani.sanin.media.FranchiseSort
import ani.sanin.media.showFranchiseSortDialog
import ani.sanin.media.read
import ani.sanin.media.FranchiseSorter
import ani.sanin.media.FranchiseStub
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * Turns the loaded AniList titles into Franchise cards and hands them to the adapter.
     *
     * Each loaded title is a seed. Kitsu resolves the seed into its real franchise, and
     * AniList relations are only the fallback for when Kitsu is unreachable or has no
     * mapping, so the row still fills.
     *
     * @param from index of the first seed to consider, so a later AniList page only adds its
     *   own cards instead of rebuilding every card already on screen.
     */
    private fun publishFranchises(adaptor: FranchiseAdaptor, from: Int = 0) {
        // Snapshot on the main thread: `results` keeps growing as later pages land, and
        // reading it from a background thread would race that mutation.
        val snapshot = model.aniMangaSearchResults.results.toList()
        if (snapshot.isEmpty()) {
            adaptor.submit(emptyList())
            return
        }
        val range = from.coerceAtLeast(0) until snapshot.size
        if (range.isEmpty()) return

        // A new page re-reports seeds that are already in flight or already on screen.
        // Re-walking Kitsu for those is the request cost this change exists to avoid.
        val seeds = range.mapNotNull { snapshot.getOrNull(it) }
            .filterNot { adaptor.hasCardFor(it.id) || adaptor.isPending(it.id) }
        if (seeds.isEmpty()) return

        // markPending claims the seeds, so a concurrent page overlap cannot race in on the
        // same ones between the filter above and this call.
        val claimed = adaptor.markPending(seeds.map { it.id })
            .mapNotNull { id -> seeds.firstOrNull { it.id == id } }
        if (claimed.isEmpty()) return

        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                claimed.mapNotNull { seed ->
                    Kitsu.franchiseCard(seed.id)
                        ?: FranchiseStub.fromMedia(seed)
                }
                    // Several seeds can resolve to the same franchise; one card each.
                    .distinctBy { it.name.lowercase() }
            }
            adaptor.markDone(claimed.map { it.id })
            // notifyItemRangeInserted must run on the main thread, which lifecycleScope's
            // default dispatcher already is.
            // Appended only while the row is unsorted: a sorted row cannot just have a card
            // pushed onto the end, so it is re-submitted in full once the new card exists.
            if (animeFranchisePrefs().sort == FranchiseSort.RANDOM) {
                adaptor.append(cards)
            } else {
                // A sorted row cannot have a new card pushed onto the end; it has to be
                // re-submitted in its order, with the new card in it.
                sortAnimeFranchises()
            }
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
        adaptor.submit(FranchiseSorter.apply(ranked, prefs, shuffled))
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
                publishFranchises(franchiseAdaptor, from = page)
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