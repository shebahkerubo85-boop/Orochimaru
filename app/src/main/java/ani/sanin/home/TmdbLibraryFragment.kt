package ani.sanin.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.FragmentTmdbLibraryBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.sizeBannerCard
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbLibraryFragment : Fragment() {

    private var _binding: FragmentTmdbLibraryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadLibrary()
    }

    private fun loadLibrary() {
        if (Simkl.token == null) {
            showNotLoggedIn()
            return
        }
        binding.progressBar?.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val movies = withContext(Dispatchers.IO) { Simkl.getMovieLibrary() }
            val shows = withContext(Dispatchers.IO) { Simkl.getShowLibrary() }
            binding.progressBar?.isVisible = false
            if (movies.isEmpty() && shows.isEmpty()) {
                showEmpty()
                return@launch
            }
            showItems(movies, shows)
        }
    }

    private fun showNotLoggedIn() {
        binding.simklLibraryContainer?.removeAllViews()
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Log in to Simkl to see your library"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        binding.simklLibraryContainer?.addView(msg)
    }

    private fun showEmpty() {
        binding.simklLibraryContainer?.removeAllViews()
        val ctx = requireContext()
        val msg = TextView(ctx).apply {
            text = "Your Simkl library is empty"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 120, 48, 48)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOutline))
        }
        binding.simklLibraryContainer?.addView(msg)
    }

    private fun showItems(
        movies: List<Simkl.SimklWatchedItem>,
        shows: List<Simkl.SimklWatchedItem>
    ) {
        val ctx = requireContext()
        val container = binding.simklLibraryContainer ?: return
        container.removeAllViews()

        if (shows.isNotEmpty()) {
            val header = TextView(ctx).apply {
                text = "TV Shows (${shows.size})"
                setPadding(24, 20, 24, 8)
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
            container.addView(header)
            val grid = createGrid(shows) { item -> openItemDetails(item) }
            container.addView(grid)
        }

        if (movies.isNotEmpty()) {
            val header = TextView(ctx).apply {
                text = "Movies (${movies.size})"
                setPadding(24, 20, 24, 8)
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
            container.addView(header)
            val grid = createGrid(movies) { item -> openItemDetails(item) }
            container.addView(grid)
        }
    }

    private fun createGrid(
        items: List<Simkl.SimklWatchedItem>,
        onClick: (Simkl.SimklWatchedItem) -> Unit
    ): RecyclerView {
        val ctx = requireContext()
        return RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 3)
            adapter = LibraryAdapter(items, onClick)
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(16, 0, 16, 0)
            clipToPadding = false
        }
    }

    private fun openItemDetails(item: Simkl.SimklWatchedItem) {
        val tmdbId = item.ids?.tmdb ?: return
        val mediaType = if (item.type == "movie") "movie" else "tv"
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, tmdbId)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class LibraryAdapter(
        private val items: List<Simkl.SimklWatchedItem>,
        private val onClick: (Simkl.SimklWatchedItem) -> Unit
    ) : RecyclerView.Adapter<LibraryAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            val landscape = TmdbCards.isLandscapeOrientation()
            val size = TmdbCards.cardSize()
            val (w, h) = if (landscape) {
                (260f * size).toInt() to (148f * size).toInt()
            } else {
                (102f * size).toInt() to (154f * size).toInt()
            }
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> {
                width = w
                height = h
            }
            b.tmdbCard.radius = TmdbCards.roundness()
            b.tmdbCardPoster.loadImage(item.poster?.replace("original", "w500"), 300)
            b.tmdbCardTitle.text = item.title
            b.tmdbCardTitle.isVisible = true
            b.tmdbCardYear.text = item.year?.toString() ?: ""
            b.tmdbCardYear.isVisible = item.year != null
            b.tmdbCardGradient.isVisible = false
            b.tmdbCardLogo.isVisible = false
            b.tmdbCardOverlayTitle.isVisible = false
            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
