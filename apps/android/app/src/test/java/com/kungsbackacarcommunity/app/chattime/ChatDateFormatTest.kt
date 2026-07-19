package com.kungsbackacarcommunity.app.chattime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The separator's wording and the per-message time.
 *
 * The two patterns asserted here are the exact values shipped in
 * `contracts/localization/{en,sv}.json` under `chatTime.daySeparatorFormat` —
 * duplicated as constants because a unit test cannot read Android resources, and
 * because what is worth pinning is that THOSE patterns produce the required
 * output in their own locale.
 */
class ChatDateFormatTest {
    private companion object {
        /** contracts/localization/en.json → chatTime.daySeparatorFormat */
        const val EN_PATTERN = "EEEE, d MMMM"

        /** contracts/localization/en.json → chatTime.daySeparatorFormatWithYear */
        const val EN_PATTERN_YEAR = "EEEE, d MMMM yyyy"

        /** contracts/localization/sv.json → chatTime.daySeparatorFormat */
        const val SV_PATTERN = "EEEE d MMMM"

        /** contracts/localization/sv.json → chatTime.daySeparatorFormatWithYear */
        const val SV_PATTERN_YEAR = "EEEE d MMMM yyyy"
    }

    private val english = Locale.forLanguageTag("en-GB")
    private val swedish = Locale.forLanguageTag("sv-SE")
    private val stockholm: ZoneId = ZoneId.of("Europe/Stockholm")

    // 21 July 2026 really is a Tuesday — the point of the assertion is the exact
    // shape Seb asked for ("Tuesday, 19 July"), and a date whose weekday did not
    // match would have pinned a lie.
    @Test
    fun englishSeparatorReadsWeekdayCommaDayMonth() {
        val date = LocalDate.of(2026, 7, 21)

        assertEquals("Tuesday, 21 July", ChatDateFormat.format(date, EN_PATTERN, english))
    }

    /**
     * Swedish weekday and month names are LOWER CASE, and there is no comma. This
     * is not a stylistic choice made here — it is what the locale's own CLDR data
     * contains, which is exactly why the names come from `java.time` + the locale
     * rather than from a hand-written English string.
     */
    @Test
    fun swedishSeparatorIsLowerCaseAndCommaLess() {
        val date = LocalDate.of(2026, 7, 21)

        assertEquals("tisdag 21 juli", ChatDateFormat.format(date, SV_PATTERN, swedish))
    }

    @Test
    fun theWithYearPatternsAppendTheYearInBothLocales() {
        val date = LocalDate.of(2024, 1, 3)

        assertEquals(
            "Wednesday, 3 January 2024",
            ChatDateFormat.format(date, EN_PATTERN_YEAR, english),
        )
        assertEquals(
            "onsdag 3 januari 2024",
            ChatDateFormat.format(date, SV_PATTERN_YEAR, swedish),
        )
    }

    @Test
    fun todayAndYesterdayAreLabelledAsSuch() {
        val today = LocalDate.of(2026, 7, 19)

        assertEquals(DaySeparatorLabel.Today, ChatDateFormat.label(today, today))
        assertEquals(
            DaySeparatorLabel.Yesterday,
            ChatDateFormat.label(today.minusDays(1), today),
        )
    }

    @Test
    fun twoDaysBackIsSpelledOutWithNoYear() {
        val today = LocalDate.of(2026, 7, 19)
        val date = today.minusDays(2)

        assertEquals(DaySeparatorLabel.Absolute(date, includeYear = false), ChatDateFormat.label(date, today))
    }

    /** Across a year boundary a bare "Tuesday, 19 July" is ambiguous, so the year appears. */
    @Test
    fun aDateInAnotherYearCarriesTheYear() {
        val today = LocalDate.of(2026, 1, 2)
        val date = LocalDate.of(2025, 12, 31)

        assertEquals(DaySeparatorLabel.Absolute(date, includeYear = true), ChatDateFormat.label(date, today))
    }

    /** The day before New Year's Day is still "Yesterday", year change or not. */
    @Test
    fun yesterdayWinsOverTheYearBoundary() {
        val today = LocalDate.of(2026, 1, 1)

        assertEquals(
            DaySeparatorLabel.Yesterday,
            ChatDateFormat.label(LocalDate.of(2025, 12, 31), today),
        )
    }

    @Test
    fun timeIsRenderedInTheDevicesZone() {
        // 22:30 UTC on 19 July is 00:30 on the 20th in Stockholm (UTC+2).
        val millis =
            LocalDateTime.of(2026, 7, 19, 22, 30)
                .atZone(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()

        assertEquals(
            "00:30",
            ChatDateFormat.time(millis, stockholm, swedish, use24Hour = true),
        )
        assertEquals(
            "22:30",
            ChatDateFormat.time(millis, ZoneId.of("UTC"), swedish, use24Hour = true),
        )
    }

    @Test
    fun theTwelveHourClockFollowsTheDeviceSettingNotTheLocale() {
        val millis =
            LocalDateTime.of(2026, 7, 19, 19, 5)
                .atZone(stockholm)
                .toInstant()
                .toEpochMilli()

        assertEquals("19:05", ChatDateFormat.time(millis, stockholm, english, use24Hour = true))
        // Swedish locale, 12-hour device: the setting wins.
        assertEquals(
            "7:05",
            ChatDateFormat.time(millis, stockholm, swedish, use24Hour = false).substringBefore(' '),
        )
    }

    @Test
    fun localDateResolvesTheDayAMessageBelongsUnder() {
        val millis =
            LocalDateTime.of(2026, 7, 20, 0, 30)
                .atZone(stockholm)
                .toInstant()
                .toEpochMilli()

        assertEquals(LocalDate.of(2026, 7, 20), ChatDateFormat.localDate(millis, stockholm))
        assertEquals(LocalDate.of(2026, 7, 19), ChatDateFormat.localDate(millis, ZoneId.of("UTC")))
    }
}
