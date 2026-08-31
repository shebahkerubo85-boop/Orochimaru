package ani.sanin.media.live

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import java.util.WeakHashMap

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()
    private const val TAG = "LiveHelper"

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) return
        if (liveManagers.contains(player)) return

        val liveManager = LiveManager(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val window = Timeline.Window()
                timeline.getWindow(player.currentMediaItemIndex, window)
                if (window.isDynamic) {
                    liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                }
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)

                // Seek back to the optimal live spot
                if (timeAheadOfLive > 100) {
                    Log.w(TAG, "LIVE discontinuity correction: pos=${newPosition.positionMs} ahead=$timeAheadOfLive, seeking to ${newPosition.positionMs - timeAheadOfLive}")
                    player.seekTo(newPosition.positionMs - timeAheadOfLive)
                }
            }
        }

        synchronized(liveManagers) {
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) return
        if (!liveManagers.contains(player)) return

        synchronized(liveManagers) {
            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }
    }

    fun getLiveManager(player: Player?) = liveManagers[player]?.first
}
