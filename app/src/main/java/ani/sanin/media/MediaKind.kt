package ani.sanin.media

/**
 * Whether a title is a series or a film.
 *
 * This is a second axis, orthogonal to [MediaType]: an anime library holds both
 * films and series, and so does the TMDB library. Together they make the four
 * download sections:
 *
 *  - Anime / TV    - anime series
 *  - Anime / Movie - anime films
 *  - TMDB / TV     - live action series
 *  - TMDB / Movie  - live action films
 *
 * Storage is keyed by [MediaType] alone, so both kinds of a library share one
 * folder instead of splitting the user's library in half.
 */
enum class MediaKind : Type {
    TV,
    MOVIE;

    override fun asText(): String {
        return when (this) {
            TV -> "TV"
            MOVIE -> "Movie"
        }
    }

    companion object {
        fun fromText(string: String): MediaKind? {
            return when (string) {
                "TV" -> TV
                "Movie", "MOVIE" -> MOVIE
                else -> null
            }
        }

        /**
         * Derives the kind from the upstream metadata we already have.
         *
         * Anilist reports a `format` of MOVIE for films and TV/TV_SHORT/ONA/OVA/
         * SPECIAL for series; TMDB entries carry `tmdbType` instead. Anything
         * unknown is treated as a series, which is the common case.
         */
        fun of(format: String?, tmdbType: String?): MediaKind {
            if (tmdbType?.equals("movie", ignoreCase = true) == true) return MOVIE
            return when (format?.uppercase()) {
                "MOVIE" -> MOVIE
                else -> TV
            }
        }
    }
}