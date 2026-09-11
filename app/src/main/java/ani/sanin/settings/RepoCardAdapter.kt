package ani.sanin.settings

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemRepoCardBinding
import ani.sanin.getThemeColor
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.Locale

data class RepoUi(
    val name: String,
    val url: String,
    val count: Int,
    val iconUrl: String? = null,
    val contentTypes: List<String> = emptyList(),
    val languages: List<String> = emptyList()
) {
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        return name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
            url.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }
}

/** Extract GitHub owner avatar from a raw.githubusercontent.com repo URL. */
fun githubOwnerAvatar(repoUrl: String): String? {
    val raw = "raw.githubusercontent.com/"
    val idx = repoUrl.indexOf(raw)
    if (idx < 0) return null
    val rest = repoUrl.substring(idx + raw.length)
    val owner = rest.substringBefore('/')
    if (owner.isBlank() || owner == "index.json") return null
    return "https://github.com/$owner.png"
}

/** Shared Nuvio-style repo card adapter (cloudstream + aniyomi + installers). */
class RepoCardAdapter(
    private val onOpen: (RepoUi) -> Unit,
    private val onLongClick: (RepoUi) -> Unit,
    private val countLabel: String = "plugins"
) : ListAdapter<RepoUi, RepoCardAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRepoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val isDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        holder.binding.repoName.text = item.name

        // Count badge
        holder.binding.repoCount.text = "${item.count} $countLabel"

        // Content type chips
        holder.binding.repoContentTypes.removeAllViews()
        item.contentTypes.forEach { type ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = type
                isClickable = false
                isFocusable = false
                textSize = 11f
                setTextColor(Color.WHITE)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#40FFFFFF"))
                chipCornerRadius = 10f * ctx.resources.displayMetrics.density
                chipMinHeight = 24f * ctx.resources.displayMetrics.density
                setPadding(
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0,
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0
                )
            }
            holder.binding.repoContentTypes.addView(chip)
        }

        // Language chips
        holder.binding.repoLanguages.removeAllViews()
        item.languages.forEach { lang ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = lang
                isClickable = false
                isFocusable = false
                textSize = 11f
                setTextColor(Color.WHITE)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#30FFFFFF"))
                chipCornerRadius = 10f * ctx.resources.displayMetrics.density
                chipMinHeight = 24f * ctx.resources.displayMetrics.density
                setPadding(
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0,
                    (8 * ctx.resources.displayMetrics.density).toInt(),
                    0
                )
            }
            holder.binding.repoLanguages.addView(chip)
        }

        // Fallback gradient: theme primary
        val primaryColor = ctx.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val defaultTop = if (isDark) Color.BLACK else Color.WHITE
        val defaultBot = blendWithSurface(primaryColor, if (isDark) 0.55f else 0.35f)
        applyGradient(holder.binding.repoCardRoot, defaultTop, defaultBot)

        // Logo + Palette gradient (GitHub avatar for aniyomi/installers)
        val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
        if (!skipIcons && !item.iconUrl.isNullOrBlank()) {
            Glide.with(ctx)
                .asBitmap()
                .load(item.iconUrl)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(resource: android.graphics.Bitmap, transition: Transition<in android.graphics.Bitmap>?) {
                        Palette.from(resource).generate { palette ->
                            val vibrant = palette?.lightVibrantSwatch?.rgb
                                ?: palette?.vibrantSwatch?.rgb
                                ?: palette?.dominantSwatch?.rgb
                            if (vibrant != null) {
                                val topColor = if (isDark) Color.BLACK else Color.WHITE
                                val botColor = blendWithSurface(vibrant, if (isDark) 0.6f else 0.4f)
                                applyGradient(holder.binding.repoCardRoot, topColor, botColor)
                            }
                        }
                        holder.binding.repoLogo.setImageBitmap(resource)
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        } else {
            holder.binding.repoLogo.setImageResource(R.drawable.ic_extension)
        }

        // Browse button — only focusable element
        with(holder.binding.repoBrowseButton) {
            text = "Browse"
            contentDescription = "Browse ${item.name}"
            setOnClickListener { onOpen(item) }
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Card: not focusable, only long-press
        holder.binding.repoCardRoot.isFocusable = false
        holder.binding.repoCardRoot.setOnLongClickListener { onLongClick(item); true }

        if (position == itemCount - 1) {
            holder.binding.repoBrowseButton.nextFocusDownId = ani.sanin.R.id.searchViewText
        }
    }

    private fun applyGradient(view: View, topColor: Int, bottomColor: Int) {
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            cornerRadius = 16f * view.resources.displayMetrics.density
        }
        view.background = gradient
    }

    private fun blendWithSurface(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1 - factor) + 26 * factor).toInt()
        val g = (Color.green(color) * (1 - factor) + 26 * factor).toInt()
        val b = (Color.blue(color) * (1 - factor) + 26 * factor).toInt()
        return Color.rgb(r, g, b)
    }

    class VH(val binding: ItemRepoCardBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<RepoUi>() {
            override fun areItemsTheSame(oldItem: RepoUi, newItem: RepoUi) =
                oldItem.url == newItem.url
            override fun areContentsTheSame(oldItem: RepoUi, newItem: RepoUi) = oldItem == newItem
        }
    }
}
