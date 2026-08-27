package ani.sanin.cloudstream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.databinding.FragmentExtensionsBinding
import ani.sanin.databinding.ItemCsSourceBinding
import ani.sanin.settings.SearchQueryHandler
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import java.util.Locale

class CloudStreamInstalledFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val adapter = InstalledAdapter(
        onDelete = { source -> confirmDelete(source) },
        onSettings = { source -> openSettings(source) }
    )
    private var installed: List<CsInstalledSource> = emptyList()
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
        load()
    }

    private fun load() {
        installed = CsRepos.installed(requireContext())
        adapter.submitList(installed.filter { it.matches(query) })
    }

    private fun openSettings(source: CsInstalledSource) {
        val openSettings = CsRuntime.openSettingsFor(requireContext(), source) ?: run {
            Toast.makeText(requireContext(), "No settings available for ${source.name}", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { openSettings(requireContext()) }
            .onFailure { Toast.makeText(requireContext(), "Failed to open settings: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(source: CsInstalledSource) {
        requireContext().customAlertDialog().apply {
            setTitle("Remove extension")
            setMessage("Remove ${source.name} from installed extensions?")
            setPosButton("Remove") {
                CsRepos.uninstall(requireContext(), source)
                Toast.makeText(requireContext(), "${source.name} removed", Toast.LENGTH_SHORT).show()
                load()
            }
            setNegButton("Cancel")
            show()
        }
    }

    override fun updateContentBasedOnQuery(query: String?) {
        this.query = query.orEmpty()
        adapter.submitList(installed.filter { it.matches(this.query) })
    }

    override fun notifyDataChanged() {
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun CsInstalledSource.matches(q: String): Boolean {
        if (q.isBlank()) return true
        return name.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT)) ||
            type.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }

    class InstalledAdapter(
        private val onDelete: (CsInstalledSource) -> Unit,
        private val onSettings: (CsInstalledSource) -> Unit
    ) : ListAdapter<CsInstalledSource, InstalledAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemCsSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.sourceNameTextView.text = item.name
            holder.binding.sourceMetaTextView.text = "v${item.version} • ${item.type} • ${item.lang}"

            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                SvgImageLoader.load(
                    holder.binding.sourceIconImageView,
                    item.iconUrl,
                    ani.sanin.R.drawable.ic_extension,
                    ani.sanin.R.drawable.ic_extension
                )
            }

            holder.binding.sourceInstallImageView.setImageResource(ani.sanin.R.drawable.ic_delete)
            holder.binding.sourceInstallImageView.contentDescription = "Delete"
            holder.binding.sourceInstallImageView.setOnClickListener { onDelete(item) }

            holder.binding.sourceSettingsImageView.isVisible = true
            holder.binding.sourceSettingsImageView.contentDescription = "Settings"
            holder.binding.sourceSettingsImageView.setOnClickListener { onSettings(item) }
            FocusEffectUtil.applyFocusListener(holder.binding.sourceSettingsImageView)

            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.sourceInstallImageView)
        }

        class VH(val binding: ItemCsSourceBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<CsInstalledSource>() {
                override fun areItemsTheSame(
                    oldItem: CsInstalledSource,
                    newItem: CsInstalledSource
                ) = oldItem.id == newItem.id && oldItem.repoUrl == newItem.repoUrl

                override fun areContentsTheSame(
                    oldItem: CsInstalledSource,
                    newItem: CsInstalledSource
                ) = oldItem == newItem
            }
        }
    }
}
