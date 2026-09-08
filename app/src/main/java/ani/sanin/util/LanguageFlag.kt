package ani.sanin.util

/**
 * Maps language / locale codes to regional-indicator flag emoji.
 *
 * Handles common subtitle formats:
 *  - "en-US", "ja-JP", "es-ES"  (ISO 639-1 + ISO 3166-1)
 *  - "en", "ja", "es"            (ISO 639-1 only → best-effort country)
 *  - "English", "Japanese"       (full language names)
 */
object LanguageFlag {

    /** Regional indicator offset: 'A' = 0x1F1E6. */
    private fun countryCodeToFlag(country: String): String {
        val code = country.uppercase().take(2)
        if (code.length < 2) return ""
        return buildString {
            for (ch in code) {
                append(Character.toChars(0x1F1E6 + (ch.code - 'A'.code)))
            }
        }
    }

    /**
     * Extract a 2-letter country code from a subtitle language string and
     * return its flag emoji.  Returns empty string when the code can't be
     * mapped.
     */
    fun flagForLanguage(lang: String): String {
        // 1. Try "xx-YY" pattern — take the country part after the dash
        val dashMatch = Regex("^[a-zA-Z]{2}-([A-Za-z]{2}|\\d{3})$").find(lang)
        if (dashMatch != null) {
            val country = dashMatch.groupValues[1]
            // 3-digit codes (e.g. 419 for Latin America) don't map to flags
            if (country.length == 2) return countryCodeToFlag(country)
        }

        // 2. Try bare 2-letter ISO 639-1 code → map to a common country
        val bare = Regex("^([a-zA-Z]{2})$").find(lang)?.groupValues?.get(1)
        if (bare != null) {
            val mapped = LANGUAGE_TO_COUNTRY[bare.lowercase()]
            if (mapped != null) return countryCodeToFlag(mapped)
        }

        // 3. Try full language name
        val nameKey = lang.trim().lowercase()
        val mappedCountry = FULL_NAME_TO_COUNTRY[nameKey]
        if (mappedCountry != null) return countryCodeToFlag(mappedCountry)

        return ""
    }

    /** Common language → default country mapping. */
    private val LANGUAGE_TO_COUNTRY = mapOf(
        "en" to "US", "ja" to "JP", "ko" to "KR", "zh" to "CN",
        "es" to "ES", "fr" to "FR", "de" to "DE", "it" to "IT",
        "pt" to "PT", "ru" to "RU", "ar" to "SA", "hi" to "IN",
        "th" to "TH", "vi" to "VN", "tr" to "TR", "pl" to "PL",
        "nl" to "NL", "sv" to "SE", "da" to "DK", "fi" to "FI",
        "no" to "NO", "uk" to "UA", "cs" to "CZ", "el" to "GR",
        "he" to "IL", "ro" to "RO", "hu" to "HU", "id" to "ID",
        "ms" to "MY", "tl" to "PH", "bn" to "BD", "ur" to "PK",
    )

    /** Full language name → country mapping. */
    private val FULL_NAME_TO_COUNTRY = mapOf(
        "english" to "US", "japanese" to "JP", "korean" to "KR",
        "chinese" to "CN", "spanish" to "ES", "french" to "FR",
        "german" to "DE", "italian" to "IT", "portuguese" to "PT",
        "russian" to "RU", "arabic" to "SA", "hindi" to "IN",
        "thai" to "TH", "vietnamese" to "VN", "turkish" to "TR",
        "polish" to "PL", "dutch" to "NL", "swedish" to "SE",
        "danish" to "DK", "finnish" to "FI", "norwegian" to "NO",
        "ukrainian" to "UA", "czech" to "CZ", "greek" to "GR",
        "hebrew" to "IL", "romanian" to "RO", "hungarian" to "HU",
        "indonesian" to "ID", "malay" to "MY", "filipino" to "PH",
        "bengali" to "BD", "urdu" to "PK", "tamil" to "IN",
        "telugu" to "IN", "punjabi" to "IN", "marathi" to "IN",
    )
}
