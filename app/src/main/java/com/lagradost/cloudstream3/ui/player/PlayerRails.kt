package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.databinding.ItemEpisodeRailBinding
import ani.sanin.databinding.ItemSubtitleTextBinding
import ani.sanin.util.FocusEffectUtil
import ani.sanin.connections.subtitles.StremioSub
import ani.sanin.connections.subtitles.StremioSubtitles
import ani.sanin.connections.subtitles.WyzieSub
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CS3 player rails — visually identical to the anime exo rails (same drawer
 * layouts and row styles) but backed by CloudStream player data.
 */

private class RailTextRow(
    val label: CharSequence,
    val badge: String? = null,
    val globe: Boolean = false,
    val header: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val isToggle: Boolean = false,
    val toggleChecked: Boolean = false,
    val isStatus: Boolean = false,
    val language: String? = null,
    val onToggleChanged: ((Boolean) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

private class RailTextAdapter(private val rows: MutableList<RailTextRow>) :
    RecyclerView.Adapter<RailTextAdapter.Holder>() {

    inner class Holder(val binding: ItemSubtitleTextBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<RailTextRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemSubtitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val binding = holder.binding
        FocusEffectUtil.applyFocusListener(binding.root)

        val themePrimary = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorPrimary
        )

        if (row.header) {
            binding.root.setCardBackgroundColor(Color.TRANSPARENT)
            binding.root.isClickable = row.onClick != null
            binding.root.isFocusable = row.onClick != null
            binding.root.setOnClickListener { row.onClick?.invoke() }
            binding.subtitleTitle.text = row.label
            binding.subtitleTitle.setTextColor(themePrimary)
            binding.subtitleTitle.textSize = 12f
            binding.subtitleGlobe.isVisible = false
            binding.subtitleBadge.isVisible = false
            binding.subtitleToggle.isVisible = false
            return
        }

        // Toggle row: show Switch, hide everything else
        if (row.isToggle) {
            binding.root.setCardBackgroundColor(Color.TRANSPARENT)
            binding.root.isClickable = true
            binding.root.isFocusable = true
            binding.subtitleTitle.text = row.label
            binding.subtitleTitle.setTextColor(Color.WHITE)
            binding.subtitleTitle.textSize = 14f
            binding.subtitleGlobe.isVisible = false
            binding.subtitleBadge.isVisible = false
            binding.subtitleToggle.isVisible = true
            binding.subtitleToggle.setOnCheckedChangeListener(null)
            binding.subtitleToggle.isChecked = row.toggleChecked
            binding.subtitleToggle.setOnCheckedChangeListener { _, checked ->
                row.onToggleChanged?.invoke(checked)
            }
            return
        }

        // Status rows: greyed out, italic, not clickable
        val isStatusRow = row.isStatus
        binding.subtitleTitle.text = row.label
        binding.subtitleTitle.setTextColor(
            if (row.enabled && !isStatusRow) Color.WHITE else 0xFF808080.toInt()
        )
        binding.subtitleTitle.setTypeface(null, if (isStatusRow) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL)
        binding.subtitleToggle.isVisible = false

        binding.subtitleGlobe.isVisible = row.globe
        if (row.globe) {
            binding.subtitleGlobe.imageTintList =
                ColorStateList.valueOf(if (row.enabled) themePrimary else 0xFF808080.toInt())
        }

        binding.subtitleBadge.isVisible = row.badge != null
        if (row.badge != null) {
            binding.subtitleBadge.text = row.badge
            binding.subtitleBadge.setTextColor(if (row.enabled) themePrimary else 0xFF808080.toInt())
            binding.subtitleBadge.backgroundTintList = ColorStateList.valueOf(
                if (row.enabled) ColorUtils.setAlphaComponent(themePrimary, 40)
                else ColorUtils.setAlphaComponent(0xFF808080.toInt(), 40)
            )
        }

        binding.root.setCardBackgroundColor(
            if (row.selected && !isStatusRow) ColorUtils.setAlphaComponent(themePrimary, 60) else Color.TRANSPARENT
        )

        val clickable = row.onClick != null && row.enabled && !isStatusRow
        binding.root.isClickable = clickable
        binding.root.isFocusable = clickable
        binding.root.setOnClickListener { if (clickable) row.onClick?.invoke() }
    }
}

private class EpisodeRailRowAdapter(
    private val episodes: MutableList<ResultEpisode>,
    private val onEpisodeClick: (Int) -> Unit,
) : RecyclerView.Adapter<EpisodeRailRowAdapter.Holder>() {

    inner class Holder(val binding: ItemEpisodeRailBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<ResultEpisode>) {
        episodes.clear()
        episodes.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemEpisodeRailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun getItemCount(): Int = episodes.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val episode = episodes[position]
        val binding = holder.binding
        FocusEffectUtil.applyFocusListener(binding.root)

        binding.episodeRailComment.isVisible = false

        binding.episodeRailNumber.text =
            (episode.episode ?: episode.totalEpisodeIndex).toString()

        binding.episodeRailTitle.text = buildString {
            if (episode.isFiller == true) append("[FILLER] ")
            append(episode.name ?: episode.headerName.ifBlank { "Episode ${episode.episode}" })
        }

        val desc = episode.description?.takeIf { it.isNotBlank() }
        binding.episodeRailDesc.isVisible = desc != null
        binding.episodeRailDesc.text = desc

        val airDate = episode.airDate
        binding.episodeRailDate.isVisible = airDate != null
        if (airDate != null) {
            binding.episodeRailDate.text = SimpleDateFormat.getDateInstance(
                DateFormat.LONG,
                Locale.getDefault()
            ).format(Date(airDate))
        }

        val rating = episode.score?.toFloat(10)?.takeIf { it > 0.1 }
        binding.episodeRailRating.isVisible = rating != null
        binding.episodeRailRating.text = rating?.let { "★ %.1f".format(it) }

        val poster = episode.poster?.takeIf { it.isNotBlank() }
        if (poster != null) {
            binding.episodeRailThumb.loadImage(poster)
        } else {
            binding.episodeRailThumb.setImageDrawable(null)
        }

        binding.root.setOnClickListener { onEpisodeClick(position) }
    }
}

/**
 * Right-side episode rail (same drawer layout + chips as the anime player).
 */
class EpisodeRailController(
    private val drawer: DrawerLayout,
    private val content: View,
    private val seasonScroll: HorizontalScrollView,
    private val seasonChips: ChipGroup,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
    private val episodesProvider: () -> List<ResultEpisode>,
    private val currentIndexProvider: () -> Int,
    private val onEpisodeSelected: (Int) -> Unit,
) {
    private val episodes = mutableListOf<ResultEpisode>()
    private var currentSeasonKey: String? = null
    /** Full unfiltered list kept for id→globalIndex mapping. */
    private var allEpisodes: List<ResultEpisode> = emptyList()

    private val adapter = EpisodeRailRowAdapter(episodes) { adapterPos ->
        // Map adapter position → global index in the full episode list
        val episode = episodes.getOrNull(adapterPos)
        val global = episode?.let { ep -> allEpisodes.indexOfFirst { it.id == ep.id } } ?: adapterPos
        onEpisodeSelected(global)
    }

    init {
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = adapter
        closeButton.nextFocusDownId = R.id.episodeDrawerList
        recycler.nextFocusUpId = R.id.episodeDrawerClose
        FocusEffectUtil.applyFocusListener(closeButton)
        closeButton.setOnClickListener { close() }
    }

    private fun seasonKey(episode: ResultEpisode): String =
        (episode.seasonIndex ?: episode.season ?: 0).toString()

    fun open() {
        rebuild()
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, content)
        if (drawer.isDrawerOpen(content)) focusFirst() else drawer.openDrawer(content)
    }

    fun close() = drawer.closeDrawer(content)

    fun isOpen(): Boolean = drawer.isDrawerOpen(content)

    fun onDrawerOpened() = focusFirst()

    fun onDrawerClosed() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, content)
    }

    private fun currentSeasonOf(all: List<ResultEpisode>, index: Int): String? =
        all.getOrNull(index)?.let { seasonKey(it) }

    private fun rebuild() {
        val all = episodesProvider()
        allEpisodes = all
        currentSeasonKey = currentSeasonKey ?: currentSeasonOf(all, currentIndexProvider())
        buildSeasonChips(all)
        showSeason(all, currentSeasonKey)
    }

    private fun buildSeasonChips(all: List<ResultEpisode>) {
        seasonChips.removeAllViews()
        val seasons = all.map { seasonKey(it) }.distinct()
        if (seasons.size <= 1) {
            seasonScroll.isVisible = false
            return
        }
        seasonScroll.isVisible = true
        seasons.forEach { season ->
            val chip = LayoutInflater.from(seasonChips.context)
                .inflate(R.layout.item_tmdb_chip, seasonChips, false) as Chip
            chip.text = seasonChips.context.getString(
                R.string.tmdb_watch_season_chip,
                season.toIntOrNull() ?: season
            )
            chip.isCheckable = true
            chip.isClickable = true
            chip.isFocusable = true
            chip.tag = season
            chip.isChecked = season == currentSeasonKey
            chip.setOnClickListener {
                currentSeasonKey = season
                showSeason(all, season)
            }
            FocusEffectUtil.applyFocusListener(chip)
            seasonChips.addView(chip)
        }
    }

    private fun showSeason(all: List<ResultEpisode>, season: String?) {
        val list = if (season == null) all else all.filter { seasonKey(it) == season }
        adapter.submit(list)
        val currentEpisode = all.getOrNull(currentIndexProvider())
        if (currentEpisode != null) {
            val pos = list.indexOfFirst { it.id == currentEpisode.id }
            if (pos >= 0) recycler.post { recycler.scrollToPosition(pos) }
        }
    }

    private fun focusFirst() {
        recycler.post {
            recycler.scrollToPosition(0)
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(0)
                if (holder != null) holder.itemView.requestFocus() else closeButton.requestFocus()
            }
        }
    }
}

