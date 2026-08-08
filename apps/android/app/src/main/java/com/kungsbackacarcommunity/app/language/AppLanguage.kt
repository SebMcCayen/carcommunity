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
 * the Settings screen; keeping the mapping here means the "absent/unknown tag ->
 * default option (Swedish)" and "tag -> option" rules are unit-tested without
 * touching Android.
 */
enum class AppLanguage(val languageTag: String) {
    SWEDISH("sv"),
    ENGLISH("en"),
    ;

    companion object {
        /**
         * The option the picker shows as selected when the tag we read carries
         * no explicit choice — a null/blank tag, or one this build doesn't offer:
         * Swedish, the app's default resource set.
         *
         * Note the distinction: an EMPTY AppCompat application-locale list means
         * "follow the system locales" (which Android then resolves to res/values-en/
         * on an English device, else the res/values/ Swedish default). This DEFAULT
         * is only the picker's fallback for an absent/unknown TAG, not a claim about
         * runtime resource resolution.
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
