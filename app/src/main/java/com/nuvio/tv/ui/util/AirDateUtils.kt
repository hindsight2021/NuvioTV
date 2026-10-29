package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.BROADCAST_AIR_BUFFER_HOURS
import com.nuvio.tv.core.util.POST_BROADCAST_AIR_BUFFER_HOURS
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    return parseEpisodeReleaseLocalDate(raw)
}

internal fun computeAirDateBadgeText(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
    if (isEpisodeReleaseAired(releasedIso, airBufferHours = BROADCAST_AIR_BUFFER_HOURS, postAirBufferHours = POST_BROADCAST_AIR_BUFFER_HOURS) == true) return null

    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0 -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
}

/**
 * Short version of [computeAirDateBadgeText] for compact card styles (wide/poster).
 * Returns "Today", "Tomorrow", "In X Days" instead of "Airs Today", etc.
 */
internal fun computeAirDateBadgeTextShort(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date_short, it) }
    }
    if (isEpisodeReleaseAired(releasedIso, airBufferHours = BROADCAST_AIR_BUFFER_HOURS, postAirBufferHours = POST_BROADCAST_AIR_BUFFER_HOURS) == true) return null

    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0 -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today_short)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow_short)
        daysUntil in 2..7 -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days_short,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date_short, it) }
    }
}

private val HALIFAX_ZONE: ZoneId = runCatching { ZoneId.of("America/Halifax") }.getOrDefault(ZoneId.systemDefault())

/**
 * Formats an episode release date/time in Atlantic (Halifax) timezone with automatic DST adjustment.
 *
 * If [released] contains an explicit time component (or [releaseTimestamp] is non-null), returns
 * both date and time (e.g. "Oct 29, 2026, 7:00 PM").
 * If [released] is date-only, returns only the date (e.g. "Oct 29, 2026").
 */
internal fun formatEpisodeReleaseDateTimeInHalifax(
    released: String?,
    releaseTimestamp: Long? = null,
    context: Context
): String? {
    val trimmed = released?.trim()
    if (trimmed.isNullOrEmpty() && releaseTimestamp == null) return null

    val hasExplicitTime = trimmed != null && (trimmed.contains('T') || trimmed.contains(':'))

    return if (hasExplicitTime || releaseTimestamp != null) {
        val instant: Instant = parseEpisodeReleaseInstant(trimmed, HALIFAX_ZONE)
            ?: releaseTimestamp?.let { Instant.ofEpochMilli(it) }
            ?: return null

        val zoned: ZonedDateTime = instant.atZone(HALIFAX_ZONE)
        val locale = Locale.getDefault()
        val dateSkeleton = "dMMMy"
        val datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, dateSkeleton)
        val dateFormatter = DateTimeFormatter.ofPattern(datePattern, locale)
        val timePattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
        val timeFormatter = DateTimeFormatter.ofPattern(timePattern, locale)

        "${dateFormatter.format(zoned)}, ${timeFormatter.format(zoned)}"
    } else {
        val localDate: LocalDate = parseEpisodeReleaseLocalDate(trimmed, HALIFAX_ZONE) ?: return null
        val locale = Locale.getDefault()
        val dateSkeleton = "dMMMy"
        val datePattern = android.text.format.DateFormat.getBestDateTimePattern(locale, dateSkeleton)
        DateTimeFormatter.ofPattern(datePattern, locale).format(localDate)
    }
}
