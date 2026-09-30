package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.time.Clock
import java.time.LocalDate

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

fun MetaPreview.isUnreleased(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): Boolean {
    released?.trim()?.takeIf { it.isNotEmpty() }?.let { rawReleased ->
        isEpisodeReleaseAired(rawReleased, clock)?.let { hasAired ->
            return !hasAired
        }
    }

    val info = releaseInfo ?: return false
    isEpisodeReleaseAired(info.trim(), clock)?.let { hasAired ->
        return !hasAired
    }
    val yearStr = YEAR_REGEX.find(info)?.value ?: return false
    val year = yearStr.toIntOrNull() ?: return false
    return year > today.year
}

fun CatalogRow.filterReleasedItems(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): CatalogRow {
    val filtered = items.filterNot { it.isUnreleased(today, clock) }
    return if (filtered.size == items.size) this else copy(items = filtered)
}

/**
 * True when the item carries no release information at all. Only meaningful for
 * sources where dates are authoritative (e.g. TMDB, where a movie without a
 * release_date is unannounced); addon metadata often legitimately omits dates.
 */
fun MetaPreview.hasNoReleaseInfo(): Boolean =
    released.isNullOrBlank() && releaseInfo.isNullOrBlank()

/**
 * Returns true if this title's release date falls within [daysWindow] days prior to [today].
 */
fun MetaPreview.isRecentlyReleased(
    today: LocalDate = LocalDate.now(),
    daysWindow: Long = 14
): Boolean {
    val date = parseEpisodeReleaseLocalDate(released)
        ?: parseEpisodeReleaseLocalDate(releaseInfo)
        ?: return false
    return !date.isAfter(today) && !date.isBefore(today.minusDays(daysWindow))
}
