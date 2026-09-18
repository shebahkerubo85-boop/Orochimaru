package com.lagradost.cloudstream3.ui.download

import android.text.format.Formatter.formatShortFileSize
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemDownloadActiveBinding
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull

class SaninActiveDownloadAdapter(
    private val onPause: (Int) -> Unit,
    private val onResume: (Int) -> Unit,
    private val onCancel: (Int) -> Unit
) : ListAdapter<DownloadQueueWrapper, SaninActiveDownloadAdapter.VH>(Diff()) {

    class VH(val b: ItemDownloadActiveBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadActiveBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.b

        // poster - try to load from DownloadHeaderCached poster
        // For now use placeholder; real poster loaded via DownloadObjects if available
        // Title/episode
        val name = item.downloadItem?.episode?.name ?: item.resumePackage?.item?.ep?.name ?: "Episode"
        val episode = item.downloadItem?.episode?.episode ?: item.resumePackage?.item?.ep?.episode
        val season = item.downloadItem?.episode?.season ?: item.resumePackage?.item?.ep?.season
        b.downloadTitle.text = item.downloadItem?.resultName ?: item.resumePackage?.item?.ep?.mainName ?: name
        b.downloadEpisode.text = ctx.getNameFull(name, episode, season)
        // quality + size
        val info = VideoDownloadManager.getDownloadFileInfo(ctx, item.id)
        val total = info?.totalBytes ?: 0L
        val downloaded = info?.fileLength ?: 0L
        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
        b.downloadPercent.text = "$percent%"
        b.downloadProgress.progress = percent
        b.downloadProgress.isVisible = total > 0
        val quality = item.downloadItem?.episode?.name?.let { "" } ?: "" // placeholder, VideoDownloadManager holds quality via links
        b.downloadQualitySize.text = "${if (quality.isNotEmpty()) "$quality • " else ""}${formatShortFileSize(ctx, total)}".trim()
        val speed = VideoDownloadManager.downloadStatus[item.id]?.let { null } // speed not directly exposed; show downloaded/total
        b.downloadStats.text = "${formatShortFileSize(ctx, downloaded)} / ${formatShortFileSize(ctx, total)}" + if (speed != null) " • $speed" else ""

        val status = VideoDownloadManager.downloadStatus[item.id]
        val isDownloading = status == VideoDownloadManager.DownloadType.IsDownloading
        val isPaused = status == VideoDownloadManager.DownloadType.IsPaused
        b.btnPauseResume.text = if (isPaused) "Resume" else "Pause"
        b.btnPauseResume.setIconResource(if (isPaused) R.drawable.netflix_play else R.drawable.ic_round_pause_24)
        b.btnPauseResume.setOnClickListener {
            if (isPaused) onResume(item.id) else onPause(item.id)
        }
        b.btnCancel.setOnClickListener { onCancel(item.id) }

        // focus
        b.root.isFocusable = true
        b.btnPauseResume.isFocusable = true
        b.btnCancel.isFocusable = true
    }

    class Diff : DiffUtil.ItemCallback<DownloadQueueWrapper>() {
        override fun areItemsTheSame(a: DownloadQueueWrapper, b: DownloadQueueWrapper) = a.id == b.id
        override fun areContentsTheSame(a: DownloadQueueWrapper, b: DownloadQueueWrapper) = a == b
    }
}
