package ani.sanin.download

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import ani.sanin.R
import ani.sanin.databinding.ActivityDownloadsBinding
import ani.sanin.databinding.ItemDownloadBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Queue screen: the "Queue" tab shows what is downloading/waiting, the "Downloaded" tab shows what
 * is already on disk and can be played or deleted. Everything is driven straight from
 * [DownloadManager]'s state flows.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = DownloadRowAdapter()

    private var selectedTab = 0
    private var lastQueue: List<DownloadItem> = emptyList()
    private var lastDone: List<DownloadedItem> = emptyList()

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

        binding.downloadsPauseAll.setOnClickListener {
            val anyActive = lastQueue.any {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
            }
            if (anyActive) DownloadManager.pauseAll() else DownloadManager.resumeAll()
        }
        FocusEffectUtil.applyFocusListener(binding.downloadsPauseAll)

        binding.downloadsRecycler.layoutManager = LinearLayoutManager(this)
        binding.downloadsRecycler.adapter = adapter

        binding.downloadsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.position
                render(lastQueue, lastDone)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        lifecycleScope.launch {
            combine(DownloadManager.queue, DownloadManager.completed) { q, c -> q to c }
                .flowWithLifecycle(lifecycle)
                .collect { (queue, done) -> render(queue, done) }
        }
    }

    private fun render(queue: List<DownloadItem>, done: List<DownloadedItem>) {
        lastQueue = queue
        lastDone = done
        val rows = ArrayList<Row>(queue.size + done.size)
        if (selectedTab == 0) {
            queue.forEach { rows.add(Row.Active(it)) }
        } else {
            done.forEach { rows.add(Row.Done(it)) }
        }
        Log.i(
            "DownloadsUI",
            "render tab=$selectedTab queue=${queue.size} done=${done.size} rows=${rows.size}",
        )
        adapter.submit(rows)
        binding.downloadsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadsRecycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        updatePauseAll(queue)
    }

    private fun updatePauseAll(queue: List<DownloadItem>) {
        val anyActive = queue.any {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        val anyPaused = queue.any { it.status == DownloadStatus.PAUSED }
        when {
            anyActive -> {
                binding.downloadsPauseAll.visibility = View.VISIBLE
                binding.downloadsPauseAll.setText(R.string.download_pause_all)
            }

            anyPaused -> {
                binding.downloadsPauseAll.visibility = View.VISIBLE
                binding.downloadsPauseAll.setText(R.string.download_resume_all)
            }

            else -> binding.downloadsPauseAll.visibility = View.GONE
        }
    }

    private sealed class Row {
        data class Active(val item: DownloadItem) : Row()
        data class Done(val item: DownloadedItem) : Row()
    }

    private inner class DownloadRowAdapter :
        RecyclerView.Adapter<DownloadRowAdapter.VH>() {

        private val rows = mutableListOf<Row>()

        fun submit(next: List<Row>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = rows.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    sameId(rows[oldPos], next[newPos])

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    rows[oldPos] == next[newPos]

                override fun getChangePayload(oldPos: Int, newItem: Int): Any? {
                    val a = rows[oldPos]
                    val b = next[newItem]
                    if (a is Row.Active && b is Row.Active && a.item.isRunning() && b.item.isRunning()) {
                        return PAYLOAD_PROGRESS
                    }
                    return null
                }
            })
            rows.clear()
            rows.addAll(next)
            diff.dispatchUpdatesTo(this)
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

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_PROGRESS)) {
                (rows[position] as? Row.Active)?.let { holder.updateActiveProgress(it.item) }
                return
            }
            super.onBindViewHolder(holder, position, payloads)
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
                    "Episode ${item.episodeNumber} \u00b7 $server"
                }
                b.downloadStatus.text = statusText(item)
                b.downloadPlay.visibility = View.GONE
                b.downloadDelete.visibility = View.GONE

                val started = item.progress >= 0.01f
                when (item.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        b.downloadProgress.visibility = View.VISIBLE
                        if (started) {
                            val pct = (item.progress * 100).toInt().coerceIn(1, 100)
                            b.downloadProgress.isIndeterminate = false
                            b.downloadProgress.progress = pct
                            b.downloadPercent.visibility = View.VISIBLE
                            b.downloadPercent.text = "$pct%"
                        } else {
                            b.downloadProgress.isIndeterminate = true
                            b.downloadPercent.visibility = View.GONE
                        }
                        b.downloadPrimary.visibility = View.VISIBLE
                        b.downloadPrimary.setText(R.string.download_pause)
                        b.downloadPrimary.setOnClickListener { DownloadManager.pause(item.id) }
                    }

                    DownloadStatus.PAUSED, DownloadStatus.ERROR -> {
                        b.downloadProgress.visibility = View.GONE
                        b.downloadPercent.visibility = View.GONE
                        b.downloadPrimary.visibility = View.VISIBLE
                        b.downloadPrimary.setText(
                            if (item.status == DownloadStatus.ERROR) R.string.download_retry
                            else R.string.download_resume,
                        )
                        b.downloadPrimary.setOnClickListener { DownloadManager.resume(item.id) }
                    }

                    else -> {
                        b.downloadProgress.visibility = View.GONE
                        b.downloadPercent.visibility = View.GONE
                        b.downloadPrimary.visibility = View.GONE
                    }
                }
                b.downloadRemove.visibility = View.VISIBLE
                b.downloadRemove.setOnClickListener { DownloadManager.cancel(item.id) }
            }

            /** Cheap rebind used while a download is running: touches only text/bar, never the image. */
            fun updateActiveProgress(item: DownloadItem) {
                b.downloadStatus.text = statusText(item)
                val started = item.progress >= 0.01f
                if (started) {
                    val pct = (item.progress * 100).toInt().coerceIn(1, 100)
                    b.downloadProgress.isIndeterminate = false
                    b.downloadProgress.progress = pct
                    b.downloadPercent.visibility = View.VISIBLE
                    b.downloadPercent.text = "$pct%"
                } else {
                    b.downloadProgress.isIndeterminate = true
                    b.downloadPercent.visibility = View.GONE
                }
            }

            fun bindDone(item: DownloadedItem) {
                b.downloadCover.loadImage(item.cover)
                b.downloadTitle.text = item.mediaName
                b.downloadSubtitle.text = "Episode ${item.episodeNumber}"
                b.downloadStatus.text = "Downloaded \u00b7 ${formatSize(item.sizeBytes)}"
                b.downloadProgress.visibility = View.GONE
                b.downloadPercent.visibility = View.GONE
                b.downloadPrimary.visibility = View.GONE
                b.downloadRemove.visibility = View.GONE

                b.downloadPlay.visibility = View.VISIBLE
                b.downloadPlay.setOnClickListener { playDownload(item) }
                b.downloadDelete.visibility = View.VISIBLE
                b.downloadDelete.setOnClickListener { confirmDelete(item) }
            }

            private fun statusText(item: DownloadItem): String = when (item.status) {
                DownloadStatus.QUEUED -> getString(R.string.downloading)
                DownloadStatus.DOWNLOADING -> {
                    val parts = ArrayList<String>(3)
                    parts.add(getString(R.string.downloading))
                    val size = when {
                        item.totalBytes > 0L ->
                            "${formatSize(item.downloadedBytes)} / ${formatSize(item.totalBytes)}"

                        item.downloadedBytes > 0L -> formatSize(item.downloadedBytes)
                        else -> ""
                    }
                    if (size.isNotEmpty()) parts.add(size)
                    if (item.speed > 0L) parts.add(formatSpeed(item.speed))
                    parts.joinToString(" \u00b7 ")
                }

                DownloadStatus.PAUSED -> item.error ?: getString(R.string.download_resume)
                DownloadStatus.FINISHED -> getString(R.string.download_complete)
                DownloadStatus.ERROR -> item.error ?: getString(R.string.download_failed)
            }
        }
    }

    private fun playDownload(item: DownloadedItem) {
        ExoplayerView.media = item.toOfflineMedia()
        ExoplayerView.initialized = true
        ExoplayerView.offlinePlayback = true
        startActivity(Intent(this, ExoplayerView::class.java))
    }

    private fun confirmDelete(item: DownloadedItem) {
        customAlertDialog().apply {
            setTitle(getString(R.string.download_delete_title))
            setMessage(getString(R.string.download_delete_msg))
            setPosButton(getString(R.string.download_delete)) {
                DownloadManager.removeCompleted(item.id, true)
            }
            setNegButton(getString(R.string.cancel)) {}
        }.show()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1024.0 -> "${trim(mb / 1024.0, 2)} GB"
            mb >= 1.0 -> "${trim(mb, 1)} MB"
            else -> "${kb.toLong()} KB"
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) "${trim(mb, 1)} MB/s" else "${bytesPerSec / 1024} KB/s"
    }

    private fun trim(value: Double, decimals: Int): String {
        val s = String.format(Locale.US, "%.${decimals}f", value)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun DownloadItem.isRunning(): Boolean =
        status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    private fun sameId(a: Row, b: Row): Boolean = when {
        a is Row.Active && b is Row.Active -> a.item.id == b.item.id
        a is Row.Done && b is Row.Done -> a.item.id == b.item.id
        else -> false
    }

    companion object {
        private const val TYPE_ACTIVE = 0
        private const val TYPE_DONE = 1
        private const val PAYLOAD_PROGRESS = "progress"
    }
}