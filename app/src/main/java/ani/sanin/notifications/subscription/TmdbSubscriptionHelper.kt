package ani.sanin.notifications.subscription

import ani.sanin.connections.simkl.Simkl
import ani.sanin.settings.saving.PrefManager
import ani.sanin.util.Logger

/**
 * TMDB (movie/tv) subscriptions for new-episode notifications.
 *
 * Subscriptions are stored two ways so they survive across devices:
 *  - locally (always), so the bell reflects state even offline / not logged in
 *  - on Simkl as "watching" watchlist entries (when logged in) so a fresh
 *    install or second device picks them up, and the notification worker
 *    merges Simkl's watchlist into the set it checks.
 */
class TmdbSubscriptionHelper {
    companion object {
        private const val SUBSCRIPTIONS = "tmdb_subscriptions"

        @kotlinx.serialization.Serializable
        data class TmdbSubscriptionItem(
            val id: Int,
            val name: String,
            val type: String, // "tv" or "movie"
            val image: String? = null,
            val banner: String? = null,
            // Last episode we have already notified about, so we only notify
            // once per newly aired episode.
            val lastSeason: Int = 0,
            val lastEpisode: Int = 0
        ) : java.io.Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun getSubscriptions(): Map<Int, TmdbSubscriptionItem> =
            (PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? Map<Int, TmdbSubscriptionItem>)
                ?: mapOf<Int, TmdbSubscriptionItem>().also { PrefManager.setCustomVal(SUBSCRIPTIONS, it) }

        @Suppress("UNCHECKED_CAST")
        fun save(media: TmdbSubscriptionItem) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, TmdbSubscriptionItem>
                ?: mutableMapOf()
            data[media.id] = media
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
        }

        @Suppress("UNCHECKED_CAST")
        fun delete(id: Int) {
            val data = PrefManager.getNullableCustomVal(
                SUBSCRIPTIONS,
                null,
                Map::class.java
            ) as? MutableMap<Int, TmdbSubscriptionItem>
                ?: mutableMapOf()
            data.remove(id)
            PrefManager.setCustomVal(SUBSCRIPTIONS, data)
        }

        fun isSubscribed(id: Int): Boolean = getSubscriptions().containsKey(id)

        /**
         * Add an item, keeping its type/name/image and preserving the last
         * notified episode if it was already subscribed locally.
         */
        fun subscribe(
            id: Int,
            name: String,
            type: String,
            image: String?,
            banner: String?
        ) {
            val existing = getSubscriptions()[id]
            save(
                TmdbSubscriptionItem(
                    id = id,
                    name = name,
                    type = type,
                    image = image,
                    banner = banner,
                    lastSeason = existing?.lastSeason ?: 0,
                    lastEpisode = existing?.lastEpisode ?: 0
                )
            )
        }

        /** Add the item to Simkl's watchlist as "watching" (best-effort, fire). */
        suspend fun syncToSimkl(id: Int, type: String) {
            try {
                Simkl.addToWatchlist(type, tmdbId = id)
            } catch (e: Exception) {
                Logger.log("TmdbSubscriptionHelper.syncToSimkl add failed: ${e.message}")
            }
        }

        /** Remove the item from Simkl's watchlist (best-effort, fire). */
        suspend fun unsyncFromSimkl(id: Int, type: String) {
            try {
                Simkl.removeFromList(type, tmdbId = id)
            } catch (e: Exception) {
                Logger.log("TmdbSubscriptionHelper.unsyncFromSimkl remove failed: ${e.message}")
            }
        }

        /**
         * Cross-device import: merge Simkl "watching" shows into the local
         * subscription set (when logged in) so a fresh install / second device
         * keeps notifying for shows that were subscribed elsewhere.
         */
        suspend fun importFromSimkl(): Int {
            if (!Simkl.getSavedToken()) return 0
            val shows = try { Simkl.getShowLibrary() } catch (e: Exception) { emptyList() }
            var added = 0
            val local = getSubscriptions().toMutableMap()
            shows.filter { it.status?.lowercase() in setOf("watching", "current") }
                .forEach { item ->
                    val tmdbId = item.ids?.tmdb ?: return@forEach
                    val title = item.title ?: return@forEach
                    // Preserve existing last-notified episode; only fill gaps.
                    val existing = local[tmdbId]
                    if (existing != null) return@forEach
                    val poster = item.poster
                    local[tmdbId] = TmdbSubscriptionItem(
                        id = tmdbId,
                        name = title,
                        type = "tv",
                        image = poster,
                        banner = poster
                    )
                    added++
                }
            if (added > 0) PrefManager.setCustomVal(SUBSCRIPTIONS, local)
            return added
        }
    }
}
