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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.databinding.ActivityTmdbSearchBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbHistoryBinding
import ani.sanin.loadImage
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.launch

class TmdbSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTmdbSearchBinding
    private val adapter = TmdbSearchGridAdapter { media -> openDetails(media) }
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
            val results = Tmdb.search(query)
            binding.tmdbSearchProgress.isVisible = false
            adapter.submit(results)
            binding.tmdbSearchEmpty.isVisible = results.isEmpty()
            if (results.isEmpty()) snackString("No results for '$query'")
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
