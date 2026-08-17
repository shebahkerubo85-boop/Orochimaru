package ani.sanin.home

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.cloudstream.TmdbCards
import ani.sanin.cloudstream.TmdbDetailsActivity
import ani.sanin.connections.simkl.Simkl
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimklSectionFragment : Fragment() {

    private var items: List<Simkl.SimklWatchedItem> = emptyList()
    private var originalItems: List<Simkl.SimklWatchedItem> = emptyList()
    private var recyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            @Suppress("UNCHECKED_CAST")
            items = (it.getSerializable(ARG_ITEMS_LIST) as? ArrayList<Simkl.SimklWatchedItem>) ?: emptyList()
        }
        originalItems = items
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return RecyclerView(requireContext()).apply {
            val screenWidth = resources.displayMetrics.run { widthPixels / density }
            layoutManager = GridLayoutManager(requireContext(), (screenWidth / 120f).toInt().coerceAtLeast(2))
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(16, 8, 16, 16)
            recyclerView = this
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateAdapter(items)
    }

    fun filter(query: String) {
        if (query.isBlank()) {
            items = originalItems
        } else {
            items = originalItems.filter { item ->
                item.title?.contains(query, ignoreCase = true) == true ||
                item.year?.toString()?.contains(query) == true
            }
        }
        updateAdapter(items)
    }

    fun sort(by: String) {
        items = when (by) {
            "title" -> items.sortedBy { it.title?.lowercase() }
            "year" -> items.sortedByDescending { it.year ?: 0 }
            "updated" -> items.sortedByDescending { it.lastWatchedAt ?: "" }
            else -> items
        }
        updateAdapter(items)
    }

    private fun updateAdapter(list: List<Simkl.SimklWatchedItem>) {
        if (!isAdded) return
        recyclerView?.adapter = if (list.isEmpty()) null else SimklGridAdapter(list) { item ->
            val tmdbId = item.ids?.tmdb ?: return@SimklGridAdapter
            val mediaType = item.mediaType ?: "tv"
            startActivity(
                Intent(requireContext(), TmdbDetailsActivity::class.java)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, mediaType)
                    .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, tmdbId)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
    }

    class SimklGridAdapter(
        private val items: List<Simkl.SimklWatchedItem>,
        private val onClick: (Simkl.SimklWatchedItem) -> Unit
    ) : RecyclerView.Adapter<SimklGridAdapter.VH>() {

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

            // Reset before async loads
            b.tmdbCardTitle.isVisible = true
            b.tmdbCardTitle.text = item.title
            b.tmdbCardYear.text = item.year?.toString() ?: ""
            b.tmdbCardYear.isVisible = item.year != null

            if (landscape) {
                b.tmdbCardGradient.isVisible = true
                setGradient(b.tmdbCardGradient)
                b.tmdbCardOverlayTitle.isVisible = true
                b.tmdbCardOverlayTitle.text = item.title
                b.tmdbCardLogo.isVisible = false
            } else {
                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
            }

            // Load image + logo async (same pattern as anime library)
            val tmdbId = item.ids?.tmdb
            val mediaType = item.mediaType ?: "tv"
            if (tmdbId != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val detail = Tmdb.detail(mediaType, tmdbId)
                    val backdropUrl = detail?.backdropPath?.let { Tmdb.imageUrl(it, 780) }
                    val logoUrl = Tmdb.logoUrl(mediaType, tmdbId)

                    withContext(Dispatchers.Main) {
                        if (!holder.bindingEquals(item)) return@withContext

                        // Image: landscape → backdrop, portrait → poster
                        val imageUrl = if (landscape) {
                            backdropUrl ?: Simkl.imageUrl(item.poster, "w")
                        } else {
                            Simkl.imageUrl(item.poster, "m")
                        }
                        b.tmdbCardPoster.loadImage(imageUrl)

                        // Logo overlay in landscape mode
                        if (landscape && !logoUrl.isNullOrBlank()) {
                            b.tmdbCardLogo.isVisible = true
                            b.tmdbCardLogo.loadImage(logoUrl)
                            b.tmdbCardOverlayTitle.isVisible = false
                            b.tmdbCardTitle.isVisible = false
                        }
                    }
                }
            } else {
                // No TMDB ID — use Simkl poster
                val imageUrl = if (landscape) {
                    Simkl.imageUrl(item.poster, "w")
                } else {
                    Simkl.imageUrl(item.poster, "m")
                }
                b.tmdbCardPoster.loadImage(imageUrl)
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }

        override fun getItemCount() = items.size

        private fun setGradient(view: View) {
            val intensity = PrefManager.getVal<Float>(PrefName.CardGradientIntensity)
            if (intensity <= 0f) {
                view.background = null
                return
            }
            val endAlpha = 255
            val startColor = Color.argb(0, 0, 0, 0)
            val endColor = Color.argb(
                (endAlpha * intensity).toInt().coerceIn(0, 255),
                0, 0, 0
            )
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(endColor, startColor)
            )
            view.background = gradient
        }

        private fun VH.bindingEquals(item: Simkl.SimklWatchedItem): Boolean {
            return binding.tmdbCardTitle.text == item.title
        }

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val ARG_ITEMS_LIST = "items_list"

        fun newInstance(items: List<Simkl.SimklWatchedItem>): SimklSectionFragment {
            return SimklSectionFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ITEMS_LIST, ArrayList(items))
                }
            }
        }
    }
}
