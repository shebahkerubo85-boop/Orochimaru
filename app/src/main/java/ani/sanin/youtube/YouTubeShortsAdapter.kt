package ani.sanin.youtube

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.ItemYoutubeShortBinding
import ani.sanin.util.FocusEffectUtil
import com.bumptech.glide.Glide
import ani.sanin.R

class YouTubeShortsAdapter(
    private val onClick: (YouTubeShort) -> Unit
) : ListAdapter<YouTubeShort, YouTubeShortsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemYoutubeShortBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.binding.shortTitle.text = item.title

        val mins = item.duration / 60
        val secs = item.duration % 60
        holder.binding.shortDuration.text = if (mins > 0) "$mins:${"%02d".format(secs)}" else "0:${"%02d".format(secs)}"

        Glide.with(ctx)
            .load(item.thumbnailUrl)
            .placeholder(R.drawable.ic_extension)
            .error(R.drawable.ic_extension)
            .centerCrop()
            .into(holder.binding.shortThumbnail)

        holder.binding.shortCard.setOnClickListener { onClick(item) }
        holder.binding.shortCard.layoutParams = (holder.binding.shortCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it
        } ?: holder.binding.shortCard.layoutParams
        FocusEffectUtil.applyFocusListener(holder.binding.shortCard)
    }

    class VH(val binding: ItemYoutubeShortBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<YouTubeShort>() {
            override fun areItemsTheSame(oldItem: YouTubeShort, newItem: YouTubeShort) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: YouTubeShort, newItem: YouTubeShort) = oldItem == newItem
        }
    }
}
