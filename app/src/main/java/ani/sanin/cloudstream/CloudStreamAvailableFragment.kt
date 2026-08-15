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
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.databinding.ItemRepoBinding
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import kotlinx.coroutines.launch
import java.util.Locale

data class RepoUi(val name: String, val url: String, val count: Int) {
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
        onDelete = { repo -> confirmDelete(repo) }
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
                RepoUi(
                    name = manifest?.name ?: url.clean(),
                    url = url,
                    count = manifest?.sources?.size ?: 0
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

    private fun confirmDelete(repo: RepoUi) {
        requireContext().customAlertDialog().apply {
            setTitle("Remove repository")
            setMessage("Remove ${repo.name}? Installed extensions stay on device.")
            setPosButton("Remove") {
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
        private val onDelete: (RepoUi) -> Unit
    ) : ListAdapter<RepoUi, RepoAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.repoNameTextView.text = item.name
            holder.binding.repoNameTextView.isFocusable = true
            FocusEffectUtil.applyFocusListener(holder.binding.repoNameTextView)
            holder.binding.repoNameTextView.setOnClickListener { onOpen(item) }
            holder.binding.repoDeleteImageView.setOnClickListener { onDelete(item) }
            holder.binding.repoCopyImageView.setOnClickListener {
                copyToClipboard(item.url, true)
                Toast.makeText(holder.itemView.context, "Copied", Toast.LENGTH_SHORT).show()
            }
            FocusEffectUtil.applyFocusListener(holder.binding.repoDeleteImageView)
            FocusEffectUtil.applyFocusListener(holder.binding.repoCopyImageView)
        }

        class VH(val binding: ItemRepoBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<RepoUi>() {
                override fun areItemsTheSame(oldItem: RepoUi, newItem: RepoUi) =
                    oldItem.url == newItem.url

                override fun areContentsTheSame(oldItem: RepoUi, newItem: RepoUi) = oldItem == newItem
            }
        }
    }
}
