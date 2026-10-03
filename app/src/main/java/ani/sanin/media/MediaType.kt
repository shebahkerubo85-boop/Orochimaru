package ani.sanin.media

interface Type {
    fun asText(): String
}

/**
 * Library a download belongs to.
 *
 * ANIME is everything reached through the anime sources; MOVIE is everything reached
 * through the TMDB sources. Each gets its own storage folder and its own queue
 * section, so the two never mix.
 *
 * TV versus movie is deliberately *not* modelled here: that is an orthogonal axis
 * carried by [MediaKind], since a library holds both films and series.
 */
enum class MediaType : Type {
    ANIME,
    MOVIE;

    override fun asText(): String {
        return when (this) {
            ANIME -> "Anime"
            MOVIE -> "TMDB"
        }
    }

    companion object {
        fun fromText(string: String): MediaType? {
            return when (string) {
                "Anime" -> ANIME
                "Movie", "Movies", "TMDB" -> MOVIE
                else -> {
                    null
                }
            }
        }
    }
}

enum class AddonType : Type {
    DOWNLOAD;

    override fun asText(): String {
        return when (this) {
            DOWNLOAD -> "Download"
        }
    }

    companion object {
        fun fromText(string: String): AddonType? {
            return when (string) {
                "Download" -> DOWNLOAD
                else -> {
                    null
                }
            }
        }
    }
}