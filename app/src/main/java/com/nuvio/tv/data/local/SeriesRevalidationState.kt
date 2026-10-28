package com.nuvio.tv.data.local

/**
 * Persistent per-series revalidation metadata.
 * Replaces the flat deadline map + ongoing_series_ids set.
 */
data class SeriesRevalidationState(
    /** Epoch ms when this series must be re-checked for new episodes. */
    val deadline: Long,
    /** Series lifecycle status - drives TTL computation. */
    val status: SeriesAirStatus,
    /** Epoch ms of next expected episode release (null if unknown/TBA). */
    val nextAirTimestampMs: Long? = null,
    /** Epoch ms when the pipeline last validated this series. */
    val lastValidatedMs: Long
)

enum class SeriesAirStatus {
    /** Actively airing or confirmed returning - short TTL. */
    ONGOING,
    /** Series concluded - long TTL. */
    ENDED,
    /** Series cancelled - long TTL. */
    CANCELED,
    /** No metadata available yet - medium TTL. */
    UNKNOWN
}

/**
 * Input from the pipeline's metadata resolution.
 * Passed by the caller (HomeViewModel) - replaces the old revalidateAt map.
 */
data class SeriesValidationInput(
    val status: SeriesAirStatus,
    val nextAirTimestampMs: Long? = null
)