/**
 * Left-side subtitle rail (same drawer layout + rows as the anime player).
 */
class SubtitleRailController(
    private val drawer: DrawerLayout,
    private val content: View,
    private val languageButton: ImageButton,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
    private val subtitlesProvider: () -> List<SubtitleData>,
    private val currentSubtitleProvider: () -> SubtitleData?,
    private val onSubtitleSelected: (SubtitleData?) -> Unit,
    private val onToggleChanged: ((Boolean) -> Unit)? = null,
    private val isSubtitlesEnabledProvider: (() -> Boolean)? = null,
    private val onSyncSubtitle: (() -> Unit)? = null,
    private val onSearchOnline: (() -> Unit)? = null,
    private val onAddLocalSubtitle: (() -> Unit)? = null,
    private val isSearchingOnlineProvider: (() -> Boolean)? = null,
) {
    private val rows = mutableListOf<RailTextRow>()
    private val adapter = RailTextAdapter(rows)
    private var languageFilter: String? = null

    init {
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = adapter
        languageButton.nextFocusDownId = R.id.subtitleDrawerList
        closeButton.nextFocusDownId = R.id.subtitleDrawerList
        recycler.nextFocusUpId = R.id.subtitleDrawerClose
        FocusEffectUtil.applyFocusListener(languageButton)
        FocusEffectUtil.applyFocusListener(closeButton)
        languageButton.setOnClickListener { showLanguageDialog() }
        closeButton.setOnClickListener { close() }
    }

    fun open() {
        rebuild()
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, content)
        if (drawer.isDrawerOpen(content)) focusFirst() else drawer.openDrawer(content)
    }

    fun close() = drawer.closeDrawer(content)

    fun isOpen(): Boolean = drawer.isDrawerOpen(content)

    fun onDrawerOpened() = focusFirst()

    fun onDrawerClosed() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, content)
    }

    /** Toggle the search-in-progress state and refresh the rail. */
    fun setSearchingOnline(searching: Boolean) {
        rebuild()
    }

    private fun subtitleLanguage(sub: SubtitleData): String? =
        sub.getIETF_tag()?.let { fromTagToLanguageName(it.substringBefore('-')) }
            ?: fromTagToLanguageName(sub.languageCode)
            ?: sub.languageCode?.replaceFirstChar { it.uppercaseChar() }

    private fun matchesFilter(sub: SubtitleData): Boolean {
        if (languageFilter == null) return true
        return (sub.getIETF_tag() ?: sub.languageCode ?: "").substringBefore('-')
            .equals(languageFilter, true)
    }

    private fun rebuild() {
        val current = currentSubtitleProvider()
        val allSubs = subtitlesProvider()
        val enabled = isSubtitlesEnabledProvider?.invoke() ?: true
        rows.clear()

        // 1. Master subtitle toggle
        rows.add(
            RailTextRow(
                subtitleText(R.string.subtitles),
                isToggle = true,
                toggleChecked = enabled,
                onToggleChanged = { checked -> onToggleChanged?.invoke(checked) },
            )
        )

        // 2. Subtitle sync
        if (onSyncSubtitle != null) {
            rows.add(
                RailTextRow(
                    subtitleText(R.string.subtitles_sync),
                    enabled = enabled,
                    onClick = { if (enabled) onSyncSubtitle?.invoke() },
                )
            )
        }

        // Group subtitles by origin (filtered)
        val embedded = allSubs.filter { it.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO && matchesFilter(it) }
        val online = allSubs.filter { it.origin == SubtitleOrigin.URL && matchesFilter(it) }
        val local = allSubs.filter { it.origin == SubtitleOrigin.DOWNLOADED_FILE && matchesFilter(it) }

        // 3. Embedded tracks
        if (embedded.isNotEmpty()) {
            rows.add(RailTextRow(subtitleText(R.string.subtitles_from_embedded), header = true))
            embedded.forEach { sub ->
                val selected = sub == current
                rows.add(
                    RailTextRow(
                        label = sub.name,
                        badge = subtitleLanguage(sub),
                        selected = selected,
                        enabled = enabled,
                        language = sub.getIETF_tag()?.substringBefore('-') ?: sub.languageCode,
                        onClick = { if (enabled) { onSubtitleSelected(sub); rebuild() } },
                    )
                )
            }
        }

        // 4. Online subtitles
        if (online.isNotEmpty()) {
            rows.add(RailTextRow(subtitleText(R.string.subtitles_from_online), header = true))
            online.forEach { sub ->
                val selected = sub == current
                rows.add(
                    RailTextRow(
                        label = sub.name,
                        badge = subtitleLanguage(sub),
                        globe = true,
                        selected = selected,
                        enabled = enabled,
                        language = sub.getIETF_tag()?.substringBefore('-') ?: sub.languageCode,
                        onClick = { if (enabled) { onSubtitleSelected(sub); rebuild() } },
                    )
                )
            }
        }

        // 5. "+ Search Online Subtitles" action (under Online section)
        if (onSearchOnline != null) {
            val searching = isSearchingOnlineProvider?.invoke() == true
            if (searching) {
                rows.add(
                    RailTextRow(
                        subtitleText(R.string.searching),
                        isStatus = true,
                        enabled = enabled,
                    )
                )
            } else {
                rows.add(
                    RailTextRow(
                        subtitleText(R.string.player_load_subtitles_online),
                        enabled = enabled,
                        onClick = { if (enabled) onSearchOnline?.invoke() },
                    )
                )
            }
        }

        // 6. Local subtitles
        if (local.isNotEmpty()) {
            rows.add(RailTextRow(subtitleText(R.string.player_load_subtitles), header = true))
            local.forEach { sub ->
                val selected = sub == current
                rows.add(
                    RailTextRow(
                        label = sub.name,
                        badge = subtitleLanguage(sub),
                        selected = selected,
                        enabled = enabled,
                        language = sub.getIETF_tag()?.substringBefore('-') ?: sub.languageCode,
                        onClick = { if (enabled) { onSubtitleSelected(sub); rebuild() } },
                    )
                )
            }
        }

        // 7. "+ Add Local Subtitle" action
        if (onAddLocalSubtitle != null) {
            rows.add(
                RailTextRow(
                    subtitleText(R.string.player_load_subtitles),
                    enabled = enabled,
                    onClick = { if (enabled) { close(); onAddLocalSubtitle?.invoke() } },
                )
            )
        }

        // Empty state
        if (embedded.isEmpty() && online.isEmpty() && local.isEmpty()) {
            rows.add(RailTextRow(subtitleText(R.string.no_subtitles), header = true))
        }

        adapter.submit(rows.toList())

        // Language button reflects an active filter
        languageButton.imageTintList = ColorStateList.valueOf(
            if (languageFilter != null) MaterialColors.getColor(
                languageButton, com.google.android.material.R.attr.colorPrimary
            ) else Color.WHITE
        )
    }

    private fun subtitleText(res: Int): String = recycler.context.getString(res)

    private fun showLanguageDialog() {
        val act = languageButton.context as? Activity ?: return
        val langs = subtitlesProvider().mapNotNull { sub ->
            (sub.getIETF_tag() ?: sub.languageCode ?: "").substringBefore('-')
                .takeIf { it.isNotBlank() && it != "und" }
        }.distinct().sorted()
        if (langs.isEmpty()) return
        val names = langs.map { fromTagToLanguageName(it) ?: it }
        val all = act.getString(R.string.all)
        val options = listOf(all) + names
        AlertDialog.Builder(act, R.style.AlertDialogCustom)
            .setTitle(R.string.language)
            .setSingleChoiceItems(options.toTypedArray(), if (languageFilter == null) 0 else (langs.indexOf(languageFilter) + 1).coerceAtLeast(0)) { dialog, which ->
                languageFilter = if (which == 0) null else langs.getOrNull(which - 1)
                rebuild()
                dialog.dismiss()
            }
            .show()
    }

    private fun focusFirst() {
        val first = rows.indexOfFirst { it.onClick != null }
        if (first >= 0) {
            recycler.post {
                recycler.scrollToPosition(first)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(first)
                    if (holder != null) holder.itemView.requestFocus() else closeButton.requestFocus()
                }
            }
        } else {
            closeButton.requestFocus()
        }
    }
}

