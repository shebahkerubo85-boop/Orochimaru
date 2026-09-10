package ani.sanin.cloudstream

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.others.svg.SvgImageLoader
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.databinding.ItemRepoCardBinding
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.customAlertDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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

class CloudStreamAvailableFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val cacheJson = Json { ignoreUnknownKeys = true }
    private fun cacheFile() = File(requireContext().cacheDir, "cs_repo_cache.json")

    @Serializable
    private data class CachedRepo(
        val name: String, val url: String, val count: Int, val iconUrl: String? = null,
        val contentTypes: List<String> = emptyList(), val languages: List<String> = emptyList()
    )

    private fun saveCache(list: List<RepoUi>) {
        runCatching {
            cacheFile().writeText(cacheJson.encodeToString(list.map {
                CachedRepo(it.name, it.url, it.count, it.iconUrl, it.contentTypes, it.languages)
            }))
        }
    }

    private fun loadCache(): List<RepoUi>? {
        return runCatching {
            val file = cacheFile()
            if (!file.exists()) return null
            cacheJson.decodeFromString<List<CachedRepo>>(file.readText()).map {
                RepoUi(it.name, it.url, it.count, it.iconUrl, it.contentTypes, it.languages)
            }
        }.getOrNull()
    }

    private val adapter = RepoAdapter(
        onOpen = { repo -> openRepo(repo) },
        onLongClick = { repo -> showRepoShortcuts(repo) }
    )
    private var repos: List<RepoUi> = emptyList()
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadRepos()
    }

    private fun loadRepos() {
        val urls = CsRepos.repos().toList()
        val urlSet = urls.toSet()

        loadCache()?.let { cached ->
            val valid = cached.filter { it.url in urlSet }
            if (valid.isNotEmpty()) {
                repos = valid
                adapter.submitList(valid.filter { it.matches(query) })
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = urls.map { url ->
                async {
                    val manifest = runCatching { CsRepos.fetchManifest(url) }.getOrNull()
                    val plugins = if (manifest != null) CsRepos.getRepoPlugins(url) else emptyList()
                    val iconUrl = manifest?.iconUrl?.let {
                        if (it.startsWith("http")) it else CsRepos.sourceUrl(url, it)
                    }
                    val contentTypes = plugins.map { it.typeLabel }.distinct().sorted()
                    val languages = plugins.map { it.lang.uppercase(Locale.ROOT) }.distinct().sorted()
                    RepoUi(
                        name = manifest?.name ?: url.clean(),
                        url = url,
                        count = plugins.size,
                        iconUrl = iconUrl,
                        contentTypes = contentTypes,
                        languages = languages
                    )
                }
            }.awaitAll()
            repos = fresh
            adapter.submitList(fresh.filter { it.matches(query) })
            saveCache(fresh)
        }
    }

    private fun openRepo(repo: RepoUi) {
        startActivity(
            Intent(requireContext(), CloudStreamRepoDetailActivity::class.java)
                .putExtra(CloudStreamRepoDetailActivity.ARG_REPO_URL, repo.url)
        )
    }

    private fun showRepoShortcuts(repo: RepoUi) {
        requireContext().customAlertDialog().apply {
            setTitle(repo.name)
            setMessage("Choose an action for this repository")
            setPosButton("Copy URL") {
                copyToClipboard(repo.url, true)
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
            }
            setNeutralButton("Delete") {
                CsRepos.removeRepo(repo.url)
                loadRepos()
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(repos.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() {
        loadRepos()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.json", "")
        .removeSuffix("/")

    class RepoAdapter(
        private val onOpen: (RepoUi) -> Unit,
        private val onLongClick: (RepoUi) -> Unit
    ) : ListAdapter<RepoUi, RepoAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemRepoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val ctx = holder.itemView.context
            val isDark = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            // Repo name
            holder.binding.repoName.text = item.name

            // Plugin count badge
            holder.binding.repoCount.text = "${item.count} plugins"

            // Content types chips
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

            // Set card gradient from logo via Palette, fallback to theme primary
            val defaultTop = if (isDark) Color.BLACK else Color.WHITE
            val defaultBot = if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#666666")
            applyGradient(holder.binding.repoCardRoot, defaultTop, defaultBot)

            // Load logo via Palette
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
                            // Also set circular logo
                            holder.binding.repoLogo.setImageBitmap(resource)
                        }
                        override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                    })
            } else {
                holder.binding.repoLogo.setImageResource(R.drawable.ic_extension)
            }

            // Browse button — the only focusable element
            with(holder.binding.repoBrowseButton) {
                text = "Browse"
                contentDescription = "Browse ${item.name}"
                setOnClickListener { onOpen(item) }
                isFocusable = true
                isFocusableInTouchMode = true
            }

            // Card itself: not focusable, only long-press
            holder.binding.repoCardRoot.isFocusable = false
            holder.binding.repoCardRoot.setOnLongClickListener { onLongClick(item); true }

            // Last item: DPAD_DOWN goes to search
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
}
