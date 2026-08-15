package ani.sanin.cloudstream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ActivityCsRepoDetailBinding
import ani.sanin.databinding.ItemCsSourceBinding
import ani.sanin.initActivity
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.util.FocusEffectUtil
import ani.sanin.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CloudStreamRepoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCsRepoDetailBinding
    private val adapter = SourceAdapter(::onInstallClick)
    private var installedIds: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityCsRepoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        val repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: run {
            finish()
            return
        }

        binding.csRepoBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.csRepoBack)
        binding.csRepoTitle.text = repoUrl.clean()

        binding.csRepoRecyclerView.adapter = adapter
        binding.csRepoRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.csRepoProgressBar.visibility = View.VISIBLE
            binding.csRepoRecyclerView.visibility = View.GONE
            val manifest = runCatching { CsRepos.fetchManifest(repoUrl) }
            binding.csRepoProgressBar.visibility = View.GONE
            val result = manifest.getOrNull()
            if (result == null) {
                binding.csRepoEmptyText.visibility = View.VISIBLE
                binding.csRepoEmptyText.text = "Could not load repo:\n${manifest.exceptionOrNull()?.message}"
                return@launch
            }
            binding.csRepoTitle.text = result.name
            binding.csRepoRecyclerView.visibility = View.VISIBLE
            refreshList(result.sources)
        }
    }

    override fun onResume() {
        super.onResume()
        installedIds = CsRepos.installed(this).map { it.id }.toSet()
    }

    private fun refreshList(sources: List<CsSource>) {
        val lang = PrefManager.getVal<String>(PrefName.LangSort)
        val filtered = sources.filter { source ->
            CsTypeFilter.matches(source.type) &&
                (lang == "all" || source.lang.equals(lang, true))
        }
        binding.csRepoEmptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) binding.csRepoEmptyText.text = "No extensions match the current filter"
        adapter.submitList(filtered)
    }

    private fun onInstallClick(source: CsSource) {
        if (source.id in installedIds) {
            Toast.makeText(this, "${source.name} is already installed", Toast.LENGTH_SHORT).show()
            return
        }
        val repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: return
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { CsRepos.install(this@CloudStreamRepoDetailActivity, repoUrl, source) }
            }
            if (result.isSuccess) {
                installedIds = CsRepos.installed(this@CloudStreamRepoDetailActivity).map { it.id }.toSet()
                adapter.notifyDataSetChanged()
                Toast.makeText(this@CloudStreamRepoDetailActivity, "${source.name} installed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this@CloudStreamRepoDetailActivity,
                    "Install failed: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.json", "")
        .removeSuffix("/")

    inner class SourceAdapter(
        private val onInstall: (CsSource) -> Unit
    ) : ListAdapter<CsSource, SourceAdapter.VH>(CloudStreamRepoDetailActivity.DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCsSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.sourceNameTextView.text = item.name
            holder.binding.sourceMetaTextView.text =
                "v${item.version} • ${item.type.ifBlank { "unknown" }} • ${item.lang}"
            val installed = item.id in installedIds
            holder.binding.sourceInstallImageView.setImageResource(
                if (installed) R.drawable.ic_check else R.drawable.ic_download_24
            )
            holder.binding.sourceInstallImageView.contentDescription = if (installed) "Installed" else "Install"
            holder.binding.sourceInstallImageView.setOnClickListener { onInstall(item) }
            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.sourceInstallImageView)
        }

        class VH(val binding: ItemCsSourceBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val ARG_REPO_URL = "repoUrl"

        val DIFF = object : DiffUtil.ItemCallback<CsSource>() {
            override fun areItemsTheSame(oldItem: CsSource, newItem: CsSource) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CsSource, newItem: CsSource) =
                oldItem == newItem
        }
    }
}
