package ani.sanin.profile.notification

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import ani.sanin.R
import ani.sanin.databinding.ItemNotificationEpisodeBinding
import ani.sanin.getThemeColor
import ani.sanin.loadImage
import ani.sanin.notifications.subscription.SubscriptionStore
import ani.sanin.profile.activity.ActivityItemBuilder
import ani.sanin.setAnimation
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.util.FocusEffectUtil
import ani.sanin.util.customAlertDialog
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact "episode aired" notification: a thin top pill (round poster + message
 * + relative time) and a 72dp episode card (full-bleed thumbnail, dominant
 * colour gradient, episode title + metadata, single focusable Watch button).
 *
 * Used by the Media tab (anime-mode) and the Subscriptions tab (both modes).
 */
class EpisodeNotificationItem(
    private val notification: Notification,
    val type: TabType,
    val parentAdapter: GroupieAdapter,
    val clickCallback: (Int, Int?, NotificationClickType) -> Unit,
) : BindableItem<ItemNotificationEpisodeBinding>() {

    private lateinit var binding: ItemNotificationEpisodeBinding
    private var loadJob: Job? = null

    override fun getLayout(): Int = R.layout.item_notification_episode

    override fun initializeViewBinding(view: View): ItemNotificationEpisodeBinding =
        ItemNotificationEpisodeBinding.bind(view)

    override fun bind(viewBinding: ItemNotificationEpisodeBinding, position: Int) {
        binding = viewBinding
        loadJob?.cancel()
        setAnimation(binding.root.context, binding.root)

        bindPill()
        bindCard()

        binding.episodeWatch.setOnClickListener { open() }
        FocusEffectUtil.applyFocusListener(binding.episodeWatch)
        binding.episodeCard.setOnLongClickListener { dialog(); true }
    }

    // ── Top pill ──────────────────────────────────────────────────

    private fun bindPill() {
        // Round poster (anime poster, not the episode thumbnail).
        val poster = notification.image ?: notification.media?.coverImage?.large
        if (!poster.isNullOrBlank()) {
            binding.episodePillPoster.loadImage(poster)
        }

        binding.episodePillText.text = pillText()

        val time = ActivityItemBuilder.getDateTime(notification.createdAt)
        binding.episodePillTime.text = time
        binding.episodePillTime.isVisible = time.isNotBlank()
    }

    private fun pillText(): CharSequence {
        val contextApp = binding.root.context
        val primary = contextApp.getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val episode = notification.episode
        val title = notification.media?.title?.userPreferred
            ?: notification.context?.substringBefore(":").orEmpty().trim()

        val episodeLabel = if (episode != null) "Episode $episode" else "New episode"
        val builder = SpannableStringBuilder()
        builder.append(contextApp.getString(R.string.episode_airing_prefix, episodeLabel))
        val titleStart = builder.length
        builder.append(if (title.isBlank()) contextApp.getString(R.string.episode_airing_unknown_title) else title)
        builder.setSpan(
            ForegroundColorSpan(primary),
            titleStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            titleStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.append(contextApp.getString(R.string.episode_airing_suffix))
        return builder
    }

    // ── Episode card ──────────────────────────────────────────────

    private fun bindCard() {
        // Full-bleed background: banner immediately, thumbnail swaps in once known.
        val banner = notification.banner ?: notification.image
            ?: notification.media?.bannerImage ?: notification.media?.coverImage?.large
        if (!banner.isNullOrBlank()) binding.episodeThumb.loadImage(banner)

        val dark = isDarkMode()
        // Top rim: dark gradient in dark mode, white gradient in light mode.
        binding.episodeRim.setBackgroundResource(
            if (dark) R.drawable.bg_repo_card_rim else R.drawable.bg_episode_card_rim_light
        )
        val onRight = if (dark) Color.WHITE else Color.BLACK
        binding.episodeTitle.setTextColor(onRight)
        bindMetaColors(onRight)

        val knownTitle = notification.episodeTitle
        val knownDuration = notification.durationMinutes
        val knownAirDate = notification.airDate
        binding.episodeTitle.text = knownTitle.orEmpty()
        binding.episodeTitle.isVisible = !knownTitle.isNullOrBlank()
        applyMeta(
            knownDuration,
            knownAirDate,
            notification.airTimeMillis ?: notification.createdAt.toLong() * 1000L
        )

        // Async enrichment: resolver (anizip -> tmdb -> kitsu) fills missing
        // title/duration/date/thumbnail, then colour gradient.
        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        loadJob = owner.lifecycleScope.launch {
            val isAnime = notification.tmdbType.isNullOrBlank()
            var title = knownTitle
            var duration = knownDuration
            var airDate = knownAirDate
            var thumb = notification.thumbnail
            var anizipBackdrop: String? = null
            var bannerForGradient = banner

            if (isAnime && notification.mediaId != null && notification.episode != null) {
                val extra = EpisodeNotificationResolver.resolve(
                    notification.mediaId,
                    notification.episode,
                    fallbackTitle = knownTitle
                )
                if (!extra.isEmpty) {
                    title = extra.title ?: title
                    duration = extra.durationMinutes ?: duration
                    airDate = extra.airDate ?: airDate
                    thumb = extra.thumbnailUrl ?: thumb
                    anizipBackdrop = extra.backdropUrl ?: anizipBackdrop
                }
            }

            run {
                if (!title.isNullOrBlank() && knownTitle.isNullOrBlank()) {
                    binding.episodeTitle.text = title
                    binding.episodeTitle.isVisible = true
                }
                applyMeta(duration, airDate, notification.airTimeMillis ?: notification.createdAt.toLong() * 1000L)

                val displayUrl = thumb?.takeIf { it.isNotBlank() }
                    ?: anizipBackdrop?.takeIf { it.isNotBlank() }
                if (displayUrl != null) {
                    binding.episodeThumb.loadImage(displayUrl)
                    bannerForGradient = displayUrl
                }
                val dominant = EpisodeCardGradient.dominantColor(displayUrl ?: bannerForGradient)
                binding.episodeGradient.background = EpisodeCardGradient.build(binding.root.context, dominant)
            }
        }
    }

    private fun bindMetaColors(onRight: Int) {
        binding.episodeMetaText.setTextColor(onRight)
        binding.episodeMetaText.setCompoundDrawableTintList(
            android.content.res.ColorStateList.valueOf(onRight)
        )
    }

    /** duration (minutes), ISO date, and local release time — one ellipsizing line. */
    private fun applyMeta(durationMinutes: Int?, airDate: String?, airTimeMillis: Long) {
        val date = airDate ?: if (airTimeMillis > 0)
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(airTimeMillis)) else null
        val time = if (airTimeMillis > 0)
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(airTimeMillis)) else null

        val parts = mutableListOf<String>()
        if (durationMinutes != null) parts += "${durationMinutes}m"
        if (date != null) parts += date
        if (time != null) parts += time
        binding.episodeMetaText.text = parts.joinToString("  ·  ")
        binding.episodeMetaText.isVisible = parts.isNotEmpty()
    }

    private fun isDarkMode(): Boolean =
        (binding.root.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── Actions ───────────────────────────────────────────────────

    private fun open() {
        val isTmdb = type == TabType.SUBSCRIPTION && !notification.tmdbType.isNullOrBlank()
        if (isTmdb) {
            clickCallback(
                notification.mediaId ?: 0,
                if (notification.tmdbType == "tv") 1 else 0,
                NotificationClickType.TMDB_MEDIA
            )
        } else {
            clickCallback(notification.mediaId ?: 0, null, NotificationClickType.MEDIA)
        }
    }

    private fun dialog() {
        if (type != TabType.SUBSCRIPTION) return
        binding.root.context.customAlertDialog().apply {
            setTitle(R.string.delete)
            setMessage(notification.context ?: "")
            setPosButton(R.string.yes) {
                val list = PrefManager.getNullableVal<List<SubscriptionStore>>(
                    PrefName.SubscriptionNotificationStore, null
                ) ?: listOf()
                val newList = list.filter { (it.time / 1000L).toInt() != notification.createdAt }
                PrefManager.setVal(PrefName.SubscriptionNotificationStore, newList)
                parentAdapter.remove(this@EpisodeNotificationItem)
            }
            setNegButton(R.string.no)
            show()
        }
    }
}
