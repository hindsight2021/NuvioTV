package com.nuvio.tv.ambient.context

import com.nuvio.tv.ambient.AmbientSeason
import com.nuvio.tv.ambient.AmbientTimeBucket
import java.time.LocalDateTime
import java.time.Month
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves ambient time context (time-of-day bucket and season) from a [LocalDateTime].
 */
@Singleton
class AmbientTimeContext @Inject constructor() {

    /**
     * Maps the time-of-day of [dateTime] to an [AmbientTimeBucket].
     */
    fun getCurrentTimeBucket(dateTime: LocalDateTime = LocalDateTime.now()): AmbientTimeBucket {
        val minutesOfDay = dateTime.hour * 60 + dateTime.minute

        return when (minutesOfDay) {
            in minutesOf(5, 0)..minutesOf(8, 59) -> AmbientTimeBucket.EARLY_MORNING
            in minutesOf(9, 0)..minutesOf(16, 59) -> AmbientTimeBucket.DAY
            in minutesOf(17, 0)..minutesOf(19, 29) -> AmbientTimeBucket.GOLDEN_HOUR
            in minutesOf(19, 30)..minutesOf(21, 59) -> AmbientTimeBucket.EVENING
            in minutesOf(22, 0)..minutesOf(23, 59),
            in minutesOf(0, 0)..minutesOf(0, 59) -> AmbientTimeBucket.LATE_NIGHT
            else -> AmbientTimeBucket.OVERNIGHT
        }
    }

    /**
     * Maps the month of [dateTime] to an [AmbientSeason] using meteorological seasons.
     */
    fun getCurrentSeason(dateTime: LocalDateTime = LocalDateTime.now()): AmbientSeason =
        when (dateTime.month) {
            Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> AmbientSeason.WINTER
            Month.MARCH, Month.APRIL, Month.MAY -> AmbientSeason.SPRING
            Month.JUNE, Month.JULY, Month.AUGUST -> AmbientSeason.SUMMER
            Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER -> AmbientSeason.AUTUMN
        }

    fun getTimeTags(bucket: AmbientTimeBucket): List<String> = when (bucket) {
        AmbientTimeBucket.EARLY_MORNING -> listOf("early_morning", "morning", "sunrise", "day")
        AmbientTimeBucket.DAY -> listOf("day", "afternoon", "sunny")
        AmbientTimeBucket.GOLDEN_HOUR -> listOf("golden_hour", "sunset", "evening")
        AmbientTimeBucket.EVENING -> listOf("evening", "sunset", "night")
        AmbientTimeBucket.LATE_NIGHT -> listOf("late_night", "night", "midnight")
        AmbientTimeBucket.OVERNIGHT -> listOf("overnight", "night", "late_night")
    }

    fun getSeasonTags(season: AmbientSeason): List<String> = when (season) {
        AmbientSeason.WINTER -> listOf("winter", "snow", "cold")
        AmbientSeason.SPRING -> listOf("spring", "bloom")
        AmbientSeason.SUMMER -> listOf("summer", "warm", "sunny")
        AmbientSeason.AUTUMN -> listOf("autumn", "fall", "foliage")
    }

    private fun minutesOf(hour: Int, minute: Int): Int = hour * 60 + minute
}
