package ani.sanin.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.FragmentTmdbHomeBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.launch

class TmdbHomeFragment : Fragment() {

    private var _binding: FragmentTmdbHomeBinding? = null
    private val binding get() = _binding!!
    private val bannerItems = mutableListOf<TmdbMedia>()
    private var bannerIndex = 0
    private val bannerHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTmdbHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tmdbBannerFrame.setOnClickListener {
            bannerItems.getOrNull(bannerIndex)?.let { openDetails(it.type, it.id) }
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbBannerFrame)
        load()
    }

    override fun onResume() {
        super.onResume()
        startAutoAdvance()
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bannerHandler.removeCallbacksAndMessages(null)
        _binding = null
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val trendingSeries = Tmdb.trending("tv", "week")
            val trendingMovies = Tmdb.trending("movie", "week")
            val latestSeries = Tmdb.latestSeries()
            val latestMovies = Tmdb.latestMovies()
            val popular = Tmdb.popular()
            val topRated = Tmdb.topRated()
            val trending = trendingSeries + trendingMovies
            bannerItems.clear()
            bannerItems.addAll(trending)
            if (trending.isNotEmpty()) showBanner(0)
            addSection("Trending Series", trendingSeries)
            addSection("Trending Movies", trendingMovies)
            addSection("Latest Series", latestSeries)
            addSection("Latest Movies", latestMovies)
            addSection("Popular", popular)
            addSection("Top Rated", topRated)
            startAutoAdvance()
        }
    }

    private fun addSection(title: String, items: List<TmdbMedia>) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val header = TextView(ctx).apply {
            text = title
            setPadding(24, 20, 24, 8)
            textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = TmdbRowAdapter(items) { media -> openDetails(media.type, media.id) }
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(24, 0, 24, 0)
        }
        binding.tmdbHomeSections.addView(header)
        binding.tmdbHomeSections.addView(list)
    }

    private fun showBanner(index: Int) {
        val item = bannerItems.getOrNull(index) ?: return
        bannerIndex = index
        binding.tmdbBannerImage.loadImage(Tmdb.imageUrl(item.backdropPath, 780))
        binding.tmdbBannerTitle.text = item.displayTitle
        binding.tmdbBannerMeta.text = buildString {
            if (item.voteAverage > 0) append("★ ").append(String.format("%.1f", item.voteAverage)).append("  •  ")
            if (item.year.isNotBlank()) append(item.year).append("  •  ")
            append(item.type.replaceFirstChar { it.uppercase() })
        }
        binding.tmdbBannerSynopsis.text = item.overview?.takeIf { it.isNotBlank() } ?: ""
    }

    private fun startAutoAdvance() {
        bannerHandler.removeCallbacksAndMessages(null)
        val mode = PrefManager.getVal(PrefName.HomeBannerMode)
        if (mode != 0 || bannerItems.size < 2) return
        bannerHandler.postDelayed(object : Runnable {
            override fun run() {
                showBanner((bannerIndex + 1) % bannerItems.size)
                bannerHandler.postDelayed(this, 6000)
            }
        }, 6000)
    }

    private fun openDetails(mediaType: String, id: Int) {
        startActivity(
            Intent(requireContext(), TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, id)
        )
    }

    class TmdbRowAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbRowAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbCardPoster.loadImage(Tmdb.imageUrl(item.posterPath, 300))
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
