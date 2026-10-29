package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.ImageButton
import androidx.recyclerview.widget.LinearLayoutManager
import ani.sanin.ui.LensButtonBackground
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.bumptech.glide.Glide
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.cloudstream.TmdbSearchActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbGenre
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.FragmentTmdbDiscoveryBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.isTvDevice
import ani.sanin.loadImage
import ani.sanin.reels.ReelFeed
import ani.sanin.reels.TmdbReelFeed
import ani.sanin.util.FocusEffectUtil
import ani.sanin.youtube.ReelShortsPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TmdbDiscoveryFragment : Fragment() {

    private var _binding: FragmentTmdbDiscoveryBinding? = null
    private val binding get() = _binding!!
    private val tmdbCategoryItems = mutableListOf<TmdbMedia>()
    private val tmdbCategoryRowCards = mutableListOf<DiscoveryStandardCardAdapter.Card>()
    private val adapter = TmdbGridAdapter(tmdbCategoryItems) { openDetails(it) }
    private lateinit var tmdbListRowAdapter: DiscoveryListRowAdapter
    private var tmdbGridButton: ImageButton? = null
    private var tmdbListButton: ImageButton? = null
    private var genres: List<TmdbGenre> = emptyList()

    private var shelvesScrollY = 0
    private var selectedCategory: TmdbDiscoveryCategory? = null
    private val tmdbPool = mutableListOf<TmdbMedia>()
    private val tmdbSeen = HashSet<Pair<String, Int>>()
    private var tmdbMoviePage = 1
    private var tmdbTvPage = 1
    private var tmdbMoviePhase = 0
    private var tmdbTvPhase = 0
    private var tmdbMovieExhausted = false
    private var tmdbTvExhausted = false
    private var tmdbCategoryLoading = false
    private var tmdbReloadUsed = false
    private var tmdbCategoryEmptyView: TextView? = null
    private val keywordCache = java.util.concurrent.ConcurrentHashMap<String, Int?>()
    private val artworkCache = mutableMapOf<String, String?>()
    private val artworkLoading = mutableSetOf<String>()
    private val artworkFailedAt = mutableMapOf<String, Long>()
    private var artworkLimit = 12
    private var backCallback: OnBackPressedCallback? = null

    private var tmdbReelLoaded = false
    private var tmdbReelEntries: List<ReelFeed.ReelItem> = emptyList()
    private var tmdbReelCards: List<TmdbReelCard> = emptyList()

    private data class TmdbReelCard(val title: String, val key: String, val startIndex: Int)

    private val categorySemaphore = Semaphore(3)

    private companion object {

        const val RAIL_MEDIA_COUNT = 12
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTmdbDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        artworkCache.putAll(DiscoveryArtworkStore.tmdbPosters(requireContext()))
        val dm = resources.displayMetrics
        binding.tmdbDiscoveryGrid.layoutManager = GridLayoutManager(
            requireContext(), TmdbCards.gridSpan(dm.widthPixels / dm.density)
        )
        binding.tmdbDiscoveryGrid.isNestedScrollingEnabled = false
        tmdbListRowAdapter = DiscoveryListRowAdapter(tmdbCategoryRowCards) { card ->
            val item = tmdbCategoryItems.firstOrNull { it.id == -card.id }
            if (item != null) openDetails(item)
        }
        binding.tmdbDiscoveryGrid.adapter = adapter
        binding.tmdbDiscoveryProgress.isVisible = false
        binding.tmdbGenreChips.isVisible = false
        binding.tmdbCategoryChips.isVisible = false
        binding.tmdbDiscoveryGrid.isVisible = false

        val openSearch = { startActivity(Intent(requireContext(), TmdbSearchActivity::class.java)) }
        binding.tmdbDiscoverySearchText.setOnClickListener { openSearch() }
        binding.tmdbDiscoverySearchBar.isClickable = true
        binding.tmdbDiscoverySearchBar.isFocusableInTouchMode = true
        binding.tmdbDiscoverySearchBar.setOnClickListener { openSearch() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoverySearchBar)

        val avatarUrl = ani.sanin.connections.simkl.Simkl.avatar
            ?: ani.sanin.connections.anilist.Anilist.avatar
        if (!avatarUrl.isNullOrBlank()) binding.tmdbDiscoveryAvatar.loadImage(avatarUrl)
        binding.tmdbDiscoveryAvatar.setOnClickListener {
            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(Gravity.END)) {
                    act.findViewById<android.widget.FrameLayout>(
                        act.resources.getIdentifier("mainAvatarContainer", "id", act.packageName)
                    )?.let {
                        runCatching {
                            val method = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                            method.isAccessible = true
                            method.invoke(act)
                        }
                    }
                    drawer.openDrawer(Gravity.END)
                }
            }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoveryAvatar)

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                showCategories()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback!!)

        binding.tmdbDiscoveryScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (selectedCategory != null) {
                val content = binding.tmdbDiscoveryScroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
                if (scrollY + binding.tmdbDiscoveryScroll.height >= content - dp(600)) {
                    loadTmdbCategoryPage()
                }
                return@setOnScrollChangeListener
            }
            if (selectedCategory == null) {

                val rowsAhead = (scrollY + binding.tmdbDiscoveryScroll.height) / dp(118) + 2
                val needed = (rowsAhead * 2).coerceAtMost(TmdbDiscoveryCategories.all.size)
                if (needed > artworkLimit) {
                    artworkLimit = (artworkLimit + 12).coerceAtMost(needed)
                    loadCategoryArtwork()
                }
            }
        }

        renderCategories()
        loadCategoryArtwork()
        loadTmdbReelRail()
        viewLifecycleOwner.lifecycleScope.launch {
            genres = runCatching { Tmdb.genres() }.getOrDefault(emptyList())
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun openDetails(item: TmdbMedia) {
        startActivity(Intent(requireContext(), TmdbDetailsActivity::class.java)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, item.type)
            .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, item.id)
            .putExtra(TmdbDetailsActivity.ARG_OPEN_TAB, TmdbDetailsActivity.TAB_INFO))
    }

    private fun loadTmdbReelRail() {
        if (tmdbReelLoaded) {
            renderTmdbReelRail()
            return
        }
        tmdbReelLoaded = true
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbReelFeed.buildInterleaved(TmdbReelFeed.railPool(RAIL_MEDIA_COUNT))
                }.getOrNull()
            }
            tmdbReelEntries = entries ?: emptyList()

            val seen = HashSet<String>()
            tmdbReelCards = tmdbReelEntries.mapIndexedNotNull { index, item ->
                if (seen.add(item.animeTitle)) TmdbReelCard(item.animeTitle, item.key, index) else null
            }
            if (_binding != null) renderTmdbReelRail()
        }
    }

    private fun renderTmdbReelRail() {
        val host = _binding?.tmdbTrailerCarousel ?: return
        host.visibility = View.VISIBLE
        host.removeAllViews()
        val context = host.context
        host.addView(TextView(context).apply {
            text = "TRAILERS TO DROP INTO"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(12), dp(12), dp(8))
        })
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(8), dp(2), dp(8), dp(10))
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (tmdbReelCards.isEmpty()) {
            repeat(3) { index ->
                val placeholder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    layoutParams = LinearLayout.LayoutParams(dp(112), dp(178)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                placeholder.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
                    contentDescription = "Trailer artwork unavailable"
                })
                placeholder.addView(TextView(context).apply {
                    text = if (Tmdb.apiKey.isBlank())
                        "Add TMDB API key" else "Loading trailers ${index + 1}"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, dp(8), 0, 0)
                })
                row.addView(placeholder)
            }
        } else {
            tmdbReelCards.forEach { card ->
                val holder = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    isFocusable = true
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(dp(116), dp(190)).apply {
                        marginEnd = dp(10)
                    }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { openTmdbReelPlayer(card.startIndex) }
                }
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = card.title
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(145)
                    )
                }
                thumb.loadTmdbReelThumbnail(card.key)
                holder.addView(thumb)
                holder.addView(TextView(context).apply {
                    text = card.title
                    textSize = 11.5f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(dp(3), dp(5), dp(3), 0)
                })
                FocusEffectUtil.applyFocusListener(holder)
                row.addView(holder)
            }
        }
        scroll.addView(row)
        host.addView(scroll)
    }

    private fun openTmdbReelPlayer(startIndex: Int) {
        if (tmdbReelEntries.isEmpty() || !isAdded) return
        val intent = Intent(requireContext(), ReelShortsPlayerActivity::class.java).apply {
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_VIDEO_IDS,
                ArrayList(tmdbReelEntries.map { it.key })
            )

            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TITLES,
                ArrayList(tmdbReelEntries.map { it.videoName })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_ANIME_TITLES,
                ArrayList(tmdbReelEntries.map { it.animeTitle })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_SYNOPSIS,
                ArrayList(tmdbReelEntries.map { it.synopsis ?: "" })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_GENRES,
                ArrayList(tmdbReelEntries.map { it.genres.joinToString("|") })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_COVERS,
                ArrayList(tmdbReelEntries.map { it.cover ?: "" })
            )

            putIntegerArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TMDB_IDS,
                ArrayList(tmdbReelEntries.map { it.tmdbId })
            )
            putStringArrayListExtra(
                ReelShortsPlayerActivity.EXTRA_TMDB_TYPES,
                ArrayList(tmdbReelEntries.map { it.mediaType ?: "movie" })
            )
            putExtra(ReelShortsPlayerActivity.EXTRA_START_INDEX, startIndex)
            putExtra(ReelShortsPlayerActivity.EXTRA_REEL_MODE, true)
            putExtra(ReelShortsPlayerActivity.EXTRA_REEL_SOURCE, ReelShortsPlayerActivity.SOURCE_TMDB)
        }
        startActivity(intent)
    }

    private fun ImageView.loadTmdbReelThumbnail(key: String) {
        val view = this
        Glide.with(view)
            .load(ReelFeed.thumbnailUrl(key))
            .error(Glide.with(view).load(ReelFeed.thumbnailFallbackUrl(key)))
            .into(view)
    }

    private fun renderCategories() {
        val host = _binding?.tmdbEditorialShelves ?: return
        host.removeAllViews()
        host.orientation = LinearLayout.VERTICAL
        host.setPadding(dp(3), dp(4), dp(3), dp(18))
        host.addView(TextView(requireContext()).apply {
            text = "FIND YOUR NEXT OBSESSION"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(12), dp(16), dp(12), dp(12))
        })
        host.addView(TextView(requireContext()).apply {
            text = "150 moods, tropes, worlds, and rabbit holes. Pick a shelf and follow it wherever it goes."
            textSize = 12.5f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), 0, dp(12), dp(16))
        })

        val measuredWidth = host.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val availableWidthDp = (measuredWidth / resources.displayMetrics.density).toInt()
        val columnCount = ((availableWidthDp + 18) / (140 + 18)).coerceIn(1, 2)
        TmdbDiscoveryCategories.all.chunked(columnCount).forEachIndexed { rowIndex, rowCategories ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(18) }
            }
            rowCategories.forEachIndexed { columnIndex, category ->
                val card = LinearLayout(requireContext()).apply {
                    tag = category.id
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    clipChildren = false
                    background = context.getDrawable(R.drawable.bg_editorial_card_focus)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                        if (columnIndex < columnCount - 1) marginEnd = dp(18)
                    }
                    contentDescription = "${category.title}. ${category.description}"
                    setOnClickListener { openCategory(category) }
                }
                val artworkFrame = FrameLayout(requireContext()).apply {
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(82), dp(82))
                }
                val image = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                        }
                    }
                    setImageResource(R.drawable.ic_editorial_artwork_unavailable)
                    contentDescription = "${category.title} poster"
                    rotation = if (columnIndex % 2 == 0) -8f else 8f
                    layoutParams = FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
                    tag = category.id
                    artworkCache[category.id]?.let { loadImage(it, dp(82)) }
                }
                artworkFrame.addView(image)
                val surface = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
                fun addFade(orientation: GradientDrawable.Orientation, colors: IntArray, params: FrameLayout.LayoutParams) {
                    artworkFrame.addView(View(requireContext()).apply {
                        background = GradientDrawable(orientation, colors)
                        isClickable = false
                        isFocusable = false
                    }, params)
                }
                val fadeSize = dp(30)
                addFade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
                addFade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(fadeSize, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
                addFade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.TOP))
                addFade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fadeSize, Gravity.BOTTOM))

                val textColumn = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                }
                textColumn.addView(TextView(requireContext()).apply {
                    text = category.title
                    textSize = 13.5f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                })
                textColumn.addView(TextView(requireContext()).apply {
                    text = category.description
                    textSize = 10.5f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    alpha = 0.9f
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, dp(4), 0, 0)
                })

                if (columnIndex == 0) {
                    card.addView(artworkFrame)
                    card.addView(textColumn)
                } else {
                    card.addView(textColumn)
                    card.addView(artworkFrame)
                }
                FocusEffectUtil.applyFocusListener(card)
                row.addView(card)
            }
            if (rowCategories.size == 1) {
                row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            }
            host.addView(row)
        }
    }

    private fun loadCategoryArtwork() {
        val categories = TmdbDiscoveryCategories.all.take(artworkLimit)
        val now = System.currentTimeMillis()
        val pending = categories.filter {
            val failedRecently = artworkFailedAt.containsKey(it.id) && now - artworkFailedAt[it.id]!! < 60000
            val stale = !artworkCache.containsKey(it.id) || artworkCache[it.id] == null
            (!failedRecently && stale) && artworkLoading.add(it.id)
        }
        if (pending.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val results = coroutineScope {
                pending.map { category ->
                    async(Dispatchers.IO) {
                        categorySemaphore.withPermit {
                            category.id to runCatching {
                                fetchArtworkPoster(category)
                            }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
            if (_binding == null) return@launch
            results.forEach { (id, url) ->
                artworkLoading.remove(id)
                artworkCache[id] = url
                if (url.isNullOrBlank()) artworkFailedAt[id] = System.currentTimeMillis()
                if (!url.isNullOrBlank()) {
                    DiscoveryArtworkStore.putTmdb(requireContext(), id, url)
                }
            }
            updateArtworkViews()
        }
    }

    private fun updateArtworkViews() {
        val host = _binding?.tmdbEditorialShelves ?: return
        for (i in 0 until host.childCount) {
            val row = host.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val card = row.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until card.childCount) {
                    val frame = card.getChildAt(k) as? FrameLayout ?: continue
                    val image = frame.getChildAt(0) as? ImageView ?: continue
                    val id = image.tag as? String ?: continue
                    val url = artworkCache[id]
                    if (!url.isNullOrBlank() && image.tag != "loaded:$id") {
                        image.loadImage(url, dp(82))
                        image.tag = "loaded:$id"
                    }
                }
            }
        }
    }

    private fun openCategory(category: TmdbDiscoveryCategory) {
        selectedCategory = category
        backCallback?.isEnabled = true

        shelvesScrollY = binding.tmdbDiscoveryScroll.scrollY
        binding.tmdbDiscoveryScroll.scrollTo(0, 0)
        binding.tmdbDiscoveryGrid.isVisible = true
        binding.tmdbDiscoveryProgress.isVisible = true
        binding.tmdbDiscoverySearchBar.contentDescription = "Search movies and TV"
        resetTmdbCategoryPaging()
        val host = binding.tmdbEditorialShelves
        host.removeAllViews()
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        val back = Chip(requireContext()).apply {
            text = "‹  All discovery shelves"
            isFocusable = true
            setOnClickListener { showCategories() }
            FocusEffectUtil.applyFocusListener(this)
        }
        header.addView(back)
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = category.title
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(10), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val gridButton = ImageButton(requireContext()).apply {
            setImageResource(ani.sanin.R.drawable.ic_round_grid_view_24)
            setColorFilter(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            LensButtonBackground.apply(this)
            contentDescription = context.getString(ani.sanin.R.string.grid)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        val listButton = ImageButton(requireContext()).apply {
            setImageResource(ani.sanin.R.drawable.ic_round_view_list_24)
            setColorFilter(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setPadding(dp(11), dp(11), dp(11), dp(11))
            LensButtonBackground.apply(this)
            contentDescription = context.getString(ani.sanin.R.string.list)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        titleRow.addView(gridButton)
        titleRow.addView(listButton)
        FocusEffectUtil.applyFocusListener(gridButton)
        FocusEffectUtil.applyFocusListener(listButton)
        tmdbGridButton = gridButton
        tmdbListButton = listButton
        gridButton.setOnClickListener { applyTmdbCategoryLayout(0) }
        listButton.setOnClickListener { applyTmdbCategoryLayout(1) }
        header.addView(titleRow)
        header.addView(TextView(requireContext()).apply {
            text = category.description
            textSize = 13f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        host.addView(header)
        if (isTvDevice(requireContext())) back.post { back.requestFocus() }
        applyTmdbCategoryLayout(PrefManager.getCustomVal("discover_category_view", 0))
        loadTmdbCategoryPage()
    }

    private fun resetTmdbCategoryPaging() {
        tmdbCategoryItems.clear()
        tmdbPool.clear()
        tmdbSeen.clear()
        tmdbMoviePage = 1
        tmdbTvPage = 1
        tmdbMoviePhase = 0
        tmdbTvPhase = 0
        tmdbMovieExhausted = false
        tmdbTvExhausted = false
        tmdbCategoryLoading = false
        tmdbCategoryEmptyView = null
        tmdbReloadUsed = false
    }

    private fun tmdbAllExhausted(): Boolean =
        tmdbMovieExhausted && tmdbTvExhausted && tmdbPool.isEmpty()

    private fun applyTmdbCategoryLayout(mode: Int) {
        val b = _binding ?: return
        PrefManager.setCustomVal("discover_category_view", mode)
        tmdbGridButton?.alpha = if (mode == 0) 1f else 0.33f
        tmdbListButton?.alpha = if (mode == 1) 1f else 0.33f
        b.tmdbDiscoveryGrid.adapter = if (mode == 1) tmdbListRowAdapter else adapter
        b.tmdbDiscoveryGrid.layoutManager =
            if (mode == 1) {
                LinearLayoutManager(requireContext())
            } else {
                GridLayoutManager(
                    requireContext(),
                    TmdbCards.gridSpan(resources.displayMetrics.widthPixels / resources.displayMetrics.density)
                )
            }
    }

    private fun loadTmdbCategoryPage() {
        val category = selectedCategory ?: return
        val b = _binding ?: return
        if (tmdbCategoryLoading || tmdbAllExhausted()) return
        tmdbCategoryLoading = true
        val firstLoad = tmdbCategoryItems.isEmpty()
        val want = if (firstLoad) 20 else 30
        b.tmdbDiscoveryProgress.isVisible = firstLoad
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                runLoadTmdbCategoryPage(category, want, firstLoad)
            } finally {
                tmdbCategoryLoading = false
                if (_binding != null && selectedCategory?.id == category.id) {
                    _binding?.tmdbDiscoveryProgress?.isVisible = false
                }
            }
        }
    }

    private suspend fun runLoadTmdbCategoryPage(category: TmdbDiscoveryCategory, want: Int, firstLoad: Boolean) {
        val b = _binding ?: return
        run {
            var emitted = 0
            var failures = 0
            while (emitted < want && !tmdbAllExhausted() && failures < 3 &&
                _binding != null && selectedCategory?.id == category.id
            ) {
                if (tmdbPool.isEmpty()) {
                    val outcome = runCatching { refillTmdbPool(category) }
                    if (outcome.isSuccess) failures = 0 else failures++
                }
                if (tmdbPool.isEmpty()) {
                    if (tmdbAllExhausted() || failures >= 3) break
                    continue
                }
                val take = minOf(tmdbPool.size, want - emitted)
                val chunk = tmdbPool.take(take)
                repeat(take) { tmdbPool.removeAt(0) }
                if (chunk.isEmpty()) break
                val fresh = chunk.filter { tmdbSeen.add(it.type to it.id) }
                if (fresh.isEmpty()) continue
                emitted += fresh.size
                tmdbCategoryItems.addAll(fresh)
                val rowStart = tmdbCategoryRowCards.size
                tmdbCategoryRowCards.addAll(fresh.map { item ->
                    DiscoveryStandardCardAdapter.Card(
                        id = -item.id,
                        title = item.displayTitle,
                        posterUrl = item.posterPath?.let { Tmdb.imageUrl(it, 300) },
                        year = item.year.takeIf { y -> y.isNotBlank() },
                    )
                })
                val start = tmdbCategoryItems.size - fresh.size
                adapter.notifyItemRangeInserted(start, fresh.size)
                tmdbListRowAdapter.notifyItemRangeInserted(rowStart, fresh.size)
            }
            if (_binding == null || selectedCategory?.id != category.id) return
            b.tmdbDiscoveryProgress.isVisible = false
            b.tmdbDiscoveryGrid.isVisible = tmdbCategoryItems.isNotEmpty()
            if (tmdbCategoryItems.isNotEmpty()) {
                tmdbCategoryEmptyView?.let { if (it.parent != null) (it.parent as? android.view.ViewGroup)?.removeView(it) }
                tmdbCategoryEmptyView = null
            }
            if (tmdbCategoryItems.isEmpty() && tmdbAllExhausted()) {
                val reason = Tmdb.lastError
                val message: String = if (reason != null) "TMDB can\'t be reached from your network right now (${Tmdb.lastError}). Anime shelves still work - try Wi-Fi or another network, then go back and retry."
                else "No reliable matches found for this shelf yet. Try another category."
                showTmdbCategoryEmpty(message)
                if (firstLoad && !tmdbReloadUsed) {
                    tmdbReloadUsed = true
                    b.tmdbDiscoveryScroll.postDelayed({
                        if (_binding != null && selectedCategory?.id == category.id) {
                            resetTmdbCategoryPaging()
                            loadTmdbCategoryPage()
                        }
                    }, 6000)
                }
            }
            tmdbCategoryLoading = false
            if (emitted > 0 && !tmdbAllExhausted()) {
                val scroll = b.tmdbDiscoveryScroll
                val content = scroll.getChildAt(0)?.height
                if (content != null && scroll.scrollY + scroll.height >= content - dp(600)) {
                    scroll.post { if (_binding != null) loadTmdbCategoryPage() }
                }
            }
        }
    }

    private suspend fun refillTmdbPool(category: TmdbDiscoveryCategory) {
        if (tmdbMoviePhase == 0 && tmdbMoviePage == 1 && resolveKeyword(category) == null) tmdbMoviePhase = 1
        if (tmdbTvPhase == 0 && tmdbTvPage == 1 && resolveKeyword(category) == null) tmdbTvPhase = 1
        if (!tmdbMovieExhausted) {
            val movie = fetchTmdbType("movie", category, tmdbMoviePhase, tmdbMoviePage)
            if (Tmdb.lastError != null) throw java.io.IOException(Tmdb.lastError ?: "connection failed")
            if (movie.isEmpty()) advanceTmdbPhase(movie = true) else {
                addToTmdbPool(movie)
                tmdbMoviePage++
            }
        }
        if (!tmdbTvExhausted) {
            val tv = fetchTmdbType("tv", category, tmdbTvPhase, tmdbTvPage)
            if (Tmdb.lastError != null) throw java.io.IOException(Tmdb.lastError ?: "connection failed")
            if (tv.isEmpty()) advanceTmdbPhase(movie = false) else {
                addToTmdbPool(tv)
                tmdbTvPage++
            }
        }
    }

    private fun advanceTmdbPhase(movie: Boolean) {
        if (movie) {
            if (tmdbMoviePhase < 2) {
                tmdbMoviePhase++
                tmdbMoviePage = 1
            } else {
                tmdbMovieExhausted = true
            }
        } else {
            if (tmdbTvPhase < 2) {
                tmdbTvPhase++
                tmdbTvPage = 1
            } else {
                tmdbTvExhausted = true
            }
        }
    }

    private fun addToTmdbPool(list: List<TmdbMedia>) {
        list.forEach { if (tmdbSeen.add(it.type to it.id)) tmdbPool.add(it) }
    }

    private suspend fun fetchTmdbType(
        mediaType: String,
        category: TmdbDiscoveryCategory,
        phase: Int,
        page: Int,
    ): List<TmdbMedia> {
        val keyword = if (phase == 0) resolveKeyword(category)?.toString() else null
        val useGenres = phase <= 1
        val sort = if (phase == 0 && (category.id == "hidden-gems" || category.id == "underrated-masterpieces")) {
            "vote_average.desc"
        } else {
            "popularity.desc"
        }
        val extra = mutableListOf<Pair<String, String>>("include_adult" to "false")
        category.originalLanguage?.let { extra.add("with_original_language" to it) }
        if (phase == 0 && (category.id == "hidden-gems" || category.id == "underrated-masterpieces")) {
            extra.add("vote_count.gte" to "250")
            extra.add("vote_average.gte" to "7.0")
            if (mediaType == "movie") extra.add("release_date.lte" to todayIso())
            else extra.add("first_air_date.lte" to todayIso())
        }
        val genres: String? = if (useGenres && category.genres != null) {
            if (mediaType == "movie") {
                category.genres
            } else {
                category.genres?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                    ?.map { id ->
                        when (id) {
                            28, 12 -> 10759
                            14, 878 -> 10765
                            else -> id
                        }
                    }?.distinct()?.joinToString(",")
            }
        } else {
            null
        }
        return Tmdb.discover(
            mediaType = mediaType,
            genres = genres,
            sort = sort,
            keywords = keyword,
            page = page,
            extra = extra
        )
    }

    private fun showTmdbCategoryEmpty(message: String) {
        val host = _binding?.tmdbEditorialShelves ?: return
        if (tmdbCategoryEmptyView?.parent == null) tmdbCategoryEmptyView = TextView(requireContext()).apply {
            textSize = 13f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        tmdbCategoryEmptyView?.let { view ->
            view.text = message
            if (view.parent == null) host.addView(view)
        }
    }

    private fun showCategories() {
        selectedCategory = null
        resetTmdbCategoryPaging()
        backCallback?.isEnabled = false
        binding.tmdbDiscoveryProgress.isVisible = false
        binding.tmdbDiscoveryGrid.isVisible = false
        tmdbCategoryItems.clear()
        tmdbCategoryRowCards.clear()
        adapter.notifyDataSetChanged()
        tmdbListRowAdapter.notifyDataSetChanged()
        renderCategories()

        val target = shelvesScrollY
        binding.tmdbDiscoveryScroll.post { if (_binding != null) binding.tmdbDiscoveryScroll.scrollTo(0, target) }
        loadCategoryArtwork()
    }

    private suspend fun fetchArtworkPoster(category: TmdbDiscoveryCategory): String? {
        val keyword = resolveKeyword(category)?.toString()
        val extra = mutableListOf<Pair<String, String>>("include_adult" to "false")
        category.originalLanguage?.let { extra.add("with_original_language" to it) }
        suspend fun poster(type: String): String? = Tmdb.discover(
            mediaType = type,
            genres = if (keyword == null) category.genres else null,
            sort = "popularity.desc",
            keywords = keyword,
            page = 1,
            extra = extra
        ).firstOrNull { !it.posterPath.isNullOrBlank() }
            ?.posterPath
            ?.let { Tmdb.imageUrl(it, 342) }
        return poster("movie") ?: poster("tv")
    }

    private suspend fun resolveKeyword(category: TmdbDiscoveryCategory): Int? {
        val query = category.keyword?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = query.lowercase(Locale.ROOT)
        if (keywordCache.containsKey(key)) return keywordCache[key]
        val resolved = runCatching {
            val matches = Tmdb.searchKeywords(query)
            matches.firstOrNull { normalize(it.name) == normalize(query) }?.id
                ?: matches.firstOrNull {
                    normalize(it.name).contains(normalize(query)) ||
                        normalize(query).contains(normalize(it.name))
                }?.id
        }.getOrNull()
        keywordCache[key] = resolved
        return resolved
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.tmdbDiscoveryGrid?.let { TmdbCards.applyGridSpan(it) }
        if (_binding != null && selectedCategory == null) renderCategories()
    }

    override fun onDestroyView() {
        backCallback?.isEnabled = false
        backCallback = null
        _binding = null
        super.onDestroyView()
    }

    class TmdbGridAdapter(
        private val items: MutableList<TmdbMedia>,
        private val onOpen: (TmdbMedia) -> Unit,
    ) : RecyclerView.Adapter<TmdbGridAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val rv = holder.itemView.parent as? RecyclerView
            val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
            TmdbCards.applyCardStyle(holder.binding, item, cell)
            if (rv != null) holder.binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = TmdbCards.gridRowGapPx(rv)
            }
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.root.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.itemView, holder.binding.tmdbCard)
        }
        override fun getItemCount(): Int = items.size
        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.root.isFocusable = true
                binding.root.isFocusableInTouchMode = false
            }
        }
    }
}
