package ani.sanin.cloudstream

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbCast
import ani.sanin.connections.tmdb.TmdbDetail
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.simkl.Simkl
import ani.sanin.databinding.ActivityTmdbDetailsBinding
import ani.sanin.databinding.FragmentTmdbInfoBinding
import ani.sanin.databinding.ActivityGenreBinding
import ani.sanin.databinding.ItemTitleTrailerBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbCastBinding
import ani.sanin.databinding.ItemGenreBinding
import ani.sanin.databinding.ItemChipBinding
import ani.sanin.databinding.ItemTitleChipgroupBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.snackString
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.google.android.material.card.MaterialCardView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TmdbDetailsActivity : AppCompatActivity() {

    companion object {
        const val ARG_MEDIA_TYPE = "mediaType"
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_PLUGIN_SOURCE = "pluginSource"
        const val ARG_PLUGIN_URL = "pluginUrl"
    }

    private lateinit var shell: ActivityTmdbDetailsBinding
    private lateinit var binding: FragmentTmdbInfoBinding
    private var mediaType: String = "movie"
    private var mediaId: Int = -1
    private var pluginSourceId: String? = null
    private var pluginUrl: String? = null
    private val pluginMode get() = pluginUrl != null
    private var detail: TmdbDetail? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        CommonActivity.setActivityInstance(this)

        shell = ActivityTmdbDetailsBinding.inflate(layoutInflater)
        binding = FragmentTmdbInfoBinding.inflate(layoutInflater)
        shell.tmdbDetailFragmentContainer.addView(binding.root)
        setContentView(shell.root)

        mediaType = intent.getStringExtra(ARG_MEDIA_TYPE) ?: "movie"
        mediaId = intent.getIntExtra(ARG_MEDIA_ID, -1)
        pluginSourceId = intent.getStringExtra(ARG_PLUGIN_SOURCE)
        pluginUrl = intent.getStringExtra(ARG_PLUGIN_URL)
        Logger.log("TMDB_DETAILS: opened mediaType=$mediaType mediaId=$mediaId")

        shell.tmdbDetailBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(shell.tmdbDetailBack)
        binding.tmdbDetailPlayCard.setOnClickListener { onPlayClick() }
        FocusEffectUtil.applyFocusListener(binding.tmdbDetailPlayCard)
        binding.mediaInfoAddToList.setOnClickListener { detail?.let { openListEditor(it) } }
        FocusEffectUtil.applyFocusListener(binding.mediaInfoAddToList)

        load()
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        if (pluginMode) {
            loadPlugin()
            return
        }
        lifecycleScope.launch {
            val d = Tmdb.detail(mediaType, mediaId) ?: run {
                snackString("Could not load details"); return@launch
            }
            detail = d
            binding.mediaInfoProgressBar.visibility = View.GONE
            binding.mediaInfoContainer.visibility = View.VISIBLE

            val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val bg = if (isPortrait)
                Tmdb.imageUrl(d.posterPath, 780) ?: Tmdb.imageUrl(d.backdropPath, 780)
            else
                Tmdb.imageUrl(d.backdropPath, 1280) ?: Tmdb.imageUrl(d.posterPath, 780)
            bg?.let { shell.tmdbDetailBackdrop.loadImage(it) }

            val logo = Tmdb.logoUrl(d)
            if (logo != null) {
                binding.mediaInfoLogo.loadImage(logo)
                binding.mediaInfoLogo.visibility = View.VISIBLE
                binding.mediaInfoTitle.visibility = View.GONE
            } else {
                binding.mediaInfoTitle.text = d.displayTitle
                binding.mediaInfoTitle.visibility = View.VISIBLE
                binding.mediaInfoLogo.visibility = View.GONE
            }

            val scoreTxt = if (d.voteAverage > 0) "★ " + String.format("%.1f", d.voteAverage) else "—"
            val scoreSpan = SpannableString(scoreTxt)
            if (d.voteAverage > 0) {
                scoreSpan.setSpan(
                    ForegroundColorSpan(Color.parseColor("#FFD700")),
                    0, 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            binding.mediaInfoMeanScore.text = scoreSpan
            binding.mediaInfoStatus.text = statusLabel(d.status)
            binding.mediaInfoStatus.setTextColor(tmdbStatusColor(d.status))

            val airedStr = buildString {
                val startRaw = if (mediaType == "movie") d.releaseDate else d.firstAirDate
                val startFmt = startRaw?.let { formatAiredDate(it) }
                if (startFmt != null) append("Aired: ").append(startFmt)
                if (mediaType == "tv") {
                    val endFmt = d.lastEpisodeToAir?.airDate?.let { formatAiredDate(it) }
                    if (startFmt != null) append("  •  ")
                    append("To: ").append(endFmt ?: "???")
                }
            }
            if (airedStr.isNotBlank()) {
                binding.mediaInfoAired.text = airedStr
                binding.mediaInfoAired.visibility = View.VISIBLE
            } else {
                binding.mediaInfoAired.visibility = View.GONE
            }

            if (mediaType == "tv" && d.numberOfEpisodes > 0) {
                val total = d.numberOfEpisodes
                binding.mediaInfoReleased.text = total.toString() + " of " + total.toString()
                lifecycleScope.launch {
                    val prog = simklProgress(mediaType, d.id, d.externalIds?.imdbId)
                    val w = prog?.first ?: 0
                    val totalEps = (prog?.second ?: total).coerceAtLeast(total)
                    val span = SpannableString(w.toString() + " of " + totalEps.toString())
                    val len = w.toString().length
                    if (len > 0) span.setSpan(
                        ForegroundColorSpan(getThemeColor(com.google.android.material.R.attr.colorPrimary)),
                        0, len, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    binding.mediaInfoWatchProgress.text = span
                    val next = if (w < totalEps) w + 1 else 0
                    binding.mediaInfoNextEpisode.text = if (next > 0) "Ep " + next else "—"
                }
            } else {
                binding.mediaInfoReleasedRow.visibility = View.GONE
                binding.mediaInfoProgressRow.visibility = View.GONE
                binding.mediaInfoNextRow.visibility = View.GONE
            }

            lifecycleScope.launch { refreshListLabel(d) }

            loadGenres(d)
            binding.mediaInfoDescription.text =
                d.overview?.takeIf { it.isNotBlank() } ?: getString(R.string.no_description_available)
            setupShowMore()
            loadExternalLinks(d)
            setupMetaRow(d)
            loadTrailer(d)
            loadTags(d)
            loadRelations(d)
            loadPrequelSequel(d)
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

    private fun tmdbStatusColor(status: String?): Int {
        val s = status?.lowercase() ?: ""
        return when {
            s.contains("returning") -> Color.parseColor("#76FF03")
            s.contains("planned") || s.contains("in production") || s.contains("upcoming") ->
                Color.parseColor("#00E5FF")
            s.contains("released") || s.contains("ended") || s.contains("cancel") ->
                Color.parseColor("#F44336")
            else -> Color.WHITE
        }
    }

    private fun formatAiredDate(raw: String): String? {
        val parts = raw.split("-")
        if (parts.size < 3) return raw
        val year = parts[0].toIntOrNull() ?: return raw
        val month = parts[1].toIntOrNull() ?: return raw
        val day = parts[2].toIntOrNull() ?: return raw
        if (year == 0) return null
        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        val monthName = if (month in 1..12) months[month - 1] else return raw
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return "${day}${suffix} $monthName $year"
    }

// __PART2__

    private fun loadPlugin() {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE
        binding.mediaInfoTitle.text = intent.getStringExtra("title") ?: "Play"
        binding.mediaInfoTitle.visibility = View.VISIBLE
        binding.mediaInfoLogo.visibility = View.GONE
        binding.mediaInfoMeanScore.text = "—"
        binding.mediaInfoStatus.text = "—"
        listOf(
            binding.mediaInfoGenreContainer,
            binding.mediaInfoExternalLinksContainer,
            binding.mediaInfoTagsContainer,
            binding.mediaInfoCastTitle,
            binding.mediaInfoCastRecycler,
            binding.mediaInfoRecommendTitle,
            binding.mediaInfoRecommendRecycler,
            binding.tmdbDetailRelationChips
        ).forEach { it.visibility = View.GONE }
        binding.mediaInfoReleasedRow.visibility = View.GONE
        binding.mediaInfoProgressRow.visibility = View.GONE
        binding.mediaInfoNextRow.visibility = View.GONE
    }

    private fun loadGenres(d: TmdbDetail) {
        val genres = d.genres
        if (genres.isNullOrEmpty()) {
            binding.mediaInfoGenreContainer.visibility = View.GONE
            return
        }
        binding.mediaInfoGenreContainer.visibility = View.VISIBLE
        val host = ActivityGenreBinding.inflate(layoutInflater)
        host.mediaInfoGenresProgressBar.visibility = View.GONE
        host.mediaInfoGenresRecyclerView.layoutManager =
            GridLayoutManager(this, (resources.displayMetrics.widthPixels / 95.dpToPx()).coerceAtLeast(3))
        val items = genres.map { GenreBanner(it.id, it.name, null) }
        val adapter = GenreBannerAdapter(items)
        host.mediaInfoGenresRecyclerView.adapter = adapter
        binding.mediaInfoGenreContainer.addView(host.root)
        lifecycleScope.launch(Dispatchers.IO) {
            val banners = items.map { Tmdb.genreBannerUrl(it.id) }
            val updated = items.mapIndexed { i, g -> g.copy(bannerUrl = banners[i]) }
            withContext(Dispatchers.Main) { adapter.submitList(updated) }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    data class GenreBanner(val id: Int, val name: String, val bannerUrl: String?)

    class GenreBannerAdapter(private var items: List<GenreBanner>) :
        RecyclerView.Adapter<GenreBannerAdapter.VH>() {
        class VH(val b: ItemGenreBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
            VH(ItemGenreBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = items.size
        fun submitList(list: List<GenreBanner>) {
            items = list
            notifyDataSetChanged()
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val g = items[i]
            h.b.genreTitle.text = g.name
            h.b.genreImage.loadImage(g.bannerUrl)
        }
    }

    private fun setupShowMore() {
        binding.mediaInfoShowMore.visibility = View.GONE
        binding.mediaInfoDescription.post {
            val lineHeight = binding.mediaInfoDescription.lineHeight
            val visibleMax = (dp(210) / lineHeight).coerceAtLeast(3)
            if (binding.mediaInfoDescription.lineCount > visibleMax) {
                binding.mediaInfoDescription.maxLines = visibleMax
                binding.mediaInfoShowMore.visibility = View.VISIBLE
                binding.mediaInfoShowMore.setOnClickListener {
                    val expanded = binding.mediaInfoDescription.maxLines == Int.MAX_VALUE
                    if (expanded) {
                        binding.mediaInfoDescription.maxLines = visibleMax
                        binding.mediaInfoShowMore.text = getString(R.string.show_more)
                    } else {
                        binding.mediaInfoDescription.maxLines = Int.MAX_VALUE
                        binding.mediaInfoShowMore.text = getString(R.string.show_less)
                    }
                }
            }
        }
    }

    private fun loadExternalLinks(d: TmdbDetail) {
        val links = mutableListOf<Pair<String, String>>()
        d.externalIds?.imdbId?.let { links.add("IMDb" to "https://www.imdb.com/title/$it/") }
        d.externalIds?.facebookId?.let { links.add("Facebook" to "https://www.facebook.com/$it") }
        d.externalIds?.instagramId?.let { links.add("Instagram" to "https://www.instagram.com/$it") }
        d.externalIds?.twitterId?.let { links.add("Twitter" to "https://twitter.com/$it") }
        if (links.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(layoutInflater)
        bind.itemTitle.setText(R.string.external_links)
        links.forEach { (site, url) ->
            val chip = ItemChipBinding.inflate(layoutInflater, bind.itemChipGroup, false).root
            chip.text = site
            chip.setOnClickListener { openUrl(url) }
            FocusEffectUtil.applyFocusListener(chip)
            bind.itemChipGroup.addView(chip)
        }
        binding.mediaInfoExternalLinksContainer.addView(bind.root)
    }

    private fun setupMetaRow(d: TmdbDetail) {
        binding.mediaInfoSource.text = "TMDB"
        binding.mediaInfoFormat.text = if (mediaType == "tv") "TV" else "Movie"
        val studio = d.productionCompanies.firstOrNull()?.name
        binding.mediaInfoStudio.text = studio ?: "—"
        if (studio != null) binding.mediaInfoStudio.setOnClickListener {
            openUrl("https://www.themoviedb.org/$mediaType/${d.id}")
        }
        val creator = d.createdBy.firstOrNull()?.name
        val director = d.credits?.crew?.firstOrNull { it.job.equals("Director", true) }?.name
        val (authorLabel, authorName) = when {
            creator != null -> "AUTHOR" to creator
            director != null -> "DIRECTOR" to director
            else -> "AUTHOR" to "—"
        }
        binding.mediaInfoAuthorLabel.text = authorLabel
        binding.mediaInfoAuthor.text = authorName
        binding.mediaInfoShare.setOnClickListener { shareMovie() }
        binding.mediaInfoFav.setOnClickListener { snackString("Favorite not available") }
        binding.mediaInfoComment.setOnClickListener { snackString("Comments not available") }
        FocusEffectUtil.applyFocusListener(
            binding.mediaInfoAddToList, binding.mediaInfoFav,
            binding.mediaInfoShare, binding.mediaInfoComment
        )
    }

// __PART3__

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun shareMovie() {
        val d = detail ?: return
        val url = "https://www.themoviedb.org/$mediaType/${d.id}"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun loadTrailer(d: TmdbDetail) {
        val key = d.videos?.results?.firstOrNull { it.site.equals("YouTube", true) }?.key
        if (key == null) return
        val bind = ItemTitleTrailerBinding.inflate(layoutInflater)
        val wv = bind.mediaInfoTrailer
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.userAgentString = null
        wv.webChromeClient = MyChrome()
        wv.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun loadVideo() {
                val trailerHtml = """
                    <!DOCTYPE html><html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
                    html,body{width:100%;height:100%;background:#000;overflow:hidden;}
                    iframe{width:100%;height:100%;border:none;display:block;}</style>
                    </head><body>
                    <iframe src="https://www.youtube-nocookie.com/embed/$key?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0"
                    allow="accelerometer;autoplay;clipboard-write;encrypted-media;gyroscope;picture-in-picture" frameborder="0"></iframe>
                    </body></html>
                """.trimIndent()
                runOnUiThread {
                    wv.loadDataWithBaseURL(
                        "https://www.youtube-nocookie.com",
                        trailerHtml, "text/html", "utf-8", null
                    )
                }
            }
        }, "Android")
        wv.loadDataWithBaseURL(
            "https://www.youtube-nocookie.com",
            placeholderHtml(key), "text/html", "utf-8", null
        )
        binding.mediaInfoTrailerHost.addView(bind.root)
    }

    private fun placeholderHtml(trailerId: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none;}
        body,html{width:100%;height:100%;background:#000;overflow:hidden;}
        .thumbnail-container{position:relative;width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#000;}
        .thumbnail{width:100%;height:100%;object-fit:contain;}
        .play-button{position:absolute;width:68px;height:48px;background:rgba(255,0,0,0.8);border-radius:12px;display:flex;align-items:center;justify-content:center;transition:transform 0.2s;}
        .thumbnail-container:active .play-button{transform:scale(0.95);}
        .play-icon{width:0;height:0;border-left:20px solid white;border-top:12px solid transparent;border-bottom:12px solid transparent;margin-left:4px;}</style>
        </head><body>
        <div class="thumbnail-container" onclick="Android.loadVideo()">
        <img class="thumbnail" src="https://img.youtube.com/vi/$trailerId/maxresdefault.jpg"
             onerror="this.src='https://img.youtube.com/vi/$trailerId/hqdefault.jpg'" alt="Trailer">
        <div class="play-button"><div class="play-icon"></div></div></div></body></html>
    """.trimIndent()

    private fun loadTags(d: TmdbDetail) {
        val tags = d.keywords?.keywords?.map { it.name } ?: emptyList()
        if (tags.isEmpty()) return
        val bind = ItemTitleChipgroupBinding.inflate(layoutInflater)
        bind.itemTitle.setText(R.string.tags)
        tags.forEach { tag ->
            val chip = ItemChipBinding.inflate(layoutInflater, bind.itemChipGroup, false).root
            chip.text = tag
            chip.setOnClickListener {
                val intent = Intent(this, TmdbSearchActivity::class.java)
                intent.putExtra("query", tag)
                startActivity(intent)
            }
            FocusEffectUtil.applyFocusListener(chip)
            bind.itemChipGroup.addView(chip)
        }
        binding.mediaInfoTagsContainer.addView(bind.root)
    }

    private fun loadRelations(d: TmdbDetail) {
        val collectionId = d.collection?.id
        if (collectionId == null) {
            binding.tmdbDetailRelationChips.visibility = View.GONE
            return
        }
        binding.tmdbDetailRelationChips.visibility = View.VISIBLE
        binding.tmdbDetailRelationChips.removeAllViews()
        lifecycleScope.launch(Dispatchers.IO) {
            val parts = Tmdb.collection(collectionId)
            withContext(Dispatchers.Main) {
                parts.forEach { part ->
                    val iv = ImageView(this@TmdbDetailsActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(140), dp(200)).apply {
                            marginEnd = dp(8)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        loadImage(Tmdb.imageUrl(part.posterPath, 342))
                        contentDescription = part.displayTitle
                        setOnClickListener {
                            val intent =
                                Intent(this@TmdbDetailsActivity, TmdbDetailsActivity::class.java)
                            intent.putExtra(ARG_MEDIA_TYPE, if (part.mediaType == "tv") "tv" else "movie")
                            intent.putExtra(ARG_MEDIA_ID, part.id)
                            startActivity(intent)
                        }
                        FocusEffectUtil.applyFocusListener(this)
                    }
                    binding.tmdbDetailRelationChips.addView(iv)
                }
            }
        }
    }

    private fun loadPrequelSequel(d: TmdbDetail) {
        val colId = d.collection?.id
        if (colId == null) {
            binding.tmdbDetailPrequelSequel.visibility = View.GONE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val parts = Tmdb.collection(colId)
            val idx = parts.indexOfFirst { it.id == d.id }
            val pre = if (idx > 0) parts[idx - 1] else null
            val seq = if (idx in 0 until parts.lastIndex) parts[idx + 1] else null
            withContext(Dispatchers.Main) {
                if (pre == null && seq == null) {
                    binding.tmdbDetailPrequelSequel.visibility = View.GONE
                    return@withContext
                }
                binding.tmdbDetailPrequelSequel.visibility = View.VISIBLE
                if (pre != null) {
                    binding.tmdbDetailPrequel.visibility = View.VISIBLE
                    binding.tmdbDetailPrequelBanner.loadImage(
                        Tmdb.imageUrl(pre.backdropPath, 780) ?: Tmdb.imageUrl(pre.posterPath, 342)
                    )
                    binding.tmdbDetailPrequelTitle.text = pre.displayTitle
                    binding.tmdbDetailPrequel.setOnClickListener { openDetails(pre.id, pre.mediaType ?: "movie") }
                } else binding.tmdbDetailPrequel.visibility = View.GONE
                if (seq != null) {
                    binding.tmdbDetailSequel.visibility = View.VISIBLE
                    binding.tmdbDetailSequelBanner.loadImage(
                        Tmdb.imageUrl(seq.backdropPath, 780) ?: Tmdb.imageUrl(seq.posterPath, 342)
                    )
                    binding.tmdbDetailSequelTitle.text = seq.displayTitle
                    binding.tmdbDetailSequel.setOnClickListener { openDetails(seq.id, seq.mediaType ?: "movie") }
                } else binding.tmdbDetailSequel.visibility = View.GONE
            }
        }
    }

    private fun openDetails(id: Int, type: String) {
        val intent = Intent(this, TmdbDetailsActivity::class.java)
        intent.putExtra(ARG_MEDIA_TYPE, if (type == "tv") "tv" else "movie")
        intent.putExtra(ARG_MEDIA_ID, id)
        startActivity(intent)
    }

    private fun buildCastSection(d: TmdbDetail) {
        val cast = d.credits?.cast?.take(20) ?: emptyList()
        if (cast.isEmpty()) {
            binding.mediaInfoCastTitle.visibility = View.GONE
            binding.mediaInfoCastRecycler.visibility = View.GONE
            return
        }
        binding.mediaInfoCastTitle.visibility = View.VISIBLE
        binding.mediaInfoCastRecycler.visibility = View.VISIBLE
        binding.mediaInfoCastRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.mediaInfoCastRecycler.adapter = CastAdapter(cast) {}
    }

    private fun buildMoreLikeSection(d: TmdbDetail) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recs = d.recommendations?.results ?: emptyList()
            withContext(Dispatchers.Main) {
                if (recs.isEmpty()) {
                    binding.mediaInfoRecommendTitle.visibility = View.GONE
                    binding.mediaInfoRecommendRecycler.visibility = View.GONE
                    return@withContext
                }
                binding.mediaInfoRecommendTitle.visibility = View.VISIBLE
                binding.mediaInfoRecommendRecycler.visibility = View.VISIBLE
                binding.mediaInfoRecommendRecycler.layoutManager =
                    LinearLayoutManager(this@TmdbDetailsActivity, LinearLayoutManager.HORIZONTAL, false)
                binding.mediaInfoRecommendRecycler.adapter = MoreLikeAdapter(recs) { m ->
                    val intent = Intent(this@TmdbDetailsActivity, TmdbDetailsActivity::class.java)
                    intent.putExtra(ARG_MEDIA_TYPE, m.mediaType)
                    intent.putExtra(ARG_MEDIA_ID, m.id)
                    startActivity(intent)
                }
            }
        }
    }

// __PART4__

    private fun onPlayClick() {
        val intent = Intent(this, TmdbWatchActivity::class.java).apply {
            putExtra(TmdbWatchActivity.ARG_MEDIA_TYPE, mediaType)
            putExtra(TmdbWatchActivity.ARG_MEDIA_ID, mediaId)
            pluginSourceId?.let { putExtra(TmdbWatchActivity.ARG_PLUGIN_SOURCE, it) }
            pluginUrl?.let { putExtra(TmdbWatchActivity.ARG_PLUGIN_URL, it) }
        }
        startActivity(intent)
    }

    private fun refreshListLabel(d: TmdbDetail) {
        lifecycleScope.launch(Dispatchers.IO) {
            val st = runCatching {
                Simkl.getMediaStatus(mediaType, d.id, d.externalIds?.imdbId)
            }.getOrNull()
            withContext(Dispatchers.Main) { updateListButtonLabel(st) }
        }
    }

    private fun updateListButtonLabel(simklStatus: String?) {
        val labels = resources.getStringArray(R.array.status_anime)
        binding.mediaInfoAddToList.text =
            if (simklStatus == null) getString(R.string.add_to_list)
            else labels[TmdbListDialogFragment.simklStatusToIndex(simklStatus)]
    }

    private fun openListEditor(d: TmdbDetail) {
        val ids = d.externalIds
        val frag = TmdbListDialogFragment.newInstance(
            type = mediaType,
            tmdbId = d.id,
            imdbId = ids?.imdbId,
            anilistId = null,
            title = d.displayTitle,
            year = d.releaseDate?.take(4)?.toIntOrNull(),
            cover = Tmdb.imageUrl(d.backdropPath, 780) ?: Tmdb.imageUrl(d.posterPath, 342),
            totalEpisodes = if (mediaType == "tv") d.numberOfEpisodes else null
        )
        frag.onSaved = { refreshListLabel(d) }
        frag.show(supportFragmentManager, "tmdbList")
    }

    private suspend fun simklProgress(
        type: String, tmdbId: Int, imdbId: String?
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val items = if (type == "tv") Simkl.getShowLibrary() else Simkl.getMovieLibrary()
        val item = items.firstOrNull {
            val ids = it.ids
            ids != null && (ids.tmdb == tmdbId || (!imdbId.isNullOrBlank() && ids.imdb == imdbId))
        } ?: return@withContext null
        val w = item.lastWatchedEpisode ?: 0
        val y = item.totalEpisodes ?: 0
        if (w == 0 && y == 0) null else Pair(w, y)
    }

    class CastAdapter(
        private val items: List<TmdbCast>,
        private val onClick: (TmdbCast) -> Unit
    ) : RecyclerView.Adapter<CastAdapter.VH>() {
        class VH(val b: ItemTmdbCastBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
            VH(ItemTmdbCastBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val c = items[i]
            h.b.tmdbCastImage.loadImage(Tmdb.imageUrl(c.profilePath, 185))
            h.b.tmdbCastName.text = c.name
            h.b.tmdbCastRole.text = c.character
            h.b.root.setOnClickListener { onClick(c) }
        }
    }

    class MoreLikeAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<MoreLikeAdapter.VH>() {
        class VH(val b: ItemTmdbCardBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
            VH(ItemTmdbCardBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            h.b.tmdbCardPoster.loadImage(Tmdb.imageUrl(m.posterPath, 342))
            h.b.tmdbCardTitle.text = m.displayTitle
            h.b.root.contentDescription = m.displayTitle
            h.b.root.setOnClickListener { onClick(m) }
        }
    }

    class MyChrome : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) = Unit
        override fun onHideCustomView() = Unit
    }
}
