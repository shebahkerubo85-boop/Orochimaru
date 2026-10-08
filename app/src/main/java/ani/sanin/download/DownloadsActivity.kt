package ani.sanin.download

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ActivityDownloadsBinding
import ani.sanin.databinding.ItemDownloadBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Minimal queue screen: what is downloading, what is waiting and what is already on disk.
 * Everything is driven straight from [DownloadManager]'s state flows.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = DownloadRowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.downloadsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.downloadsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        FocusEffectUtil.applyFocusListener(binding.downloadsBack)

        binding.downloadsRecycler.layoutManager = LinearLayoutManager(this)
        binding.downloadsRecycler.adapter = adapter

        lifecycleScope.launch {
            combine(DownloadManager.queue, DownloadManager.completed) { q, c -> q to c }
                .flowWithLifecycle(lifecycle)
                .collect { (queue, done) -> render(queue, done) }
        }
    }

    private fun render(queue: List<DownloadItem>, done: List<DownloadedItem>) {
        val rows = ArrayList<Row>(queue.size + done.size)
        queue.forEach { rows.add(Row.Active(it)) }
        done.forEach { rows.add(Row.Done(it)) }
        adapter.submit(rows)
        binding.downloadsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadsRecycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private sealed class Row {
        data class Active(val item: DownloadItem) : Row()
        data class Done(val item: DownloadedItem) : Row()
    }

    private inner class DownloadRowAdapter :
        RecyclerView.Adapter<DownloadRowAdapter.VH>() {

        private val rows = mutableListOf<Row>()

        fun submit(next: List<Row>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Active) TYPE_ACTIVE else TYPE_DONE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDownloadBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (val row = rows[position]) {
                is Row.Active -> holder.bindActive(row.item)
                is Row.Done -> holder.bindDone(row.item)
            }
        }

        inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                FocusEffectUtil.applyFocusListener(b.root)
            }

            fun bindActive(item: DownloadItem) {
                b.downloadCover.loadImage(item.cover)
                b.downloadTitle.text = item.mediaName
                val server = item.serverName
                b.downloadSubtitle.text = if (server.isNullOrBlank()) {
                    getString(R.string.downloading_episode, item.episodeNumber)
                } else {
                    "Episode ${item.episodeNumber} · $server"
                }
                b.downloadStatus.text = statusText(item)

                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        b.downloadProgress.visibility = View.VISIBLE
                        if (item.progress > 0f) {
                            b.downloadProgress.isIndeterminate = false
                            b.downloadProgress.progress = (item.progress * 100).toInt()
                        } else {
                            b.downloadProgress.isIndeterminate = true
                        }
                        b.downloadPrimary.visibility = View.VISIBLE
                        b.downloadPrimary.setText(R.string.download_pause)
                        b.downloadPrimary.setOnClickListener { DownloadManager.pause(item.id) }
                    }

                    DownloadStatus.QUEUED -> {
                        b.downloadProgress.visibility = View.VISIBLE
                        b.downloadProgress.isIndeterminate = true
                        b.downloadPrimary.visibility = View.VISIBLE
                        b.downloadPrimary.setText(R.string.download_pause)
                        b.downloadPrimary.setOnClickListener { DownloadManager.pause(item.id) }
                    }

                    DownloadStatus.PAUSED, DownloadStatus.ERROR -> {
                        b.downloadProgress.visibility = View.GONE
                        b.downloadPrimary.visibility = View.VISIBLE
                        b.downloadPrimary.setText(
                            if (item.status == DownloadStatus.ERROR) R.string.download_retry
                            else R.string.download_resume,
                        )
                        b.downloadPrimary.setOnClickListener { DownloadManager.resume(item.id) }
                    }

                    else -> {
                        b.downloadProgress.visibility = View.GONE
                        b.downloadPrimary.visibility = View.GONE
                    }
                }
                b.downloadRemove.setOnClickListener { DownloadManager.cancel(item.id) }
            }

            fun bindDone(item: DownloadedItem) {
                b.downloadCover.loadImage(item.cover)
                b.downloadTitle.text = item.mediaName
                b.downloadSubtitle.text = "Episode ${item.episodeNumber}"
                b.downloadStatus.text = "Downloaded · ${formatSize(item.sizeBytes)}"
                b.downloadProgress.visibility = View.GONE
                b.downloadPrimary.visibility = View.GONE
                b.downloadRemove.setOnClickListener {
                    DownloadManager.removeCompleted(item.id, true)
                }
            }

            private fun statusText(item: DownloadItem): String = when (item.status) {
                DownloadStatus.QUEUED -> getString(R.string.downloading)
                DownloadStatus.DOWNLOADING -> when {
                    item.progress > 0f -> "${getString(R.string.downloading)} ${(item.progress * 100).toInt()}%" +
                        if (item.downloadedBytes > 0L) " · ${formatSize(item.downloadedBytes)}" else ""

                    item.downloadedBytes > 0L ->
                        "${getString(R.string.downloading)} · ${formatSize(item.downloadedBytes)}"

                    else -> getString(R.string.downloading)
                }

                DownloadStatus.PAUSED -> item.error ?: getString(R.string.download_resume)
                DownloadStatus.FINISHED -> getString(R.string.download_complete)
                DownloadStatus.ERROR -> item.error ?: getString(R.string.download_failed)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.1f MB", mb)
    }

    companion object {
        private const val TYPE_ACTIVE = 0
        private const val TYPE_DONE = 1
    }
}