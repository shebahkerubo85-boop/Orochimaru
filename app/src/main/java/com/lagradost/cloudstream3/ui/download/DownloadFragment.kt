package com.lagradost.cloudstream3.ui.download

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.os.Build
import android.text.format.Formatter.formatShortFileSize
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.lagradost.cloudstream3.CommonActivity.showToast
import ani.sanin.R
import ani.sanin.databinding.FragmentDownloadsBinding
import ani.sanin.databinding.StreamInputBinding
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.queue.DownloadQueueViewModel
import com.lagradost.cloudstream3.ui.player.BasicLink
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LinkGenerator
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper.playUri
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIcons
import com.lagradost.cloudstream3.utils.UIHelper.setAppBarNoScrollFlagsOnTV
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import java.net.URI

const val DOWNLOAD_NAVIGATE_TO = "downloadpage"

class DownloadFragment : BaseFragment<FragmentDownloadsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentDownloadsBinding::inflate)
) {

    private val downloadViewModel: DownloadViewModel by activityViewModels()
    private val downloadQueueViewModel: DownloadQueueViewModel by activityViewModels()

    private fun View.setLayoutWidth(weight: Long) {
        val param = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxOf((weight / 1000000000f), 0.1f) // 100mb
        )
        this.layoutParams = param
    }

    override fun onDestroyView() {
        activity?.detachBackPressedCallback("Downloads")
        super.onDestroyView()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentDownloadsBinding) {
        hideKeyboard()
        binding.downloadAppbar.setAppBarNoScrollFlagsOnTV()
        binding.downloadDeleteAppbar.setAppBarNoScrollFlagsOnTV()

        observe(downloadViewModel.headerCards) { cards ->
            when (cards) {
                is Resource.Success -> {
                    (binding.downloadList.adapter as? DownloadAdapter)?.submitList(cards.value)
                    binding.textNoDownloads.isVisible = cards.value.isEmpty()
                    binding.downloadLoading.isVisible = false
                    binding.downloadList.isVisible = true
                }

                is Resource.Loading -> {
                    binding.downloadList.isVisible = false
                    binding.downloadLoading.isVisible = true
                }

                is Resource.Failure -> {
                    binding.downloadList.isVisible = true
                    binding.downloadLoading.isVisible = false
                }
            }
        }

        observe(downloadViewModel.availableBytes) {
            updateStorageInfo(
                binding.root.context,
                it,
                R.string.free_storage,
                binding.downloadFreeTxt,
                binding.downloadFree
            )
        }
        observe(downloadViewModel.usedBytes) {
            updateStorageInfo(
                binding.root.context,
                it,
                R.string.used_storage,
                binding.downloadUsedTxt,
                binding.downloadUsed
            )

            val hasBytes = it > 0
            if (hasBytes) {
                binding.downloadLoadingBytes.stopShimmer()
            } else binding.downloadLoadingBytes.startShimmer()

            binding.downloadBytesBar.isVisible = hasBytes
            binding.downloadLoadingBytes.isGone = hasBytes
        }
        observe(downloadViewModel.downloadBytes) {
            updateStorageInfo(
                binding.root.context,
                it,
                R.string.app_storage,
                binding.downloadAppTxt,
                binding.downloadApp
            )
        }
        observe(downloadQueueViewModel.childCards) { cards ->
            val size = cards.currentDownloads.size + cards.queue.size
            val context = binding.root.context
            val baseText = context.getString(R.string.download_queue)
            binding.downloadQueueText.text = if (size > 0) {
                "$baseText (${cards.currentDownloads.size}/$size)"
            } else {
                baseText
            }
        }

        observe(downloadViewModel.selectedBytes) {
            updateDeleteButton(downloadViewModel.selectedItemIds.value?.count() ?: 0, it)
        }

        binding.apply {
            btnDelete.setOnClickListener { view ->
                downloadViewModel.handleMultiDelete(view.context ?: return@setOnClickListener)
            }

            btnCancel.setOnClickListener {
                downloadViewModel.cancelSelection()
            }

            btnToggleAll.setOnClickListener {
                val allSelected = downloadViewModel.isAllHeadersSelected()
                if (allSelected) {
                    downloadViewModel.clearSelectedItems()
                } else {
                    downloadViewModel.selectAllHeaders()
                }
            }
        }

        observeNullable(downloadViewModel.selectedItemIds) { selection ->
            val isMultiDeleteState = selection != null
            val adapter = binding.downloadList.adapter as? DownloadAdapter
            adapter?.setIsMultiDeleteState(isMultiDeleteState)
            binding.downloadDeleteAppbar.isVisible = isMultiDeleteState
            binding.downloadAppbar.isGone = isMultiDeleteState

            if (selection == null) {
                activity?.detachBackPressedCallback("Downloads")
                return@observeNullable
            }
            activity?.attachBackPressedCallback("Downloads") {
                downloadViewModel.cancelSelection()
            }
            updateDeleteButton(selection.count(), downloadViewModel.selectedBytes.value ?: 0L)

            binding.btnDelete.isVisible = selection.isNotEmpty()
            binding.selectItemsText.isVisible = selection.isEmpty()

            val allSelected = downloadViewModel.isAllHeadersSelected()
            if (allSelected) {
                binding.btnToggleAll.setText(R.string.deselect_all)
            } else binding.btnToggleAll.setText(R.string.select_all)
        }

        val adapter = DownloadAdapter(
            { click -> handleItemClick(click) },
            { click ->
                if (click.action == DOWNLOAD_ACTION_DELETE_FILE) {
                    context?.let { ctx ->
                        downloadViewModel.handleSingleDelete(ctx, click.data.id)
                    }
                } else handleDownloadClick(click)
            },
            { itemId, isChecked ->
                if (isChecked) {
                    downloadViewModel.addSelected(itemId)
                } else downloadViewModel.removeSelected(itemId)
            }
        )

        binding.downloadList.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            this.adapter = adapter
            setLinearListLayout(
                isHorizontal = false,
                nextRight = FOCUS_SELF,
                nextDown = R.id.download_queue_button,
            )
        }

        binding.apply {
            openLocalVideoButton.apply {
                isGone = isLayout(TV)
                setOnClickListener { openLocalVideo() }
            }
            downloadStreamButton.apply {
                isGone = isLayout(TV)
                setOnClickListener { showStreamInputDialog(it.context) }
            }

            downloadQueueButton.setOnClickListener {
                activity?.navigate(R.id.action_navigation_global_to_navigation_download_queue)
            }

            downloadStreamButtonTv.isFocusableInTouchMode = isLayout(TV)
            downloadAppbar.isFocusableInTouchMode = isLayout(TV)

            downloadStreamButtonTv.setOnClickListener { showStreamInputDialog(it.context) }
            steamImageviewHolder.isVisible = isLayout(TV)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.downloadList.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                handleScroll(scrollY - oldScrollY)
            }
        }

        context?.let { downloadViewModel.updateHeaderList(it) }

        // --- Sanin redesign: header subtitle + global controls ---
        observe(downloadViewModel.headerCards) { res ->
            val headers = (res as? Resource.Success)?.value ?: emptyList()
            val total = headers.sumOf { it.totalDownloads }
            binding.completedCount?.text = "$total episodes"
            updateHeaderSubtitle()
        }
        observe(downloadQueueViewModel.childCards) { queue ->
            val active = queue.currentDownloads.size
            val total = queue.currentDownloads.size + queue.queue.size
            binding.activeCount?.text = "$active downloading"
            updateHeaderSubtitle()
            // global buttons state
            val hasActive = queue.currentDownloads.isNotEmpty()
            val hasPaused = queue.currentDownloads.any { VideoDownloadManager.downloadStatus[it.id] == VideoDownloadManager.DownloadType.IsPaused }
            binding.btnPauseAll?.isEnabled = hasActive && queue.currentDownloads.any { VideoDownloadManager.downloadStatus[it.id] == VideoDownloadManager.DownloadType.IsDownloading }
            binding.btnResumeAll?.isEnabled = hasPaused
            binding.btnPauseAll?.alpha = if (binding.btnPauseAll?.isEnabled == true) 1f else 0.4f
            binding.btnResumeAll?.alpha = if (binding.btnResumeAll?.isEnabled == true) 1f else 0.4f
            // empty state
            val hasAny = total > 0 || (downloadViewModel.headerCards.value as? Resource.Success)?.value?.isNotEmpty() == true
            binding.emptyState?.isVisible = !hasAny && queue.currentDownloads.isEmpty() && queue.queue.isEmpty()
            binding.activeSection?.isVisible = queue.currentDownloads.isNotEmpty()
            binding.queuedSection?.isVisible = queue.queue.isNotEmpty()
        }
        binding.btnPauseAll?.setOnClickListener {
            val q = downloadQueueViewModel.childCards.value ?: return@setOnClickListener
            q.currentDownloads.forEach { w ->
                if (VideoDownloadManager.downloadStatus[w.id] == VideoDownloadManager.DownloadType.IsDownloading) {
                    VideoDownloadManager.downloadEvent.invoke(Pair(w.id, VideoDownloadManager.DownloadActionType.Pause))
                }
            }
        }
        binding.btnResumeAll?.setOnClickListener {
            val q = downloadQueueViewModel.childCards.value ?: return@setOnClickListener
            q.currentDownloads.forEach { w ->
                if (VideoDownloadManager.downloadStatus[w.id] == VideoDownloadManager.DownloadType.IsPaused) {
                    VideoDownloadManager.downloadEvent.invoke(Pair(w.id, VideoDownloadManager.DownloadActionType.Resume))
                } else if (VideoDownloadManager.downloadStatus[w.id] == null) {
                    // queued but not yet downloading - try resume via queue
                    val pkg = VideoDownloadManager.getDownloadResumePackage(binding.root.context, w.id)
                    if (pkg != null) DownloadQueueManager.addToQueue(pkg.toWrapper())
                }
            }
        }
        binding.storageManageBtn?.setOnClickListener {
            // reuse existing storage-management: open downloads dir picker
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivity(intent)
            } catch (_: Exception) {
                showToast(R.string.storage_settings, Toast.LENGTH_SHORT)
            }
        }
        binding.downloadSearchBtn?.setOnClickListener { showToast(R.string.search, Toast.LENGTH_SHORT) }
        binding.downloadMoreBtn?.setOnClickListener { v ->
            v.popupMenuNoIcons(listOf(Pair(R.string.sort_by, R.string.sort_by), Pair(R.string.clear_completed, R.string.clear_completed))) { }
        }
        binding.btnBrowseAnime?.setOnClickListener {
            findNavController().navigate(R.id.navigation_home)
        }
        // Active / Queued lists setup
        setupActiveQueuedLists()
    }

    private fun updateHeaderSubtitle() {
        val headers = (downloadViewModel.headerCards.value as? Resource.Success)?.value ?: emptyList()
        val total = headers.sumOf { it.totalDownloads }
        val active = downloadQueueViewModel.childCards.value?.currentDownloads?.size ?: 0
        binding?.downloadHeaderSubtitle?.text = "$total downloads • $active active"
    }

    private fun setupActiveQueuedLists() {
        val activeAdapter = SaninActiveDownloadAdapter(
            onPause = { id -> VideoDownloadManager.downloadEvent.invoke(Pair(id, VideoDownloadManager.DownloadActionType.Pause)) },
            onResume = { id -> VideoDownloadManager.downloadEvent.invoke(Pair(id, VideoDownloadManager.DownloadActionType.Resume)) },
            onCancel = { id -> DownloadQueueManager.cancelDownload(id) }
        )
        binding?.downloadActiveList?.apply {
            adapter = activeAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            setHasFixedSize(false)
        }
        val queuedAdapter = SaninQueuedDownloadAdapter(
            onCancel = { id -> com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager.cancelDownload(id) }
        )
        binding?.downloadQueuedList?.apply {
            adapter = queuedAdapter
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        }
        // Completed grid: TV = 4 columns, phone = 2
        binding?.downloadList?.apply {
            val span = if (isLayout(TV or EMULATOR)) 4 else 2
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, span)
        }
        observe(downloadQueueViewModel.childCards) { q ->
            activeAdapter.submitList(q.currentDownloads)
            queuedAdapter.submitList(q.queue)
        }
    }

    private fun handleItemClick(click: DownloadHeaderClickEvent) {
        when (click.action) {
            DOWNLOAD_ACTION_GO_TO_CHILD -> {
                if (click.data.type.isEpisodeBased()) {
                    val folder =
                        getFolderName(DOWNLOAD_EPISODE_CACHE, click.data.id.toString())
                    activity?.navigate(
                        R.id.action_navigation_downloads_to_navigation_download_child,
                        DownloadChildFragment.newInstance(click.data.name, folder)
                    )
                }
            }

            DOWNLOAD_ACTION_LOAD_RESULT -> {
                activity?.loadResult(click.data.url, click.data.apiName, click.data.name)
            }
        }
    }

    private fun updateDeleteButton(count: Int, selectedBytes: Long) {
        val formattedSize = formatShortFileSize(context, selectedBytes)
        binding?.btnDelete?.text =
            getString(R.string.delete_format).format(count, formattedSize)
    }

    private fun updateStorageInfo(
        context: Context,
        bytes: Long,
        @StringRes stringRes: Int,
        textView: TextView?,
        view: View?
    ) {
        textView?.text = getString(R.string.storage_size_format).format(
            getString(stringRes),
            formatShortFileSize(context, bytes)
        )
        view?.setLayoutWidth(bytes)
    }

    private fun openLocalVideo() {
        val intent = Intent()
            .setAction(Intent.ACTION_GET_CONTENT)
            .setType("video/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(FLAG_GRANT_READ_URI_PERMISSION) // Request temporary access
        safe {
            videoResultLauncher.launch(
                Intent.createChooser(
                    intent,
                    getString(R.string.open_local_video)
                )
            )
        }
    }

    private fun showStreamInputDialog(context: Context) {
        val dialog = Dialog(context, R.style.AlertDialogCustom)
        val binding = StreamInputBinding.inflate(dialog.layoutInflater)
        dialog.setContentView(binding.root)
        dialog.show()

        var preventAutoSwitching = false
        binding.hlsSwitch.setOnClickListener { preventAutoSwitching = true }

        binding.streamReferer.doOnTextChanged { text, _, _, _ ->
            if (!preventAutoSwitching) activateSwitchOnHls(text?.toString(), binding)
        }

        (activity?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(
            0
        )?.text?.toString()?.let { copy ->
            val fixedText = copy.trim()
            binding.streamUrl.setText(fixedText)
            activateSwitchOnHls(fixedText, binding)
        }

        binding.applyBtt.setOnClickListener {
            val url = binding.streamUrl.text?.toString()
            if (url.isNullOrEmpty()) {
                showToast(R.string.error_invalid_url, Toast.LENGTH_SHORT)
            } else {
                val referer = binding.streamReferer.text?.toString()
                activity?.navigate(
                    R.id.global_to_navigation_player,
                    GeneratorPlayer.newInstance(
                        LinkGenerator(
                            listOf(BasicLink(url)),
                            extract = true,
                            refererUrl = referer,
                            id = url.hashCode()
                        ), 0
                    )
                )
                dialog.dismissSafe(activity)
            }
        }

        binding.cancelBtt.setOnClickListener {
            dialog.dismissSafe(activity)
        }
    }

    private fun activateSwitchOnHls(text: String?, binding: StreamInputBinding) {
        binding.hlsSwitch.isChecked = safe {
            URI(text).path?.substringAfterLast(".")?.contains("m3u")
        } == true
    }

    private fun handleScroll(dy: Int) {
        if (dy > 0) {
            binding?.downloadStreamButton?.shrink()
        } else if (dy < -5) {
            binding?.downloadStreamButton?.extend()
        }
    }

    // Open local video from files using content provider x safeFile
    private val videoResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val selectedVideoUri = result.data?.data ?: return@registerForActivityResult
        playUri(activity ?: return@registerForActivityResult, selectedVideoUri)
    }
}