/**
 * Right-side tracks/audio rail — the same overlay + rows as the anime player.
 */
@OptIn(UnstableApi::class)
class TrackRailController(
    private val drawer: DrawerLayout,
    private val content: View,
    private val closeButton: ImageButton,
    private val recycler: RecyclerView,
    private val tracksProvider: () -> CurrentTracks,
    private val onVideoTrackSelected: (VideoTrack) -> Unit,
    private val onAudioTrackSelected: (AudioTrack) -> Unit,
) {
    private val rows = mutableListOf<RailTextRow>()
    private val adapter = RailTextAdapter(rows)

    init {
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = adapter
        closeButton.nextFocusDownId = R.id.tracksDrawerList
        recycler.nextFocusUpId = R.id.tracksDrawerClose
        FocusEffectUtil.applyFocusListener(closeButton)
        closeButton.setOnClickListener { close() }
    }

    fun open() {
        rebuild()
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, content)
        if (drawer.isDrawerOpen(content)) focusFirst() else drawer.openDrawer(content)
    }

    fun close() = drawer.closeDrawer(content)

    fun isOpen(): Boolean = drawer.isDrawerOpen(content)

    fun onDrawerOpened() = focusFirst()

    fun onDrawerClosed() {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, content)
    }

    private fun mimeCodec(mime: String?): String? = when (mime) {
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        else -> null
    }

    private fun audioLanguage(track: AudioTrack): String =
        fromTagToLanguageName(track.language)
            ?: track.language?.replaceFirstChar { it.uppercaseChar() }
            ?: track.label?.trim()?.takeIf { it.isNotBlank() }
            ?: "Audio"

    private fun rebuild() {
        val tracks = tracksProvider()
        rows.clear()

        val videos = tracks.allVideoTracks.sortedByDescending { it.height ?: 0 }
        if (videos.isNotEmpty()) {
            rows.add(RailTextRow(text(R.string.tracks), header = true))
            videos.forEachIndexed { index, track ->
                val label = track.label?.takeIf { it.isNotBlank() }
                    ?: if (track.width != null && track.height != null &&
                        track.width != Format.NO_VALUE && track.height != Format.NO_VALUE
                    ) "${track.width}x${track.height}" else "Video ${index + 1}"
                rows.add(
                    RailTextRow(
                        label = label,
                        selected = tracks.currentVideoTrack?.id == track.id,
                        onClick = { onVideoTrackSelected(track) }
                    )
                )
            }
        }

        val audios = tracks.allAudioTracks
        if (audios.isNotEmpty()) {
            rows.add(RailTextRow(text(R.string.audio), header = true))
            audios.forEach { track ->
                val channels = when (val count = track.channelCount) {
                    null, 0, -1 -> ""
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${count}ch"
                }
                val codec = mimeCodec(track.sampleMimeType)
                val label = listOfNotNull(audioLanguage(track), channels, codec)
                    .joinToString(" • ")
                rows.add(
                    RailTextRow(
                        label = label,
                        selected = tracks.currentAudioTrack?.id == track.id &&
                            tracks.currentAudioTrack?.formatIndex == track.formatIndex,
                        onClick = { onAudioTrackSelected(track) }
                    )
                )
            }
        }

        if (rows.isEmpty()) {
            rows.add(RailTextRow(text(R.string.no_tracks), header = true))
        }
        adapter.submit(rows.toList())
    }

    private fun text(res: Int): String = recycler.context.getString(res)

    private fun focusFirst() {
        val first = rows.indexOfFirst { it.onClick != null }
        if (first >= 0) {
            recycler.post {
                recycler.scrollToPosition(first)
                recycler.post {
                    val holder = recycler.findViewHolderForAdapterPosition(first)
                    if (holder != null) holder.itemView.requestFocus() else closeButton.requestFocus()
                }
            }
        } else {
            closeButton.requestFocus()
        }
    }
}
