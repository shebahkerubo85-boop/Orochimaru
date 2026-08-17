package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ActivityTmdbSearchBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbHistoryBinding
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.chip.Chip
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

class TmdbSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTmdbSearchBinding
    private val adapter = TmdbSearchGridAdapter { media -> openDetails(media) }
    private val pluginAdapter = PluginSearchAdapter { item -> openPluginDetails(item) }
    private var selectedPluginApi: com.lagradost.cloudstream3.MainAPI? = null
    private var selectedPluginSource: CsInstalledSource? = null
    private val historyAdapter = HistoryAdapter { query -> runSearch(query) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tmdbSearchGrid.layoutManager = GridLayoutManager(this, 3)
        binding.tmdbSearchGrid.adapter = adapter

        binding.tmdbSearchBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchBack)

        binding.tmdbSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val q = binding.tmdbSearchInput.text.toString().trim()
                if (q.isNotEmpty()) runSearch(q)
                true
            } else false
        }

        binding.tmdbSearchClearHistory.setOnClickListener {
            PrefManager.setVal(PrefName.TmdbSearchHistory, emptyList<String>())
            buildPluginChips()
        showHistory()
            snackString("Search history cleared")
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchClearHistory)

        binding.tmdbSearchFilter.setOnClickListener {
            val dialog = TmdbSearchFilterDialog.newInstance { result -> runFiltered(result) }
            dialog.show(supportFragmentManager, "tmdbFilter")
        }
        FocusEffectUtil.applyFocusListener(binding.tmdbSearchFilter)

        showHistory()
    }

    private fun history(): List<String> = PrefManager.getVal(PrefName.TmdbSearchHistory)

    private fun addHistory(query: String) {
        val current = history()
        val updated = (listOf(query) + current.filterNot { it.equals(query, true) }).take(10)
        PrefManager.setVal(PrefName.TmdbSearchHistory, updated)
    }


    private fun buildPluginChips() {
        val installed = CsRepos.installed(this)
        if (installed.isEmpty()) return
        binding.tmdbSearchPluginScroll.isVisible = true
        val group = binding.tmdbSearchPluginChips
        group.removeAllViews()
        // TMDB chip
        val tmdb = Chip(this).apply {
            text = "TMDB"; isCheckable = true; isClickable = true; isFocusable = true; tag = "tmdb"
            isChecked = true
        }
        tmdb.setOnClickListener { selectPluginSource(null) }
        FocusEffectUtil.applyFocusListener(tmdb)
        group.addView(tmdb)
        installed.forEach { source ->
            val chip = Chip(this).apply {
                text = source.name; isCheckable = true; isClickable = true; isFocusable = true; tag = source.id
            }
            chip.setOnClickListener {
                val api = with(Dispatchers.IO) { CsRuntime.apisFor(this@TmdbSearchActivity, source).firstOrNull() }
                selectedPluginSource = source
                selectPluginSource(api)
            }
            FocusEffectUtil.applyFocusListener(chip)
            group.addView(chip)
        }
    }

    private fun selectPluginSource(api: com.lagradost.cloudstream3.MainAPI?) {
        selectedPluginApi = api
        binding.tmdbSearchGrid.adapter = if (api != null) pluginAdapter else adapter
        binding.tmdbSearchGrid.layoutManager = if (api != null)
            androidx.recyclerview.widget.LinearLayoutManager(this)
        else
            androidx.recyclerview.widget.GridLayoutManager(this, 3)
    }

    private fun showHistory() {
        val items = history()
        binding.tmdbSearchHistory.isVisible = items.isNotEmpty()
        binding.tmdbSearchGrid.isVisible = items.isEmpty()
        historyAdapter.submit(items)
        binding.tmdbSearchHistory.layoutManager = LinearLayoutManager(this)
        binding.tmdbSearchHistory.adapter = historyAdapter
    }

    private fun runSearch(query: String) {
        addHistory(query)
        binding.tmdbSearchHistory.isVisible = false
        binding.tmdbSearchGrid.isVisible = true
        binding.tmdbSearchProgress.isVisible = true
        binding.tmdbSearchEmpty.isVisible = false
        lifecycleScope.launch {
            if (selectedPluginApi != null) {
                val api = selectedPluginApi!!
                val results = withContext(Dispatchers.IO) {
                    runCatching { api.search(query, 1)?.items }.getOrNull() ?: emptyList()
                }
                binding.tmdbSearchProgress.isVisible = false
                pluginAdapter.submit(results)
                binding.tmdbSearchEmpty.isVisible = results.isEmpty()
                if (results.isEmpty()) snackString("No results for '$query' in ${api.name}")
            } else {
                val results = Tmdb.search(query)
                binding.tmdbSearchProgress.isVisible = false
                adapter.submit(results)
                binding.tmdbSearchEmpty.isVisible = results.isEmpty()
                if (results.isEmpty()) snackString("No results for '$query'")
            }
        }
    }

    private fun runFiltered(filter: TmdbFilterResult) {
        binding.tmdbSearchHistory.isVisible = false
        binding.tmdbSearchGrid.isVisible = true
        binding.tmdbSearchProgress.isVisible = true
        binding.tmdbSearchEmpty.isVisible = false
        lifecycleScope.launch {
            val results = Tmdb.discover(
                mediaType = filter.mediaType,
                genres = filter.genres.joinToString(",").ifBlank { null },
                keywords = filter.keywords.joinToString(",").ifBlank { null },
                year = filter.year,
                sort = filter.sort
            )
            binding.tmdbSearchProgress.isVisible = false
            adapter.submit(results)
            binding.tmdbSearchEmpty.isVisible = results.isEmpty()
            if (results.isEmpty()) snackString("No results with this filter")
        }
    }

    private fun openDetails(media: TmdbMedia) {
        startActivity(
            Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, media.type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun openPluginDetails(item: com.lagradost.cloudstream3.SearchResponse) {
        val source = selectedPluginSource ?: return
        startActivity(
            Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_SOURCE, source.id)
                .putExtra(TmdbDetailsActivity.ARG_PLUGIN_URL, item.url)
        )
    }

    class PluginSearchAdapter(
        private val onClick: (com.lagradost.cloudstream3.SearchResponse) -> Unit
    ) : RecyclerView.Adapter<PluginSearchAdapter.VH>() {
        private var items: List<com.lagradost.cloudstream3.SearchResponse> = emptyList()
        fun submit(list: List<com.lagradost.cloudstream3.SearchResponse>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbCardBinding.inflate(LayoutInflater.from(parent.context), parent, false); return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding; val landscape = TmdbCards.isLandscapeOrientation(); val size = TmdbCards.cardSize()
            val (w, h) = if (landscape) (260f*size).toInt() to (148f*size).toInt() else (102f*size).toInt() to (154f*size).toInt()
            b.tmdbCardPoster.updateLayoutParams<ViewGroup.LayoutParams> { width = w; height = h }
            b.tmdbCard.radius = TmdbCards.roundness()
            b.tmdbCardPoster.loadImage(item.posterUrl, if (landscape) 780 else 300)

            val titlePos = PrefManager.getVal<Int>(PrefName.CardTitlePosition)
            if (landscape) {
                when (titlePos) {
                    0 -> {
                        b.tmdbCardGradient.isVisible = true
                        b.tmdbCardGradient.updateLayoutParams<ViewGroup.LayoutParams> { width = w; height = h }
                        TmdbCards.setCardGradient(b.tmdbCardGradient)
                        b.tmdbCardOverlayTitle.isVisible = true
                        b.tmdbCardOverlayTitle.text = item.name
                        b.tmdbCardLogo.isVisible = false
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    2 -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardLogo.isVisible = false
                        b.tmdbCardTitle.isVisible = false
                        b.tmdbCardYear.isVisible = false
                    }
                    else -> {
                        b.tmdbCardGradient.isVisible = false
                        b.tmdbCardOverlayTitle.isVisible = false
                        b.tmdbCardLogo.isVisible = false
                        b.tmdbCardTitle.isVisible = true
                        b.tmdbCardTitle.text = item.name
                        b.tmdbCardYear.isVisible = false
                    }
                }
            } else {
                b.tmdbCardGradient.isVisible = false
                b.tmdbCardOverlayTitle.isVisible = false
                b.tmdbCardLogo.isVisible = false
                b.tmdbCardTitle.isVisible = true
                b.tmdbCardTitle.text = item.name
                b.tmdbCardYear.isVisible = false
            }

            b.tmdbCardPoster.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(b.tmdbCardPoster)
        }
        override fun getItemCount() = items.size
        class VH(val binding: ItemTmdbCardBinding) : RecyclerView.ViewHolder(binding.root)
    }

    class TmdbSearchGridAdapter(
        private val onOpen: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<TmdbSearchGridAdapter.VH>() {

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

    class HistoryAdapter(
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private var items: List<String> = emptyList()

        fun submit(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTmdbHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tmdbHistoryText.text = item
            holder.binding.tmdbHistoryText.setOnClickListener { onClick(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.tmdbHistoryText)
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemTmdbHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
