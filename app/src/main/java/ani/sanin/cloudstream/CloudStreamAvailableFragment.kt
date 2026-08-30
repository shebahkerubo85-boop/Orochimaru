package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import ani.sanin.databinding.ItemRepoBinding
import ani.sanin.databinding.ItemAvailableRepoBinding
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch
import java.util.Locale

data class RepoUi(val name: String, val url: String, val count: Int, val iconUrl: String? = null) {
    fun matches(q: String): Boolean {
        if (q.isBlank()) return true
        return name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
            url.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }
}

class CloudStreamAvailableFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

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
        viewLifecycleOwner.lifecycleScope.launch {
            repos = urls.map { url ->
                val manifest = runCatching { CsRepos.fetchManifest(url) }.getOrNull()
                val plugins = if (manifest != null) CsRepos.getRepoPlugins(url) else emptyList()
                val iconUrl = manifest?.iconUrl?.let {
                    if (it.startsWith("http")) it else CsRepos.sourceUrl(url, it)
                }
                RepoUi(
                    name = manifest?.name ?: url.clean(),
                    url = url,
                    count = plugins.size,
                    iconUrl = iconUrl
                )
            }
            adapter.submitList(repos.filter { it.matches(query) })
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
            val binding = ItemAvailableRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                SvgImageLoader.load(
                    holder.binding.repoIconImageView,
                    item.iconUrl,
                    ani.sanin.R.drawable.ic_extension,
                    ani.sanin.R.drawable.ic_extension
                )
            }
            holder.binding.repoNameTextView.text = item.name
            holder.itemView.isFocusable = true
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item); true }
            FocusEffectUtil.applyFocusListener(holder.itemView)
        }

        class VH(val binding: ItemAvailableRepoBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<RepoUi>() {
                override fun areItemsTheSame(oldItem: RepoUi, newItem: RepoUi) =
                    oldItem.url == newItem.url

                override fun areContentsTheSame(oldItem: RepoUi, newItem: RepoUi) = oldItem == newItem
            }
        }
    }
}
