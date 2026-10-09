package ani.sanin.download

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
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
import ani.sanin.databinding.ItemDownloadEpisodeBinding
import ani.sanin.databinding.ItemDownloadGroupBinding
import ani.sanin.initActivity
import ani.sanin.loadImage
import ani.sanin.media.anime.ExoplayerView
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import ani.sanin.ui.LensButtonBackground
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Queue screen. Both tabs group downloads by series into a main card that expands (tap anywhere
 * except the buttons) into the individual episode cards, ordered by episode number.
 *
 * Queue tab:      main card -> looping bar + "done / queued" count + pause/retry lens + red X.
 *                 episode cards -> landscape thumb, title, server·quality, size + progress ring,
 *                 speed, small lens pause/retry + red X.
 * Downloaded tab: main card -> episode count + red X; expanded cards play on tap.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = DownloadRowAdapter()

    private val expandedGroups = mutableSetOf<Int>()

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
            val anyActive = lastQueue.any { it.isActive }
            if (anyActive) DownloadManager.pauseAll() else DownloadManager.resumeAll()
        }
        LensButtonBackground.apply(binding.downloadsPauseAll)
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
        val rows = if (selectedTab == 0) buildQueueRows(queue, done) else buildDoneRows(done)
        Log.i(
            "DownloadsUI",
            "render tab=$selectedTab queue=${queue.size} done=${done.size} rows=${rows.size}",
        )
        adapter.submit(rows)
        binding.downloadsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.downloadsRecycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        updatePauseAll(queue)
    }

    // ------------------------------------------------------------- grouping

    private fun buildQueueRows(
        queue: List<DownloadItem>,
        done: List<DownloadedItem>,
    ): List<Row> {
        if (queue.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<Int, MutableList<DownloadItem>>()
        for (item in queue) grouped.getOrPut(item.mediaId) { mutableListOf() }.add(item)

        val rows = ArrayList<Row>()
        for ((mediaId, items) in grouped) {
            items.sortWith(QUEUE_EPISODE_ORDER)
            val head = items.first()
            val doneForSeries = done.count { it.mediaId == mediaId }
            rows.add(
                Row.Group(
                    mediaId = mediaId,
                    title = head.mediaName,
                    cover = head.cover ?: head.thumbnail,
                    subtitle = "$doneForSeries / ${items.size}",
                    pause = groupPauseState(items),
                    isDownloadedTab = false,
                ),
            )
            if (expandedGroups.contains(mediaId)) {
                items.forEach { rows.add(Row.Active(it, mediaId)) }
            }
        }
        return rows
    }

    private fun buildDoneRows(done: List<DownloadedItem>): List<Row> {
        if (done.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<Int, MutableList<DownloadedItem>>()
        for (item in done) grouped.getOrPut(item.mediaId) { mutableListOf() }.add(item)

        val rows = ArrayList<Row>()
        for ((mediaId, items) in grouped) {
            items.sortWith(DONE_EPISODE_ORDER)
            val head = items.first()
            rows.add(
                Row.Group(
                    mediaId = mediaId,
                    title = head.mediaName,
                    cover = head.cover ?: head.thumbnail,
                    subtitle = "${items.size} episodes",
                    pause = GroupPauseState.NONE,
                    isDownloadedTab = true,
                ),
            )
            if (expandedGroups.contains(mediaId)) {
                items.forEach { rows.add(Row.Done(it, mediaId)) }
            }
        }
        return rows
    }

    private fun groupPauseState(items: List<DownloadItem>): GroupPauseState = when {
        items.any { it.isActive } -> GroupPauseState.PAUSE
        items.any { it.status == DownloadStatus.ERROR } -> GroupPauseState.RETRY
        items.any { it.status == DownloadStatus.PAUSED } -> GroupPauseState.RESUME
        else -> GroupPauseState.NONE
    }

    private fun toggleGroup(mediaId: Int) {
        if (!expandedGroups.remove(mediaId)) expandedGroups.add(mediaId)
        render(lastQueue, lastDone)
    }

    private fun updatePauseAll(queue: List<DownloadItem>) {
        val anyActive = queue.any { it.isActive }
        val anyPaused = queue.any { it.status == DownloadStatus.PAUSED }
        when {
            anyActive -> {
                binding.downloadsPauseAllContainer.visibility = View.VISIBLE
                binding.downloadsPauseAllLabel.setText(R.string.download_pause_all)
                binding.downloadsPauseAll.setImageResource(R.drawable.ic_baseline_pause_24)
            }

            anyPaused -> {
                binding.downloadsPauseAllContainer.visibility = View.VISIBLE
                binding.downloadsPauseAllLabel.setText(R.string.download_resume_all)
                binding.downloadsPauseAll.setImageResource(R.drawable.ic_baseline_play_arrow_24)
            }

            else -> binding.downloadsPauseAllContainer.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------- row model

    private sealed class Row {
        data class Group(
            val mediaId: Int,
            val title: String,
            val cover: String?,
            val subtitle: String,
            val pause: GroupPauseState,
            val isDownloadedTab: Boolean,
        ) : Row()

        data class Active(val item: DownloadItem, val mediaId: Int) : Row()
        data class Done(val item: DownloadedItem, val mediaId: Int) : Row()
    }

    private enum class GroupPauseState { NONE, PAUSE, RESUME, RETRY }

    // ------------------------------------------------------------- adapter

    private inner class DownloadRowAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                    if (a is Row.Active && b is Row.Active && a.item.id == b.item.id &&
                        a.item.isActive && b.item.isActive
                    ) {
                        return PAYLOAD_PROGRESS
                    }
                    return null
                }
            }, false)
            rows.clear()
            rows.addAll(next)
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Group -> TYPE_GROUP
            is Row.Active, is Row.Done -> TYPE_EPISODE
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_GROUP) {
                GroupVH(ItemDownloadGroupBinding.inflate(inflater, parent, false))
            } else {
                EpisodeVH(ItemDownloadEpisodeBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (payloads.contains(PAYLOAD_PROGRESS)) {
                val item = (rows[position] as? Row.Active)?.item ?: return
                (holder as EpisodeVH).bindProgress(item)
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Group -> (holder as GroupVH).bind(row)
                is Row.Active -> (holder as EpisodeVH).bindActive(row.item)
                is Row.Done -> (holder as EpisodeVH).bindDone(row.item)
            }
        }

        private fun sameId(a: Row, b: Row): Boolean = when {
            a is Row.Group && b is Row.Group -> a.mediaId == b.mediaId
            a is Row.Active && b is Row.Active -> a.item.id == b.item.id
            a is Row.Done && b is Row.Done -> a.item.id == b.item.id
            else -> false
        }
    }

    // ------------------------------------------------------------- holders

    private inner class GroupVH(private val b: ItemDownloadGroupBinding) :
        RecyclerView.ViewHolder(b.root) {

        init {
            LensButtonBackground.apply(b.groupAction)
            LensButtonBackground.apply(b.groupDelete)
        }

        fun bind(row: Row.Group) {
            b.groupCover.loadImage(row.cover)
            b.groupTitle.text = row.title
            b.groupCount.text = row.subtitle

            // Red X is always present; on the Queue tab it cancels the whole group, on the
            // Downloaded tab it deletes every finished episode of the series (with confirm).
            if (row.isDownloadedTab) {
                b.groupProgress.visibility = View.GONE
                b.groupAction.visibility = View.GONE
                b.groupDelete.visibility = View.VISIBLE
                b.groupDelete.setOnClickListener { confirmDeleteGroup(row.mediaId) }
            } else {
                b.groupProgress.visibility = View.VISIBLE
                b.groupProgress.isIndeterminate = true
                b.groupDelete.visibility = View.VISIBLE
                b.groupDelete.setOnClickListener { cancelGroup(row.mediaId) }
                bindAction(row)
            }
            b.root.setOnClickListener { toggleGroup(row.mediaId) }
            FocusEffectUtil.applyFocusListener(b.root)
        }

        private fun bindAction(row: Row.Group) {
            when (row.pause) {
                GroupPauseState.NONE -> b.groupAction.visibility = View.GONE
                GroupPauseState.PAUSE -> {
                    b.groupAction.visibility = View.VISIBLE
                    b.groupAction.setImageResource(R.drawable.ic_baseline_pause_24)
                    b.groupAction.setOnClickListener {
                        lastQueue.filter { it.mediaId == row.mediaId && it.isActive }
                            .forEach { DownloadManager.pause(it.id) }
                    }
                }

                GroupPauseState.RESUME -> {
                    b.groupAction.visibility = View.VISIBLE
                    b.groupAction.setImageResource(R.drawable.ic_baseline_play_arrow_24)
                    b.groupAction.setOnClickListener {
                        lastQueue.filter { it.mediaId == row.mediaId && it.status == DownloadStatus.PAUSED }
                            .forEach { DownloadManager.resume(it.id) }
                    }
                }

                GroupPauseState.RETRY -> {
                    b.groupAction.visibility = View.VISIBLE
                    b.groupAction.setImageResource(R.drawable.ic_baseline_replay_24)
                    b.groupAction.setOnClickListener {
                        lastQueue.filter { it.mediaId == row.mediaId && it.status == DownloadStatus.ERROR }
                            .forEach { DownloadManager.resume(it.id) }
                    }
                }
            }
        }
    }

    private inner class EpisodeVH(private val b: ItemDownloadEpisodeBinding) :
        RecyclerView.ViewHolder(b.root) {

        init {
            FocusEffectUtil.applyFocusListener(b.root)
            LensButtonBackground.apply(b.episodeAction)
            LensButtonBackground.apply(b.episodeDelete)
        }

        fun bindActive(item: DownloadItem) {
            b.root.setOnClickListener { toggleGroup(item.mediaId) }
            b.episodeThumb.loadImage(item.thumbnail ?: item.cover)
            b.episodeTitle.text = episodeLabel(item.episodeNumber, item.episodeTitle)
            b.episodeMeta.text = metaLabel(item.serverName, item.quality)
            b.episodeMeta.visibility = if (b.episodeMeta.text.isBlank()) View.GONE else View.VISIBLE

            bindProgress(item)

            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                    b.episodeAction.visibility = View.VISIBLE
                    b.episodeAction.setImageResource(R.drawable.ic_baseline_pause_24)
                    b.episodeAction.setOnClickListener { DownloadManager.pause(item.id) }
                }

                DownloadStatus.PAUSED, DownloadStatus.ERROR -> {
                    b.episodeAction.visibility = View.VISIBLE
                    b.episodeAction.setImageResource(
                        if (item.status == DownloadStatus.ERROR) R.drawable.ic_baseline_replay_24
                        else R.drawable.ic_baseline_play_arrow_24,
                    )
                    b.episodeAction.setOnClickListener { DownloadManager.resume(item.id) }
                }

                else -> b.episodeAction.visibility = View.GONE
            }
            b.episodeDelete.visibility = View.VISIBLE
            b.episodeDelete.setOnClickListener { DownloadManager.cancel(item.id) }
        }

        fun bindDone(item: DownloadedItem) {
            b.root.setOnClickListener { playDownload(item) }
            b.episodeThumb.loadImage(item.thumbnail ?: item.cover)
            b.episodeTitle.text = episodeLabel(item.episodeNumber, item.episodeTitle)
            b.episodeMeta.visibility = View.GONE
            b.episodeSize.text = formatSize(item.sizeBytes)
            b.episodeRing.visibility = View.GONE
            b.episodePercent.visibility = View.GONE
            b.episodeSpeed.text = ""
            b.episodeSpeed.visibility = View.GONE
            b.episodeAction.visibility = View.GONE
            b.episodeDelete.visibility = View.VISIBLE
            b.episodeDelete.setOnClickListener { confirmDelete(item) }
        }

        /** Cheap rebind while downloading: only the live fields, never the image. */
        fun bindProgress(item: DownloadItem) {
            b.episodeSize.text = sizeLabel(item)
            b.episodeSpeed.text = formatSpeed(item.speed)
            b.episodeSpeed.visibility = if (item.speed > 0L) View.VISIBLE else View.GONE

            val total = item.totalBytes
            val pct = if (total > 0L) {
                ((item.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
            } else {
                -1
            }
            val indeterminate = pct < 0
            b.episodeRing.apply {
                visibility = View.VISIBLE
                isIndeterminate = indeterminate
                if (!indeterminate) progress = pct
            }
            b.episodePercent.visibility = if (indeterminate) View.GONE else View.VISIBLE
            b.episodePercent.text = if (indeterminate) "" else "$pct%"
        }
    }

    // ------------------------------------------------------------- helpers

    private fun episodeLabel(number: String, title: String?): String {
        val label = title?.takeIf { it.isNotBlank() }
        return if (label == null) "Episode $number" else "$number · $label"
    }

    private fun metaLabel(server: String?, quality: Int?): String {
        val parts = ArrayList<String>(2)
        server?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        quality?.takeIf { it > 0 }?.let { parts.add("${it}p") }
        return parts.joinToString(" · ")
    }

    private fun sizeLabel(item: DownloadItem): String =
        if (item.totalBytes > 0L) {
            "${formatSize(item.downloadedBytes)} / ${formatSize(item.totalBytes)}"
        } else {
            formatSize(item.downloadedBytes)
        }

    private fun playDownload(item: DownloadedItem) {
        ExoplayerView.media = item.toOfflineMedia()
        ExoplayerView.initialized = true
        ExoplayerView.offlinePlayback = true
        startActivity(Intent(this, ExoplayerView::class.java))
    }

    private fun cancelGroup(mediaId: Int) {
        lastQueue.filter { it.mediaId == mediaId }.forEach { DownloadManager.cancel(it.id) }
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

    private fun confirmDeleteGroup(mediaId: Int) {
        val count = lastDone.count { it.mediaId == mediaId }
        if (count == 0) return
        customAlertDialog().apply {
            setTitle(getString(R.string.download_delete_title))
            setMessage(getString(R.string.download_delete_group_msg, count))
            setPosButton(getString(R.string.download_delete)) {
                lastDone.filter { it.mediaId == mediaId }
                    .forEach { DownloadManager.removeCompleted(it.id, true) }
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

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_EPISODE = 1
        private const val PAYLOAD_PROGRESS = "progress"

        private val QUEUE_EPISODE_ORDER = Comparator { a: DownloadItem, b: DownloadItem ->
            compareEpisode(a.episodeNumber, b.episodeNumber)
        }
        private val DONE_EPISODE_ORDER = Comparator { a: DownloadedItem, b: DownloadedItem ->
            compareEpisode(a.episodeNumber, b.episodeNumber)
        }

        private fun compareEpisode(a: String, b: String): Int {
            val n1 = a.toDoubleOrNull() ?: 0.0
            val n2 = b.toDoubleOrNull() ?: 0.0
            return n1.compareTo(n2)
        }
    }
}