package ani.sanin.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
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
        val screenWidthPx = dm.widthPixels
        val density = dm.density
        val landscape = TmdbCards.isLandscapeOrientation()
        val size = TmdbCards.cardSize()
        val cardWidthPx = ((if (landscape) 260f else 102f) * size * density).toInt()
        val marginEndPx = (12 * density).toInt()
        val paddingPx = (32 * density).toInt()
        val cols = ((screenWidthPx - paddingPx) / (cardWidthPx + marginEndPx)).toInt().coerceAtLeast(2)
        binding.tmdbDiscoveryGrid.layoutManager = GridLayoutManager(requireContext(), cols)
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

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.tmdbDiscoveryProgress.isVisible = true
            // A selected genre overrides the category: category endpoints have
            // no genre filter, so route through discover (movie + TV) instead.
            val items = if (selectedGenre != null) {
                val genreId = selectedGenre!!.id.toString()
                Tmdb.discover(mediaType = "movie", genres = genreId, sort = "popularity.desc") +
                    Tmdb.discover(mediaType = "tv", genres = genreId, sort = "popularity.desc")
            } else {
                when (selectedCategory) {
                    "Trending" -> Tmdb.trending("all", "week")
                    "Latest Releases" -> Tmdb.latestMovies() + Tmdb.latestSeries()
                    "Top Rated" -> Tmdb.topRated()
                    "Popular" -> Tmdb.popular()
                    else -> emptyList()
                }
            }
            binding.tmdbDiscoveryProgress.isVisible = false
            adapter.submit(items)
        }
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
            TmdbCards.applyCardStyle(holder.binding, item)
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onOpen(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
