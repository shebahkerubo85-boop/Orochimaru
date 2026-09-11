package ani.sanin.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.copyToClipboard
import ani.sanin.databinding.ActivityAnimeRepoDetailBinding
import ani.sanin.databinding.ItemExtensionAllBinding
import ani.sanin.initActivity
import ani.sanin.others.LanguageMapper
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.bumptech.glide.Glide
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Per-repo extension list for aniyomi repos (opened from the repo card Browse pill).
 * Shows only extensions belonging to [ARG_REPO_URL] with install buttons.
 */
class AnimeRepoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnimeRepoDetailBinding
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val adapter = SourceAdapter(::onInstallClick)
    private var repoUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityAnimeRepoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        val repoUrl = intent.getStringExtra(ARG_REPO_URL) ?: run { finish(); return }
        this.repoUrl = repoUrl

        binding.animeRepoBack.setOnClickListener { finish() }
        FocusEffectUtil.applyFocusListener(binding.animeRepoBack)
        binding.animeRepoTitle.text = repoUrl.clean()

        // GitHub avatar as repo icon (aniyomi repos have no manifest icon)
        githubOwnerAvatar(repoUrl)?.let { avatar ->
            Glide.with(this).load(avatar).into(binding.animeRepoIcon)
        }

        binding.animeRepoCopy.setOnClickListener { copyToClipboard(repoUrl, true) }
        binding.animeRepoDelete.setOnClickListener {
            customAlertDialog().apply {
                setTitle("Remove repository")
                setMessage("Remove this repository? Installed extensions stay on device.")
                setPosButton("Remove") {
                    val repos =
                        PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos) - repoUrl
                    PrefManager.setVal(PrefName.AnimeExtensionRepos, repos)
                    lifecycleScope.launch { animeExtensionManager.findAvailableExtensions() } // force refresh
                    finish()
                }
                setNegButton("Cancel")
                show()
            }
        }
        FocusEffectUtil.applyFocusListener(binding.animeRepoCopy)
        FocusEffectUtil.applyFocusListener(binding.animeRepoDelete)

        binding.animeRepoRecyclerView.adapter = adapter
        binding.animeRepoRecyclerView.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            binding.animeRepoProgressBar.visibility = View.VISIBLE
            binding.animeRepoRecyclerView.visibility = View.GONE
            animeExtensionManager.availableExtensionsFlow.collectLatest { available ->
                val filtered = available.filter { it.repository == repoUrl }
                binding.animeRepoProgressBar.visibility = View.GONE
                binding.animeRepoRecyclerView.visibility = View.VISIBLE
                binding.animeRepoEmptyText.isVisible = filtered.isEmpty()
                adapter.submitList(filtered)
            }
        }
    }

    private fun onInstallClick(extension: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available) {
        val context = this
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val installerSteps = InstallerSteps(notificationManager, context)
        animeExtensionManager.installExtension(extension)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { installStep -> installerSteps.onInstallStep(installStep) {} },
                { error -> installerSteps.onError(error) {} },
                { installerSteps.onComplete { } }
            )
    }

    private fun String.clean(): String = removePrefix("https://raw.githubusercontent.com/")
        .replace("index.min.json", "")
        .replace("repo.json", "")
        .removeSuffix("/")

    class SourceAdapter(
        private val onInstall: (eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available) -> Unit
    ) : ListAdapter<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available, SourceAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemExtensionAllBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.binding.extensionNameTextView.text = item.name
            val lang = item.lang?.let {
                LanguageMapper.getLanguageName(it)
            } ?: "all"
            holder.binding.extensionVersionTextView.text =
                "$lang v${item.versionName}${if (item.isNsfw) " (18+)" else ""}"

            val skipIcons = PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)
            if (!skipIcons) {
                Glide.with(holder.itemView.context)
                    .load(item.iconUrl)
                    .placeholder(R.drawable.ic_extension)
                    .error(R.drawable.ic_extension)
                    .into(holder.binding.extensionIconImageView)
            }

            holder.binding.extensionCardView.isFocusable = true
            holder.binding.closeTextView.setImageResource(R.drawable.ic_download_24)
            holder.binding.closeTextView.contentDescription = "Install ${item.name}"
            holder.binding.closeTextView.setOnClickListener { onInstall(item) }
            FocusEffectUtil.applyFocusListener(holder.itemView)
            FocusEffectUtil.applyFocusListener(holder.binding.closeTextView)
        }

        class VH(val binding: ItemExtensionAllBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available>() {
                override fun areItemsTheSame(
                    oldItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available,
                    newItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available
                ) = oldItem.pkgName == newItem.pkgName

                override fun areContentsTheSame(
                    oldItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available,
                    newItem: eu.kanade.tachiyomi.extension.anime.model.AnimeExtension.Available
                ) = oldItem == newItem
            }
        }
    }

    companion object {
        const val ARG_REPO_URL = "repoUrl"
    }
}
