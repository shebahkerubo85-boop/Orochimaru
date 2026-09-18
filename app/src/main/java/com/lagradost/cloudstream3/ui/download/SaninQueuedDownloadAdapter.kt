package com.lagradost.cloudstream3.ui.download

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemDownloadQueuedBinding
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull

class SaninQueuedDownloadAdapter(
    private val onCancel: (Int) -> Unit
) : ListAdapter<DownloadQueueWrapper, SaninQueuedDownloadAdapter.VH>(Diff()) {

    class VH(val b: ItemDownloadQueuedBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadQueuedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val b = holder.b
        val name = item.downloadItem?.episode?.name ?: item.resumePackage?.item?.ep?.name ?: "Episode"
        val episode = item.downloadItem?.episode?.episode ?: item.resumePackage?.item?.ep?.episode
        val season = item.downloadItem?.episode?.season ?: item.resumePackage?.item?.ep?.season
        b.queuedTitle.text = item.downloadItem?.resultName ?: item.resumePackage?.item?.ep?.mainName ?: name
        // show episode • quality • size placeholder
        b.queuedEpisode.text = ctx.getNameFull(name, episode, season)
        b.btnQueuedMore.setOnClickListener { onCancel(item.id) }
        b.root.isFocusable = true
        b.btnQueuedMore.isFocusable = true
    }

    class Diff : DiffUtil.ItemCallback<DownloadQueueWrapper>() {
        override fun areItemsTheSame(a: DownloadQueueWrapper, b: DownloadQueueWrapper) = a.id == b.id
        override fun areContentsTheSame(a: DownloadQueueWrapper, b: DownloadQueueWrapper) = a == b
    }
}
