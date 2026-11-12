package com.kungsbackacarcommunity.app.design

import java.time.LocalTime

/** Daily wall-clock schedule in the phone's current timezone, including overnight day periods. */
data class AutomaticThemeSchedule(
    val dayStart: LocalTime = LocalTime.of(7, 0),
    val nightStart: LocalTime = LocalTime.of(19, 0),
) {
    init {
        require(dayStart != nightStart) { "Day and night must start at different times" }
    }

    fun isDark(localTime: LocalTime): Boolean =
        if (dayStart < nightStart) localTime < dayStart || localTime >= nightStart
        else localTime >= nightStart && localTime < dayStart

    companion object {
        val DEFAULT = AutomaticThemeSchedule()

        fun isDark(localTime: LocalTime): Boolean = DEFAULT.isDark(localTime)

        /** Bad persisted minutes or identical boundaries recover to the complete default schedule. */
        fun fromStoredMinutes(day: Int, night: Int): AutomaticThemeSchedule =
            if (day in 0..1439 && night in 0..1439 && day != night) {
                AutomaticThemeSchedule(LocalTime.ofSecondOfDay(day * 60L), LocalTime.ofSecondOfDay(night * 60L))
            } else DEFAULT
    }
}
