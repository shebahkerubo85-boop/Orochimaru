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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.bumptech.glide.Glide
import ani.sanin.R
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
import ani.sanin.youtube.YouTubeShortsPlayerActivity
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
    private val adapter = TmdbGridAdapter { openDetails(it) }
    private var genres: List<TmdbGenre> = emptyList()
    /** Shelf scroll position captured before a category opens, restored on the way back. */
    private var shelvesScrollY = 0
    private var selectedCategory: TmdbDiscoveryCategory? = null
    private val keywordCache = mutableMapOf<String, Int?>()
    private val artworkCache = mutableMapOf<String, String?>()
    private val artworkLoading = mutableSetOf<String>()
    private var artworkLimit = 12
    private var backCallback: OnBackPressedCallback? = null

    private var tmdbReelLoaded = false
    private var tmdbReelEntries: List<ReelFeed.ReelItem> = emptyList()
    private var tmdbReelCards: List<TmdbReelCard> = emptyList()

    private data class TmdbReelCard(val title: String, val key: String, val startIndex: Int)

    private val categorySemaphore = Semaphore(3)

    private companion object {
        /** How many titles the trailer rail seeds before the player takes over the feed. */
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
            if (selectedCategory == null) {
                // Load artwork just ahead of the viewport instead of waiting until the bottom
                // of the full 150-shelf list.
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

    /** Loads the trailer reels feed once, then renders the rail of entry-point cards. */
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
            // One card per title, in the order each first appears in the mixed feed, so a tap drops
            // the viewer at that title's first video without breaking the interleave.
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

    /** Opens the vertical reels player at [startIndex], continuing the TMDB trailer feed from there. */
    private fun openTmdbReelPlayer(startIndex: Int) {
        if (tmdbReelEntries.isEmpty() || !isAdded) return
        val intent = Intent(requireContext(), YouTubeShortsPlayerActivity::class.java).apply {
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_VIDEO_IDS,
                ArrayList(tmdbReelEntries.map { it.key })
            )
            // The header shows the specific video ("Official Trailer"); the pill shows the title.
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_TITLES,
                ArrayList(tmdbReelEntries.map { it.videoName })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_ANIME_TITLES,
                ArrayList(tmdbReelEntries.map { it.animeTitle })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_SYNOPSIS,
                ArrayList(tmdbReelEntries.map { it.synopsis ?: "" })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_GENRES,
                ArrayList(tmdbReelEntries.map { it.genres.joinToString("|") })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_COVERS,
                ArrayList(tmdbReelEntries.map { it.cover ?: "" })
            )
            // The title's TMDB id + media type, so the pill can open TmdbDetailsActivity.
            putIntegerArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_TMDB_IDS,
                ArrayList(tmdbReelEntries.map { it.tmdbId })
            )
            putStringArrayListExtra(
                YouTubeShortsPlayerActivity.EXTRA_TMDB_TYPES,
                ArrayList(tmdbReelEntries.map { it.mediaType ?: "movie" })
            )
            putExtra(YouTubeShortsPlayerActivity.EXTRA_START_INDEX, startIndex)
            putExtra(YouTubeShortsPlayerActivity.EXTRA_REEL_MODE, true)
            putExtra(YouTubeShortsPlayerActivity.EXTRA_REEL_SOURCE, YouTubeShortsPlayerActivity.SOURCE_TMDB)
        }
        startActivity(intent)
    }

    /**
     * Loads a card's 16:9 artwork with the same 404-safe fallback the anime rail uses, so a title
     * whose HD thumbnail is missing still shows its standard-quality one.
     */
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
                // Mirrored columns: posters face outward; copy faces the centre seam.
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
        val pending = categories.filter { !artworkCache.containsKey(it.id) && artworkLoading.add(it.id) }
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
        // Keep where the shelf list was so "All discovery shelves" can land back on it.
        shelvesScrollY = binding.tmdbDiscoveryScroll.scrollY
        binding.tmdbDiscoveryScroll.scrollTo(0, 0)
        binding.tmdbDiscoveryGrid.isVisible = true
        binding.tmdbDiscoveryProgress.isVisible = true
        binding.tmdbDiscoverySearchBar.contentDescription = "Search movies and TV"
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
        header.addView(TextView(requireContext()).apply {
            text = category.title
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(10), 0, dp(4))
        })
        header.addView(TextView(requireContext()).apply {
            text = category.description
            textSize = 13f
            setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        host.addView(header)
        if (isTvDevice(requireContext())) back.post { back.requestFocus() }
        viewLifecycleOwner.lifecycleScope.launch {
            val items = runCatching { fetchCategory(category, page = 1) }.getOrDefault(emptyList())
            if (_binding == null || selectedCategory?.id != category.id) return@launch
            adapter.submit(items)
            binding.tmdbDiscoveryProgress.isVisible = false
            binding.tmdbDiscoveryGrid.isVisible = items.isNotEmpty()
            if (items.isEmpty()) {
                host.addView(TextView(requireContext()).apply {
                    text = "No reliable matches found for this shelf yet. Try another category."
                    textSize = 13f
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(dp(12), dp(12), dp(12), dp(24))
                })
            }
        }
    }

    private fun showCategories() {
        selectedCategory = null
        backCallback?.isEnabled = false
        binding.tmdbDiscoveryProgress.isVisible = false
        binding.tmdbDiscoveryGrid.isVisible = false
        adapter.submit(emptyList())
        renderCategories()
        // Restore the shelf scroll position captured when the category was opened.
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

    private suspend fun fetchCategory(category: TmdbDiscoveryCategory, page: Int): List<TmdbMedia> {
        val keywordId = resolveKeyword(category)
        val movieGenres = category.genres
        val tvGenres = category.genres?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            ?.map { id ->
                when (id) {
                    28, 12 -> 10759
                    14, 878 -> 10765
                    else -> id
                }
            }?.distinct()?.joinToString(",")
        val extra = mutableListOf<Pair<String, String>>("include_adult" to "false")
        category.originalLanguage?.let { extra.add("with_original_language" to it) }
        if (category.id == "hidden-gems" || category.id == "underrated-masterpieces") {
            extra.add("vote_count.gte" to "250")
            extra.add("vote_average.gte" to "7.0")
            extra.add("release_date.lte" to todayIso())
        }
        val keyword = keywordId?.toString()
        val movie = Tmdb.discover(
            mediaType = "movie",
            genres = if (keyword == null) movieGenres else null,
            sort = if (category.id == "hidden-gems" || category.id == "underrated-masterpieces") "vote_average.desc" else "popularity.desc",
            keywords = keyword,
            page = page,
            extra = extra
        )
        val tvExtra = extra.filterNot { it.first == "release_date.lte" }.toMutableList()
        if (category.id == "hidden-gems" || category.id == "underrated-masterpieces") {
            tvExtra.add("first_air_date.lte" to todayIso())
        }
        val tv = Tmdb.discover(
            mediaType = "tv",
            genres = if (keyword == null) tvGenres else null,
            sort = if (category.id == "hidden-gems" || category.id == "underrated-masterpieces") "vote_average.desc" else "popularity.desc",
            keywords = keyword,
            page = page,
            extra = tvExtra
        )
        val merged = mutableListOf<TmdbMedia>()
        val max = maxOf(movie.size, tv.size)
        for (i in 0 until max) {
            movie.getOrNull(i)?.let { merged.add(it) }
            tv.getOrNull(i)?.let { merged.add(it) }
        }
        return merged.distinctBy { it.type to it.id }
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

    class TmdbGridAdapter(private val onOpen: (TmdbMedia) -> Unit) :
        RecyclerView.Adapter<TmdbGridAdapter.VH>() {
        private var items: List<TmdbMedia> = emptyList()
        fun submit(list: List<TmdbMedia>) {
            items = list
            notifyDataSetChanged()
        }
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
            holder.binding.tmdbCardPoster.setOnClickListener { onOpen(item) }
            holder.binding.root.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }
        override fun getItemCount(): Int = items.size
        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
