package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbCast
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ActivityTmdbDetailsBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbCastBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.launch

class TmdbDetailsActivity : AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
    }

    private lateinit var binding: ActivityTmdbDetailsBinding
    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var detail: TmdbDetail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply the user's actual theme (OLED black, accent colors, ...) instead of the
        // manifest default (Material3 baseline purple in night mode).
        ThemeManager(this).applyTheme()
        // Plugins use CommonActivity.getActivity() for toasts/browser launches.
        CommonActivity.setActivityInstance(this)
        binding = ActivityTmdbDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1)
        Logger.log(
            "TMDB_DETAILS: opened mediaType=$mediaType mediaId=$mediaId"
        )

        binding.tmdbDetailBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailBack)
        binding.tmdbDetailPlayCard.setOnClickListener { onPlayClick() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailPlayCard)

        load()
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }

    private fun load() {
        lifecycleScope.launch {
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details")
                return@launch
            }
            detail = d
            binding.tmdbDetailBackdrop.loadImage(Tmdb.imageUrl(d.backdropPath ?: d.posterPath, 780))
            val logo = Tmdb.logoUrl(d)
            if (logo != null) {
                binding.tmdbDetailLogo.loadImage(logo)
            } else {
                binding.tmdbDetailLogo.visibility = View.GONE
            }
            binding.tmdbDetailRating.text = buildString {
                if (d.voteAverage > 0) append("★ ").append(String.format("%.1f", d.voteAverage)).append("  •  ")
                if (d.year.isNotBlank()) append(d.year)
            }
            binding.tmdbDetailStatus.text = statusLabel(d.status)
            binding.tmdbDetailSynopsis.text = d.overview?.takeIf { it.isNotBlank() } ?: "No synopsis available."
            d.genres.take(5).forEach { genre ->
                val chip = TextView(this@TmdbDetailsActivity).apply {
                    text = genre.name
                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    setBackgroundResource(R.drawable.tmdb_chip_bg)
                    textSize = 12f
                    setPadding(36, 12, 36, 12)
                    isFocusable = true
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 20 }
                binding.tmdbDetailGenreChips.addView(chip, lp)
                FocusEffectUtil.applyFocusListener(chip)
            }
            binding.tmdbDetailPlayText.text = getString(if (mediaType == "tv") R.string.watch else R.string.play)
            buildCastSection(d)
            buildMoreLikeSection(d)
        }
    }

    private fun statusLabel(status: String?): String = when (status?.lowercase()) {
        "returning series", "returning" -> "Ongoing"
        "released" -> "Released"
        "planned" -> "Upcoming"
        "in production" -> "In Production"
        "ended", "canceled", "cancelled" -> "Completed"
        else -> status ?: ""
    }

    // ── playback flow ───────────────────────────────────────────────────────

    /** The play button is the gate to the movie/tv watch tab. */
    private fun onPlayClick() {
        startActivity(
            Intent(this, TmdbWatchActivity::class.java)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_TYPE, mediaType)
                .putExtra(TmdbWatchActivity.ARG_MEDIA_ID, mediaId)
        )
    }

    // ── cast / more like this ───────────────────────────────────────────────

    private fun buildCastSection(d: TmdbDetail) {
        val cast = d.credits?.cast.orEmpty().take(20)
        if (cast.isEmpty()) return
        val ctx = this
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        section.addView(sectionHeader("Cast"))
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = CastAdapter(cast)
            isNestedScrollingEnabled = false
            setPadding(24, 8, 24, 8)
        }
        section.addView(list)
        binding.tmdbDetailSections.addView(section)
    }

    private fun buildMoreLikeSection(d: TmdbDetail) {
        val recs = d.recommendations?.results.orEmpty().take(20)
        if (recs.isEmpty()) return
        val ctx = this
        val section = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        section.addView(sectionHeader("More Like This"))
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = MoreLikeAdapter(recs) { media ->
                startActivity(
                    Intent(this@TmdbDetailsActivity, TmdbDetailsActivity::class.java)
                        .putExtra(ARG_MEDIA_TYPE, media.type)
                        .putExtra(ARG_MEDIA_ID, media.id)
                )
            }
            isNestedScrollingEnabled = false
            setPadding(24, 8, 24, 8)
        }
        section.addView(list)
        binding.tmdbDetailSections.addView(section)
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setPadding(4, 24, 4, 4)
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
    }

    class CastAdapter(
        private val items: List<TmdbCast>
    ) : RecyclerView.Adapter<CastAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbCastImage.loadImage(Tmdb.imageUrl(item.profilePath, 200))
            holder.binding.tmdbCastName.text = item.name
            holder.binding.tmdbCastRole.text = item.character ?: ""
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCastImage)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCastBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class MoreLikeAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<MoreLikeAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            TmdbCards.applyCardStyle(holder.binding, item)
            holder.binding.tmdbCardTitle.text = item.displayTitle
            holder.binding.tmdbCardYear.text = item.year
            holder.binding.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbCardPoster)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
