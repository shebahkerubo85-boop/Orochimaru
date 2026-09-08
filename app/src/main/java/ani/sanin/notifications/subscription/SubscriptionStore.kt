package ani.sanin.notifications.subscription

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionStore(
    val title: String,
    val content: String,
    val mediaId: Int,
    val type: String = "SUBSCRIPTION",
    val time: Long = System.currentTimeMillis(),
    val image: String? = "",
    val banner: String? = "",
    // Present only for movie/tv (TMDB) subscription notifications so the
    // notification screen can open the right detail screen (TmdbDetailsActivity).
    val tmdbType: String? = null,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
