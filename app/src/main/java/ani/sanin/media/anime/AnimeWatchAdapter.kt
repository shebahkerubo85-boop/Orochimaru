package ani.sanin.media.anime

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getString
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.FileUrl
import ani.sanin.R
import ani.sanin.currActivity
import ani.sanin.currContext
import ani.sanin.databinding.ItemChipBinding
import ani.sanin.databinding.ItemMediaSourceBinding
import ani.sanin.displayTimer
import ani.sanin.isOnline
import ani.sanin.loadImage
import ani.sanin.media.Media
import ani.sanin.media.MediaDetailsActivity
import ani.sanin.media.MediaNameAdapter

import ani.sanin.media.SourceSearchDialogFragment
import ani.sanin.openSettings
import ani.sanin.others.LanguageMapper
import ani.sanin.others.webview.CookieCatcher
import ani.sanin.parsers.AnimeSources
import ani.sanin.parsers.DynamicAnimeParser
// OfflineAnimeParser removed
import ani.sanin.parsers.NativeAnimeParser
import ani.sanin.parsers.WatchSources
import ani.sanin.px
import ani.sanin.settings.FAQActivity
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.snackString
import ani.sanin.toast
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.Logger
import com.google.android.material.chip.Chip
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_SUBSCRIPTION_CHECK
import eu.kanade.tachiyomi.util.system.WebViewUtil
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch


