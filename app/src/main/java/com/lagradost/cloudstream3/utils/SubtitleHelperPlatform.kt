package com.lagradost.cloudstream3.utils

/**
 * Returns the current locale as an IETF BCP 47 language tag.
 */
fun getCurrentLocale(): String = java.util.Locale.getDefault().toLanguageTag()

/**
 * Returns the display name of [ietfTag] localized into [localizedTo].
 * Returns null if the platform couldn't produce a meaningful name
 * (i.e. it just echoed back the tag or contained a bare language code with parentheses).
 */
fun localizedLanguageName(ietfTag: String, localizedTo: String): String? {
    val localeOfLangCode = Locale.forLanguageTag(ietfTag)
    val localeOfLocalizeTo = Locale.forLanguageTag(localizedTo)
    val displayName = localeOfLangCode.getDisplayName(localeOfLocalizeTo)

    // Locale.getDisplayName() falls back to the raw tag or "language (country)" form
    // when it doesn't know how to render the name.
    val langCodeWithCountry = "${localeOfLangCode.language} ("
    val failed =
        displayName.equals(ietfTag, ignoreCase = true) ||
        displayName.contains(langCodeWithCountry, ignoreCase = true)

    return if (failed) null else displayName
}