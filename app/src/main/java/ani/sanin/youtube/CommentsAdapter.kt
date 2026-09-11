package ani.sanin.youtube

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import com.bumptech.glide.Glide

class CommentsAdapter(
    comments: List<YouTubeComment>
) : ListAdapter<YouTubeComment, CommentsAdapter.VH>(DIFF) {

    init { submitList(comments) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_youtube_comment, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.author.text = item.authorName
        holder.text.text = item.text
        holder.likes.text = if (item.likeCount > 0) "${item.likeCount} likes" else ""
        Glide.with(holder.itemView.context)
            .load(item.authorThumb)
            .circleCrop()
            .into(holder.avatar)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.commentAvatar)
        val author: TextView = view.findViewById(R.id.commentAuthor)
        val text: TextView = view.findViewById(R.id.commentText)
        val likes: TextView = view.findViewById(R.id.commentLikes)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<YouTubeComment>() {
            override fun areItemsTheSame(a: YouTubeComment, b: YouTubeComment) = a.text == b.text
            override fun areContentsTheSame(a: YouTubeComment, b: YouTubeComment) = a == b
        }
    }
}
