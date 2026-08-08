package com.kungsbackacarcommunity.app.language

/**
 * The app-language choice offered in Settings: Swedish (the app default) or
 * English.
 *
 * The app ships Swedish as its default resource set (res/values/) with English
 * as an override (res/values-en/). This enum is the pure, JVM-testable seam
 * between "which option did the user pick" and "which BCP-47 language tag do we
 * hand to AppCompat". The framework glue that actually applies it —
 * [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales] /
 * [androidx.appcompat.app.AppCompatDelegate.getApplicationLocales] — lives in
 * the Settings screen; keeping the mapping here means the "empty selection ->
 * follow the default (Swedish)" and "tag -> option" rules are unit-tested
 * without touching Android.
 */
enum class AppLanguage(val languageTag: String) {
    SWEDISH("sv"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * The option applied when the user has made no explicit choice (a fresh
         * install with an empty application-locale list): follow the app's
         * default resources, which are Swedish.
         */
        val DEFAULT: AppLanguage = SWEDISH

        /**
         * Maps a stored/active language tag to the matching option. Accepts a
         * bare subtag ("sv") or a full BCP-47 tag ("en-US") — only the primary
         * language subtag is considered. A null/blank tag (no explicit
         * selection) or one this build does not offer falls back to [DEFAULT]
         * rather than throwing, so a value written by a future build or a
         * region-qualified system locale can never crash the Settings screen.
         */
        fun fromLanguageTag(tag: String?): AppLanguage {
            val language = tag?.trim()?.substringBefore('-')?.lowercase()
            if (language.isNullOrEmpty()) return DEFAULT
            return entries.find { it.languageTag == language } ?: DEFAULT
        }
    }
}
