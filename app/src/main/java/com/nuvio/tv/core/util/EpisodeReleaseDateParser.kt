package com.nuvio.tv.core.util

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val ISO_DATE_PATTERN = Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)")

internal const val BROADCAST_AIR_BUFFER_HOURS = 0L
internal const val POST_BROADCAST_AIR_BUFFER_HOURS = 1L

/**
 * Converts timestamped episode releases into the viewer's local calendar date. Plain dates have
 * no timezone information and are preserved as supplied by the provider.
 */
internal fun parseEpisodeReleaseLocalDate(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return runCatching { LocalDate.parse(value) }.getOrNull()
        ?: parseExplicitReleaseInstant(value)?.atZone(zoneId)?.toLocalDate()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)
}

/**
 * Returns whether a known release has been reached. Zoned timestamps use their exact instant,
 * while date-only values use UTC midnight. A local timestamp without a zone is interpreted in
 * the viewer's timezone.
 *
 * A post-broadcast buffer (default 1 hour) is applied so that episodes only evaluate as aired
 * after broadcast playback has ended and the streaming release is available.
 */
internal fun isEpisodeReleaseAired(
    raw: String?,
    clock: Clock = Clock.systemDefaultZone(),
    airBufferHours: Long = 0L,
    postAirBufferHours: Long = 0L
): Boolean? {
    val releaseInstant = parseEpisodeReleaseInstant(raw, clock.zone) ?: return null
    val availableInstant = if (postAirBufferHours > 0L) {
        releaseInstant.plus(Duration.ofHours(postAirBufferHours))
    } else {
        releaseInstant
    }
    return if (airBufferHours > 0L) {
        !availableInstant.isAfter(clock.instant().plus(Duration.ofHours(airBufferHours)))
    } else {
        !clock.instant().isBefore(availableInstant)
    }
}

internal fun parseEpisodeReleaseInstant(
    raw: String?,
    zoneId: ZoneId = ZoneId.systemDefault()
): Instant? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return parseExplicitReleaseInstant(value)
        ?: runCatching { LocalDateTime.parse(value).atZone(zoneId).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(value).atStartOfDay(zoneId).toInstant() }.getOrNull()
        ?: parseEmbeddedReleaseDate(value)?.atStartOfDay(zoneId)?.toInstant()
}

/**
 * Uses the add-on release timestamp unless the user explicitly opts into TMDB's date-only
 * broadcaster calendar dates.
 */
internal fun selectEpisodeReleaseValue(
    addonReleased: String?,
    tmdbAirDate: String?,
    useTmdbReleaseDates: Boolean
): String? {
    val addonValue = addonReleased?.trim()?.takeIf { it.isNotEmpty() }
    if (!useTmdbReleaseDates) return addonValue
    return tmdbAirDate?.trim()?.takeIf { it.isNotEmpty() } ?: addonValue
}

private fun parseExplicitReleaseInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

private fun parseEmbeddedReleaseDate(value: String): LocalDate? =
    ISO_DATE_PATTERN.find(value)?.value?.let { datePortion ->
        runCatching { LocalDate.parse(datePortion) }.getOrNull()
    }
