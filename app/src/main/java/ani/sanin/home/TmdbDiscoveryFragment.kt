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
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
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
    private var selectedCategory: TmdbDiscoveryCategory? = null
    private val keywordCache = mutableMapOf<String, Int?>()
    private val artworkCache = mutableMapOf<String, String?>()
    private val artworkLoading = mutableSetOf<String>()
    private var artworkLimit = 12
    private var backCallback: OnBackPressedCallback? = null

    private val categorySemaphore = Semaphore(3)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTmdbDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
            val child = binding.tmdbDiscoveryScroll.getChildAt(0) ?: return@setOnScrollChangeListener
            if (selectedCategory == null &&
                scrollY + binding.tmdbDiscoveryScroll.height >= child.height - dp(420)
            ) {
                val next = (artworkLimit + 12).coerceAtMost(TmdbDiscoveryCategories.all.size)
                if (next > artworkLimit) {
                    artworkLimit = next
                    loadCategoryArtwork()
                }
            }
        }

        renderCategories()
        loadCategoryArtwork()
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

        val columnCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 2 else 2
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
                    artworkCache[category.id]?.let { loadImage(it) }
                }
                artworkFrame.addView(image)
                val surface = requireContext().getThemeColor(com.google.android.material.R.attr.colorSurface)
                fun fade(orientation: GradientDrawable.Orientation, colors: IntArray, params: FrameLayout.LayoutParams) {
                    artworkFrame.addView(View(requireContext()).apply {
                        background = GradientDrawable(orientation, colors)
                        isClickable = false
                        isFocusable = false
                    }, params)
                }
                val fade = dp(30)
                fade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(fade, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
                fade(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(fade, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
                fade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(surface, Color.TRANSPARENT),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fade, Gravity.TOP))
                fade(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, surface),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, fade, Gravity.BOTTOM))

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
                                val items = fetchCategory(category, page = 1)
                                items.firstOrNull { !it.posterPath.isNullOrBlank() }?.posterPath
                                    ?.let { Tmdb.imageUrl(it, 342) }
                            }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
            if (_binding == null) return@launch
            results.forEach { (id, url) ->
                artworkLoading.remove(id)
                artworkCache[id] = url
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
                        image.loadImage(url)
                        image.tag = "loaded:$id"
                    }
                }
            }
        }
    }

    private fun openCategory(category: TmdbDiscoveryCategory) {
        selectedCategory = category
        backCallback?.isEnabled = true
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
        if (category.id == "content-warnings-ratings") {
            binding.tmdbDiscoveryProgress.isVisible = false
            binding.tmdbDiscoveryGrid.isVisible = false
            host.addView(TextView(requireContext()).apply {
                text = "This shelf is guidance, not a list of mature titles. Check the regional age certification and details on a title's Info tab, and skip anything you do not want to watch. TMDB does not provide a universal, reliable nudity flag."
                textSize = 14f
                setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(12), dp(12), dp(12), dp(24))
            })
            return
        }
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
        binding.tmdbDiscoveryScroll.scrollTo(0, 0)
        loadCategoryArtwork()
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
            tvExtra.add("first_air_date.lte" to LocalDate.now().toString())
        }
        val tv = Tmdb.discover(
            mediaType = "tv",
            genres = if (keyword == null) tvGenres else null,
            sort = if (category.id == "hidden-gems" || category.id == "underrated-masterpieces") "vote_average.desc" else "popularity.desc",
            keywords = keyword,
            page = page,
            extra = tvExtra
        )
        return buildList {
            val max = maxOf(movie.size, tv.size)
            for (i in 0 until max) {
                movie.getOrNull(i)?.let(::add)
                tv.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.type to it.id }
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
