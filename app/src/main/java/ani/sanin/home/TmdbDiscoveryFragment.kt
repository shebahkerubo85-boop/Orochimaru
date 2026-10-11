package ani.sanin.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
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
import kotlinx.coroutines.launch

class TmdbDiscoveryFragment : Fragment() {

    private var _binding: FragmentTmdbDiscoveryBinding? = null
    private val binding get() = _binding!!

    private val categories = listOf("Trending", "Latest Releases", "Top Rated", "Popular")
    private val adapter = TmdbGridAdapter { item -> openDetails(item) }
    private var selectedGenre: TmdbGenre? = null
    private var selectedCategory = "Trending"
    private var genres: List<TmdbGenre> = emptyList()
    private var traktEditorialCards: List<ani.sanin.media.Franchise> = emptyList()
    private var traktLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbDiscoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dm = resources.displayMetrics
        // Same density cut as every other grid, so this screen matches anime discovery
        // instead of running two columns behind it on the same phone.
        val cols = TmdbCards.gridSpan(dm.widthPixels / dm.density)
        binding.tmdbDiscoveryGrid.layoutManager = GridLayoutManager(requireContext(), cols)
        binding.tmdbDiscoveryGrid.isNestedScrollingEnabled = false
        binding.tmdbDiscoveryGrid.adapter = adapter
        val openSearch = { startActivity(Intent(requireContext(), TmdbSearchActivity::class.java)) }
        binding.tmdbDiscoverySearchText.setOnClickListener { openSearch() }
        // D-pad focus lands on the pill (TextInputLayout) — make the whole
        // pill behave like one button so OK/Enter opens search, not just touch.
        binding.tmdbDiscoverySearchBar.isClickable = true
        binding.tmdbDiscoverySearchBar.isFocusableInTouchMode = true
        binding.tmdbDiscoverySearchBar.setOnClickListener { openSearch() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoverySearchBar)
        // Load real avatar image
        val avatarUrl = ani.sanin.connections.simkl.Simkl.avatar
            ?: ani.sanin.connections.anilist.Anilist.avatar
        if (!avatarUrl.isNullOrBlank()) {
            binding.tmdbDiscoveryAvatar.loadImage(avatarUrl)
        }
        // Avatar: open right rail drawer
        binding.tmdbDiscoveryAvatar.setOnClickListener {
            val act = requireActivity()
            if (act is ani.sanin.MainActivity) {
                val drawer = act.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    act.resources.getIdentifier("mainDrawer", "id", act.packageName))
                if (drawer != null && !drawer.isDrawerOpen(android.view.Gravity.END)) {
                    act.findViewById<android.widget.FrameLayout>(
                        act.resources.getIdentifier("mainAvatarContainer", "id", act.packageName)
                    )?.let {
                        // Reuse the MainActivity's drawer population
                        val popMethod = ani.sanin.MainActivity::class.java.getDeclaredMethod("populateRightRail")
                        popMethod.isAccessible = true
                        popMethod.invoke(act)
                    }
                    drawer.openDrawer(android.view.Gravity.END)
                }
            }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbDiscoveryAvatar)
        setupCategoryChips()
        viewLifecycleOwner.lifecycleScope.launch {
            genres = Tmdb.genres()
            setupGenreChips()
            load()
        }
    }

    private fun chip(text: String, checked: Boolean = false, onClick: () -> Unit): Chip {
        return Chip(requireContext()).apply {
            this.text = text
            isCheckable = true
            isChecked = checked
            isFocusable = true
            setTextColor(requireContext().getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            setOnClickListener { onClick() }
            FocusEffectUtil.applyFocusListener(this)
        }
    }

    private fun setupGenreChips() {
        val group = binding.tmdbGenreChips
        group.removeAllViews()
        group.addView(chip("All", selectedGenre == null) {
            selectedGenre = null
            load()
        })
        genres.forEach { genre ->
            group.addView(chip(genre.name, selectedGenre?.id == genre.id) {
                selectedGenre = genre
                load()
            })
        }
    }

    private fun setupCategoryChips() {
        val group = binding.tmdbCategoryChips
        group.removeAllViews()
        categories.forEach { cat ->
            group.addView(chip(cat, selectedCategory == cat) {
                selectedCategory = cat
                load()
            })
        }
    }

    private fun openDetails(item: TmdbMedia) {
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, item.type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, item.id)
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges, so this fragment's view survives the
        // rotation and the span built in onViewCreated is still the portrait one.
        _binding?.tmdbDiscoveryGrid?.let { TmdbCards.applyGridSpan(it) }
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.tmdbDiscoveryProgress.isVisible = true
            // Category fetches the pool; a selected genre further filters it
            // locally via genre_ids so chips combine (e.g. Action + Top Rated).
            val pool = when (selectedCategory) {
                "Trending" -> Tmdb.trending("all", "week")
                "Latest Releases" -> Tmdb.latestMovies() + Tmdb.latestSeries()
                "Top Rated" -> Tmdb.topRated()
                "Popular" -> Tmdb.popular()
                else -> emptyList()
            }
            val items = selectedGenre?.let { genre -> pool.filter { it.genreIds.contains(genre.id) } } ?: pool
            binding.tmdbDiscoveryProgress.isVisible = false
            adapter.submit(items)
            renderEditorialShelves(items)
            if (!traktLoaded) {
                traktLoaded = true
                viewLifecycleOwner.lifecycleScope.launch {
                    traktEditorialCards = runCatching {
                        ani.sanin.connections.trakt.Trakt.franchiseCards(skip = 0, take = 6).cards
                    }.getOrDefault(emptyList())
                    if (_binding != null) renderEditorialShelves(items)
                }
            }
        }
    }

    private fun renderEditorialShelves(items: List<TmdbMedia>) {
        val genreNames = genres.associate { it.id to it.name.lowercase() }
        fun genreText(item: TmdbMedia): String =
            item.genreIds.mapNotNull { genreNames[it] }.joinToString(" ")
        fun searchable(item: TmdbMedia): String =
            "${item.displayTitle} ${item.overview.orEmpty()} ${genreText(item)}".lowercase()

        fun shelf(
            title: String,
            note: String,
            predicate: (TmdbMedia, String) -> Boolean
        ): EditorialShelfData {
            val cards = items.asSequence()
                .map { it to searchable(it) }
                .filter { (item, text) -> predicate(item, text) }
                .distinctBy { it.id to it.type }
                .take(10)
                .map { (item, _) ->
                    EditorialCardData(
                        title = item.displayTitle,
                        meta = listOfNotNull(item.year.takeIf { it.isNotBlank() }, item.type.uppercase())
                            .joinToString(" · "),
                        imageUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
                    )
                }.toList()
            return EditorialShelfData(title, note, cards, placeholderCount = 3)
        }

        val shelves = listOf(
            shelf("THE SLOW BURN CLUB", "Chemistry, longing and relationships that take their time.") { item, text ->
                ("romance" in text || "drama" in text) &&
                    listOf("slow burn", "gradually", "slowly", "longing", "relationship develops",
                        "will they", "unspoken feelings", "years later", "reunite").any(text::contains)
            },
            shelf("FIVE STEPS AHEAD", "Schemes, investigations and one move ahead of everyone.") { item, text ->
                listOf("mastermind", "strateg", "conspiracy", "investigation", "detective", "scheme",
                    "manipulat", "mind game", "outsmart", "heist").any(text::contains)
            },
            shelf("THE VILLAIN HAS A POINT", "Antiheroes, revenge and motives that complicate the story.") { item, text ->
                listOf("antihero", "anti-hero", "villain", "revenge", "vengeance", "morally",
                    "justice", "corrupt", "revenge").any(text::contains)
            },
            shelf("BEAUTIFULLY UNWELL", "Psychological spirals and characters barely holding it together.") { item, text ->
                ("drama" in text || "thriller" in text || "psychological" in text || "horror" in text) &&
                    listOf("obsession", "trauma", "paranoia", "unravel", "grief", "loneliness",
                        "identity", "descent", "unstable", "haunted").any(text::contains)
            },
            shelf("WORLDS TO GET LOST IN", "Big worlds, strange rules and stories with room to wander.") { item, text ->
                listOf("fantasy", "science fiction", "adventure", "space", "kingdom", "world",
                    "magic", "universe", "journey").any(text::contains)
            },
            EditorialShelfData(
                "THE COMMUNITY'S RABBIT HOLES",
                "Curated movie and TV lists from Trakt, not another popularity chart.",
                traktEditorialCards.map { list ->
                    EditorialCardData(
                        title = list.name,
                        meta = list.trendingRank?.let { "Trakt trending #$it" } ?: "Trakt community list",
                        imageUrl = list.entries.firstOrNull()?.posterUrl ?: list.bannerUrl
                    )
                },
                placeholderCount = 3
            ),
            EditorialShelfData(
                "TRAILER PARK", "Trailer cards are reserved here for a later pass.",
                emptyList(), placeholderCount = 3
            )
        )
        EditorialShelfRenderer.render(requireContext(), binding.tmdbEditorialShelves, shelves)
    }

    class TmdbGridAdapter(
        private val onOpen: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbGridAdapter.VH>() {

        private var items: List<TmdbMedia> = emptyList()

        fun submit(list: List<TmdbMedia>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            // The card takes the width of its column so a row of them fills the screen
            // exactly; the preference that sizes rail cards would overflow a grid cell.
            val rv = holder.itemView.parent as? RecyclerView
            val span = (rv?.layoutManager as? GridLayoutManager)?.spanCount ?: 0
            val cell = if (rv != null && span > 0) TmdbCards.cellWidthPx(rv, span) else null
            TmdbCards.applyCardStyle(holder.binding, item, cell)
            if (rv != null) {
                holder.binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = TmdbCards.gridRowGapPx(rv)
                }
            }
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
