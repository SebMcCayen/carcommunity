package com.kungsbackacarcommunity.app.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The language-selection mapping: which option a stored/active language tag
 * resolves to, and the tag each option hands to AppCompat. Pure Kotlin, so it
 * runs on the JVM — the Compose side (that picking an option calls
 * setApplicationLocales and the strings actually switch) is framework glue in
 * SettingsScreen and is not covered here.
 */
class AppLanguageTest {

    @Test
    fun `bare subtags map to their option`() {
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("sv"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en"))
    }

    @Test
    fun `region-qualified tags map on the primary subtag`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en-US"))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("sv-SE"))
    }

    @Test
    fun `case and surrounding whitespace are ignored`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag(" EN "))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("SV"))
    }

    /**
     * An absent/unknown language tag — null, blank, or one this build doesn't
     * offer — must fall back to the default option (Swedish), not throw and not
     * silently pick English. (Distinct from an empty AppCompat locale list, which
     * means "follow system"; the Settings screen maps that read to a null tag.)
     */
    @Test
    fun `null or blank falls back to the Swedish default`() {
        assertEquals(AppLanguage.SWEDISH, AppLanguage.DEFAULT)
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag(null))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag(""))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("   "))
    }

    /**
     * A tag this build does not offer (a system locale the app has no
     * translation for, or a value written by a future build) falls back to the
     * default rather than throwing — this is read while composing the Settings
     * screen, so a throw here would be a crash.
     */
    @Test
    fun `an unsupported tag falls back to the default`() {
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("de"))
        assertEquals(AppLanguage.SWEDISH, AppLanguage.fromLanguageTag("fr-FR"))
    }

    @Test
    fun `each option carries its BCP-47 language tag`() {
        assertEquals("sv", AppLanguage.SWEDISH.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
    }
}
