package com.kungsbackacarcommunity.app.design

import java.time.LocalTime

/** Fixed wall-clock schedule in the phone's current timezone, not sunrise/sunset. */
object AutomaticThemeSchedule {
    private val dayStart = LocalTime.of(7, 0)
    private val nightStart = LocalTime.of(19, 0)

    fun isDark(localTime: LocalTime): Boolean = localTime < dayStart || localTime >= nightStart
}