class AnimeWatchAdapter(
    private val media: Media,
    private val fragment: AnimeWatchFragment,
    private val watchSources: WatchSources
) : RecyclerView.Adapter<AnimeWatchAdapter.ViewHolder>() {
    private var autoSelect = true
    private var chipRowFocused = false
    var subscribe: MediaDetailsActivity.PopImageButton? = null
    private var _binding: ItemMediaSourceBinding? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val bind =
            ItemMediaSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(bind)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        _binding = binding

        binding.faqbutton.setOnClickListener {
            startActivity(
                fragment.requireContext(),
                Intent(fragment.requireContext(), FAQActivity::class.java),
                null
            )
        }
        // PreferDub
        var changing = false
        binding.animeSourceDubbed.setOnCheckedChangeListener { _, isChecked ->
            binding.animeSourceDubbedText.text =
                if (isChecked) currActivity()!!.getString(R.string.dubbed) else currActivity()!!.getString(
                    R.string.subbed
                )
            if (!changing) fragment.onDubClicked(isChecked)
        }

        // Wrong Title
        binding.mediaSourceSearch.setOnClickListener {
            SourceSearchDialogFragment().show(
                fragment.requireActivity().supportFragmentManager,
                null
            )
        }
        val offline = !isOnline(binding.root.context) || PrefManager.getVal(PrefName.OfflineMode)

        binding.mediaSourcePillScroll.isGone = offline
        binding.mediaSourceSettings.isGone = offline
        binding.mediaSourceSearch.isGone = offline
        binding.mediaSourceTitle.isGone = offline
        binding.mediaSourceRefresh.isGone = offline

        // Source Selection — Pills
        val displayNames = watchSources.displayNames.filter { it != "Local" }
        var source =
            media.selected!!.sourceIndex.let { if (it >= watchSources.names.size) 0 else it }
        setLanguageList(media.selected!!.langIndex, source)
        if (watchSources.names.isNotEmpty() && source in 0 until watchSources.names.size) {
            watchSources[source].apply {
                changing = true
                binding.animeSourceDubbed.isChecked = selectDub
                changing = false
                binding.animeSourceDubbedText.text =
                    if (selectDub) currActivity()!!.getString(R.string.dubbed) else currActivity()!!.getString(
                        R.string.subbed
                    )
                binding.mediaSourceTitle.text = showUserText
                showUserTextListener = {
                    MainScope().launch {
                        binding.mediaSourceTitle.text = it
                        binding.mediaSourceSpinner.isVisible = it.startsWith("Searching")
                    }
                }
                binding.mediaSourceSpinner.isVisible = showUserText.startsWith("Searching")
                binding.animeSourceDubbedCont.isVisible = true
            }
        }

        // Website / Set Cookies
        binding.mediaSourceWebview.setOnClickListener {
            if (!WebViewUtil.supportsWebView(fragment.requireContext())) {
                toast(R.string.webview_not_installed)
            }
            if (watchSources.names.isNotEmpty() && source in 0 until watchSources.names.size) {
                val sourceAHH = watchSources[source] as? DynamicAnimeParser
                val sourceHttp =
                    sourceAHH?.extension?.sources?.firstOrNull() as? AnimeHttpSource
                val url = sourceHttp?.baseUrl
                if (url == null) {
                    toast(R.string.anime_watch_no_webpage)
                } else {
                    val headersMap = try {
                        sourceHttp.headers.toMultimap()
                            .mapValues { it.value.getOrNull(0) ?: "" }
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    val intent =
                        Intent(fragment.requireContext(), CookieCatcher::class.java)
                            .putExtra("url", url)
                            .putExtra("headers", headersMap as HashMap<String, String>)
                    startActivity(fragment.requireContext(), intent, null)
                }
            }
        }

        binding.mediaSourceRefresh.setOnClickListener {
            binding.mediaSourceSpinner.isVisible = true
            fragment.onSourceChange(source)
            fragment.loadEpisodes(source, true)
            snackString(R.string.anime_watch_refreshed)
        }

        var style =
            media.selected!!.recyclerStyle ?: PrefManager.getVal(PrefName.AnimeDefaultView)
        if (style == 0) style = 3
        var reversed = media.selected!!.recyclerReversed
        mediaSourceLayoutInit(binding, style)
        binding.mediaSourceLayout.setOnClickListener {
            style = when (style) {
                1 -> 2
                2 -> 3
                else -> 1
            }
            mediaSourceLayoutInit(binding, style)
            snackString(
                if (style == 1) R.string.anime_watch_style_bars
                else if (style == 2) R.string.anime_watch_style_compact
                else R.string.anime_watch_style_strips
            )
            fragment.onIconPressed(style, reversed)
        }
        binding.mediaSourceSort.setOnClickListener {
            reversed = !reversed
            binding.mediaSourceSort.rotation = if (reversed) 180f else 0f
            snackString(
                if (reversed) R.string.anime_watch_down_to_up
                else R.string.anime_watch_up_to_down
            )
            fragment.onIconPressed(style, reversed)
        }
        binding.mediaSourceDownload.setOnClickListener {
            snackString("Download is coming soon")
        }
        binding.mediaSourceFaq.setOnClickListener {
            startActivity(
                fragment.requireContext(),
                Intent(fragment.requireContext(), FAQActivity::class.java),
                null
            )
        }

        binding.mediaSourceTitle.isSelected = true
        val chipGroup = binding.mediaSourceChipGroupPill
        chipGroup.removeAllViews()
        val screenWidth = fragment.screenWidth.px
        displayNames.filter { !it.startsWith("───") }.forEachIndexed { _, name ->
            val chip = LayoutInflater.from(chipGroup.context).inflate(R.layout.item_chip, chipGroup, false) as Chip
            chip.text = name
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            if (name in watchSources.nativeNames) {
                chip.isChipIconVisible = true
                chip.chipIcon = ContextCompat.getDrawable(chip.context, R.drawable.ic_chip_dot)
                chip.chipIconSize = 5f.px.toFloat()
            }
            val actualIndex = watchSources.names.indexOf(name)
            if (actualIndex >= 0) {
                chip.tag = actualIndex
                if (actualIndex == source) chip.isChecked = true
                chip.setOnClickListener {
                        autoSelect = false
                        val idx = chip.tag as Int
                        if (idx == source) return@setOnClickListener
                        Logger.log("Watch: source chip clicked '${watchSources.names.getOrNull(idx)}' (idx $idx)")
                        fragment.onSourceChange(idx).apply {
                            binding.mediaSourceTitle.text = showUserText
                            showUserTextListener = {
                                MainScope().launch {
                                    binding.mediaSourceTitle.text = it
                                    binding.mediaSourceSpinner.isVisible = it.startsWith("Searching")
                                }
                            }
                            binding.mediaSourceSpinner.isVisible = showUserText.startsWith("Searching")
                            changing = true
                            binding.animeSourceDubbed.isChecked = selectDub
                            changing = false
                            binding.animeSourceDubbedCont.isVisible = true
                            source = idx
                            setLanguageList(0, idx)
                        }
                        subscribeButton(false)
                        fragment.loadEpisodes(idx, true)
                        binding.mediaSourcePillScroll.smoothScrollTo(
                            (chip.left - screenWidth / 2) + (chip.width / 2),
                            0
                        )
                    }
                }
            chipGroup.addView(chip)
        }

        if (!chipRowFocused) {
            chipRowFocused = true
            chipGroup.post {
                if (!chipGroup.hasFocus() && chipGroup.childCount > 0) {
                    chipGroup.getChildAt(0).requestFocus()
                }
            }
        }

        // Settings
        binding.mediaSourceSettings.setOnClickListener {
            val parser = watchSources[source]
            when (parser) {
                is DynamicAnimeParser -> fragment.openSettings(parser.extension)
                is NativeAnimeParser -> fragment.openNativeProviderSettings(parser)
                else -> toast("Source not configurable")
            }
        }

        FocusEffectUtil.applyFocusListener(binding.mediaSourcePillScroll, binding.mediaSourcePillScroll)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceSearch, binding.mediaSourceSearch)
        binding.mediaSourceSearch.nextFocusRightId = R.id.mediaSourceSearch
        FocusEffectUtil.applyFocusListener(binding.mediaSourceSettings, binding.mediaSourceSettings, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceRefresh, binding.mediaSourceRefresh, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceWebview, binding.mediaSourceWebview, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceLayout, binding.mediaSourceLayout, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceSort, binding.mediaSourceSort, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceDownload, binding.mediaSourceDownload, true)
        FocusEffectUtil.applyFocusListener(binding.mediaSourceFaq, binding.mediaSourceFaq, true)
        FocusEffectUtil.applyFocusListener(binding.animeSourceDubbed, binding.animeSourceDubbed, true)
        binding.animeSourceDubbed.nextFocusUpId = R.id.mediaSourceSettings
        binding.animeSourceDubbed.nextFocusRightId = R.id.mediaSourceSearch
        binding.mediaSourceSettings.nextFocusDownId = R.id.animeSourceDubbed
        FocusEffectUtil.applyFocusListener(binding.mediaSourceSubscribe, binding.mediaSourceSubscribe, true)
        FocusEffectUtil.applyFocusListener(binding.sourceContinue)
        binding.mediaSourceSubscribe.nextFocusDownId = R.id.ScrollTop
        binding.mediaSourceLayout.nextFocusDownId = R.id.ScrollTop
        binding.mediaSourceSort.nextFocusDownId = R.id.ScrollTop
        binding.mediaSourceDownload.nextFocusDownId = R.id.ScrollTop
        binding.mediaSourceFaq.nextFocusDownId = R.id.ScrollTop
        binding.animeSourceDubbed.nextFocusDownId = R.id.ScrollTop
        binding.faqbutton.nextFocusDownId = R.id.ScrollTop

        // Icons

        // Subscribe
        subscribe = MediaDetailsActivity.PopImageButton(
            fragment.lifecycleScope,
            binding.mediaSourceSubscribe,
            R.drawable.ic_round_notifications_active_24,
            R.drawable.ic_round_notifications_none_24,
            R.color.bg_opp,
            R.color.violet_400,
            fragment.subscribed,
            true
        ) { enabled ->
            fragment.onNotificationPressed(enabled, watchSources.names.getOrElse(source) { "" })
        }

        subscribeButton(false)

        binding.mediaSourceSubscribe.setOnLongClickListener {
            openSettings(fragment.requireContext(), CHANNEL_SUBSCRIPTION_CHECK)
        }

        // Episode Handling
        handleEpisodes()
    }

    fun subscribeButton(enabled: Boolean) {
        subscribe?.enabled(enabled)
    }

    private fun mediaSourceLayoutInit(binding: ItemMediaSourceBinding, style: Int) {
        binding.mediaSourceLayout.setImageResource(
            when (style) {
                1 -> R.drawable.ic_round_view_array_24
                2 -> R.drawable.ic_round_view_comfy_24
                else -> R.drawable.ic_round_view_list_24
            }
        )
    }

    // Chips
    fun updateChips(limit: Int, names: Array<String>, arr: Array<Int>, selected: Int = 0) {
        val binding = _binding
        if (binding != null) {
            val screenWidth = fragment.screenWidth.px
            var select: Chip? = null
            for (position in arr.indices) {
                val last = if (position + 1 == arr.size) names.size else (limit * (position + 1))
                val chip =
                    ItemChipBinding.inflate(
                        LayoutInflater.from(fragment.context),
                        binding.mediaSourceChipGroup,
                        false
                    ).root
                chip.isCheckable = true
                chip.isFocusable = true
                fun selected() {
                    chip.isChecked = true
                    binding.mediaWatchChipScroll.smoothScrollTo(
                        (chip.left - screenWidth / 2) + (chip.width / 2),
                        0
                    )
                }

                val chipText = "${names[limit * (position)]} - ${names[last - 1]}"
                chip.text = chipText
                chip.setTextColor(
                    ContextCompat.getColorStateList(
                        fragment.requireContext(),
                        R.color.chip_text_color
                    )
                )

                chip.setOnClickListener {
                    selected()
                    fragment.onChipClicked(position, limit * (position), last - 1)
                }
                binding.mediaSourceChipGroup.addView(chip)
                if (selected == position) {
                    selected()
                    select = chip
                }
            }
            if (select != null)
                binding.mediaWatchChipScroll.apply {
                    post {
                        scrollTo(
                            (select.left - screenWidth / 2) + (select.width / 2),
                            0
                        )
                    }
                }
        }
    }

    fun clearChips() {
        _binding?.mediaSourceChipGroup?.removeAllViews()
    }

    fun handleEpisodes() {
        val binding = _binding
        if (binding != null) {
            if (media.anime?.episodes != null) {
                val episodes = media.anime.episodes!!.keys.toTypedArray()

                val anilistEp = (media.userProgress ?: 0).plus(1)
                val appEp = PrefManager.getCustomVal<String?>(
                    "${media.id}_current_ep", ""
                )?.toIntOrNull() ?: 1

                val maxAvailableEp = episodes.maxOfOrNull { key ->
                    MediaNameAdapter.findEpisodeNumber(key)
                        ?: media.anime?.episodes?.get(key)?.number?.let { MediaNameAdapter.findEpisodeNumber(it) }
                        ?: 0f
                } ?: episodes.size.toFloat()
                // If user progress already completed all available episodes, hide continue button
                if (media.userProgress != null && media.userProgress!!.toFloat() >= maxAvailableEp && (media.userProgress ?: 0) >= appEp) {
                    binding.sourceContinue.visibility = View.GONE
                    binding.sourceProgressBar.visibility = View.GONE
                    return
                }
                val targetEpNum = (if (anilistEp > appEp) anilistEp else appEp).toFloat()
                // Find matching episode key in media.anime.episodes (keys can be numbers or labels)
                var matchingKey: String? = episodes.find { key ->
                    val epObj = media.anime.episodes?.get(key)
                    MediaNameAdapter.findEpisodeNumber(key) == targetEpNum ||
                        (epObj?.number != null && MediaNameAdapter.findEpisodeNumber(epObj.number) == targetEpNum)
                }
                if (matchingKey == null) {
                    val targetIdx = targetEpNum.toInt() - 1
                    if (targetIdx in episodes.indices) {
                        matchingKey = episodes[targetIdx]
                    }
                }
                var continueEp = matchingKey ?: ""
                if (episodes.contains(continueEp)) {
                    binding.sourceContinue.visibility = View.VISIBLE
                    handleProgress(
                        binding.itemMediaProgressCont,
                        binding.itemMediaProgress,
                        binding.itemMediaProgressEmpty,
                        media.id,
                        continueEp
                    )
                    if ((binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams).weight > PrefManager.getVal<Float>(
                            PrefName.WatchPercentage
                        )
                    ) {
                        val e = episodes.indexOf(continueEp)
                        if (e != -1 && e + 1 < episodes.size) {
                            continueEp = episodes[e + 1]
                            handleProgress(
                                binding.itemMediaProgressCont,
                                binding.itemMediaProgress,
                                binding.itemMediaProgressEmpty,
                                media.id,
                                continueEp
                            )
                        }
                    }
                    val ep = media.anime.episodes!![continueEp]!!

                    val cleanedTitle = ep.title?.let { MediaNameAdapter.removeEpisodeNumber(it) }

                    binding.itemMediaImage.loadImage(
                        ep.thumb ?: FileUrl[media.banner ?: media.cover], 0
                    )
                    if (ep.filler) binding.itemEpisodeFillerView.visibility = View.VISIBLE

                    binding.mediaSourceContinueText.text =
                        currActivity()!!.getString(
                            R.string.continue_episode, ep.number, if (ep.filler)
                                currActivity()!!.getString(R.string.filler_tag)
                            else
                                "", cleanedTitle
                        )
                    binding.sourceContinue.setOnClickListener {
                        fragment.onEpisodeClick(continueEp)
                    }
                    if (fragment.continueEp) {
                        if (
                            (binding.itemMediaProgress.layoutParams as LinearLayout.LayoutParams)
                                .weight < PrefManager.getVal<Float>(PrefName.WatchPercentage)
                        ) {
                            binding.sourceContinue.performClick()
                            fragment.continueEp = false
                        }
                    }
                } else {
                    binding.sourceContinue.visibility = View.GONE
                }

                binding.sourceProgressBar.visibility = View.GONE

                val sourceFound = media.anime.episodes!!.isNotEmpty()
                val isDownloadedSource =
                    false

                if (isDownloadedSource) {
                    binding.sourceNotFound.text = if (sourceFound) {
                        currActivity()!!.getString(R.string.source_not_found)
                    } else {
                        currActivity()!!.getString(R.string.download_not_found)
                    }
                } else {
                    binding.sourceNotFound.text =
                        currActivity()!!.getString(R.string.source_not_found)
                }

                binding.sourceNotFound.isGone = sourceFound
                binding.faqbutton.isGone = sourceFound
                binding.faqbutton.nextFocusDownId = if (sourceFound) R.id.ScrollTop else R.id.mediaSourceSettings

                if (!sourceFound && PrefManager.getVal(PrefName.SearchSources) && autoSelect) {
                    val nextIndex = media.selected!!.sourceIndex + 1
                    if (nextIndex < watchSources.names.size) {
                        fragment.onSourceChange(nextIndex).apply {
                            binding.mediaSourceTitle.text = showUserText
                            showUserTextListener = {
                                MainScope().launch {
                                    binding.mediaSourceTitle.text = it
                                    binding.mediaSourceSpinner.isVisible = it.startsWith("Searching")
                                }
                            }
                            binding.mediaSourceSpinner.isVisible = showUserText.startsWith("Searching")
                            binding.animeSourceDubbed.isChecked = selectDub
                            binding.animeSourceDubbedCont.isVisible = isDubAvailableSeparately()
                            setLanguageList(0, nextIndex)
                        }
                        subscribeButton(false)
                        fragment.loadEpisodes(nextIndex, false)
                        _binding?.mediaSourceChipGroupPill?.let { cg ->
                            for (i in 0 until cg.childCount) {
                                val c = cg.getChildAt(i) as? Chip ?: continue
                                c.isChecked = c.tag == nextIndex
                            }
                        }
                    }
                }
            } else {
                binding.sourceContinue.visibility = View.GONE
                binding.sourceNotFound.visibility = View.GONE
                binding.faqbutton.visibility = View.GONE
                clearChips()
                binding.sourceProgressBar.visibility = View.VISIBLE
            }
        }
    }

    private fun setLanguageList(lang: Int, source: Int) {
        if (watchSources is AnimeSources) {
            (watchSources[source] as? DynamicAnimeParser)?.let { ext ->
                ext.sourceLanguage = lang
            }
        }
    }

    override fun getItemCount(): Int = 1

    inner class ViewHolder(val binding: ItemMediaSourceBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            displayTimer(media, binding.animeSourceContainer)
        }
    }
}
