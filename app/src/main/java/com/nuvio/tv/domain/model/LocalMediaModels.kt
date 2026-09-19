package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * Represents a single locally stored media file discovered on a NAS or local storage device.
 *
 * The [id] is a stable, deterministic identifier derived from the media type and a content/path
 * hash, e.g. `local:movie:hash` or `local:series:hash`.
 */
@Immutable
data class LocalMediaItem(
    /** Unique identifier, e.g. `local:movie:hash` or `local:series:hash`. */
    val id: String,
    /** Absolute filesystem path to the media file. */
    val filePath: String,
    /** URI form of the file, e.g. `file:///storage/...`. */
    val fileUri: String,
    /** Human-readable title of the movie or series. */
    val title: String,
    /** Media type: either `movie` or `series`. */
    val type: String,
    /** Release year, if known. */
    val year: Int? = null,
    /** Season number for series episodes. */
    val season: Int? = null,
    /** Episode number for series episodes. */
    val episode: Int? = null,
    /** Title of the specific episode, if available. */
    val episodeTitle: String? = null,
    /** File size in bytes. */
    val fileSize: Long = 0L,
    /** Human-readable resolution label: `4K`, `1080p`, `720p`, or `SD`. */
    val resolution: String = RESOLUTION_1080P,
    /** Numeric quality value used for sorting/filtering: `2160`, `1080`, `720`, or `480`. */
    val qualityValue: Int = QUALITY_1080,
    /** Container format, e.g. `MKV`, `MP4`. */
    val format: String = FORMAT_MKV,
    /** Last modified timestamp in epoch milliseconds. */
    val lastModified: Long = 0L,
) {
    /** Display-friendly quality label, falling back to the raw [resolution] value. */
    val displayQuality: String
        get() = resolution.ifBlank { QUALITY_LABELS[qualityValue] ?: resolution }

    /** File size formatted as GB or MB, e.g. `4.20 GB` or `512.0 MB`. */
    val displayFileSize: String
        get() = formatFileSize(fileSize)

    /** True when this item represents a series episode. */
    val isEpisode: Boolean
        get() = type == TYPE_SERIES && season != null && episode != null

    companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_SERIES = "series"

        const val RESOLUTION_4K = "4K"
        const val RESOLUTION_1080P = "1080p"
        const val RESOLUTION_720P = "720p"
        const val RESOLUTION_SD = "SD"

        const val QUALITY_2160 = 2160
        const val QUALITY_1080 = 1080
        const val QUALITY_720 = 720
        const val QUALITY_480 = 480

        const val FORMAT_MKV = "MKV"

        private const val BYTES_PER_KB = 1024.0
        private const val BYTES_PER_MB = BYTES_PER_KB * 1024.0
        private const val BYTES_PER_GB = BYTES_PER_MB * 1024.0

        private val QUALITY_LABELS: Map<Int, String> = mapOf(
            QUALITY_2160 to RESOLUTION_4K,
            QUALITY_1080 to RESOLUTION_1080P,
            QUALITY_720 to RESOLUTION_720P,
            QUALITY_480 to RESOLUTION_SD,
        )

        /**
         * Formats a byte count into a compact GB/MB string.
         * Values below 1 MB are rendered in KB for completeness.
         */
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val size = bytes.toDouble()
            return when {
                size >= BYTES_PER_GB -> String.format(Locale.US, "%.2f GB", size / BYTES_PER_GB)
                size >= BYTES_PER_MB -> String.format(Locale.US, "%.1f MB", size / BYTES_PER_MB)
                else -> String.format(Locale.US, "%.0f KB", size / BYTES_PER_KB)
            }
        }
    }
}

/**
 * Aggregated result of a local media scan across one or more storage paths.
 */
@Immutable
data class LocalMediaScanSummary(
    /** Total number of files inspected during the scan. */
    val totalFilesScanned: Int,
    /** Number of distinct movies discovered. */
    val totalMovies: Int,
    /** Number of distinct series discovered. */
    val totalSeries: Int,
    /** Total number of series episodes discovered. */
    val totalEpisodes: Int,
    /** Duration of the scan in milliseconds. */
    val scanDurationMs: Long,
    /** Storage paths that were scanned. */
    val scannedPaths: List<String>,
    /** Epoch-millisecond timestamp of when the scan completed. */
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** Total number of playable media items (movies + episodes). */
    val totalItems: Int
        get() = totalMovies + totalEpisodes

    /** Scan duration formatted in seconds, e.g. `12.4s`. */
    val displayScanDuration: String
        get() = String.format(Locale.US, "%.1fs", scanDurationMs / 1000.0)
}

/**
 * Aggregated view of a single series and all of its locally stored episodes.
 */
@Immutable
data class LocalSeriesSummary(
    /** Series title. */
    val title: String,
    /** Release year, if known. */
    val year: Int? = null,
    /** Number of distinct seasons present. */
    val totalSeasons: Int,
    /** Total number of episodes present. */
    val totalEpisodes: Int,
    /** All episodes belonging to this series. */
    val episodes: List<LocalMediaItem>,
) {
    /** Episodes grouped by season number, sorted ascending. */
    val episodesBySeason: Map<Int, List<LocalMediaItem>>
        get() = episodes
            .filter { it.season != null }
            .groupBy { it.season!! }
            .toSortedMap()

    /** Highest quality value among all episodes, useful for badge display. */
    val maxQualityValue: Int
        get() = episodes.maxOfOrNull { it.qualityValue } ?: 0

    /** Display-friendly quality label for the series' best available episode. */
    val displayQuality: String
        get() = episodes
            .maxByOrNull { it.qualityValue }
            ?.displayQuality
            ?: LocalMediaItem.RESOLUTION_SD
}

fun LocalMediaItem.toMetaPreview(): MetaPreview = MetaPreview(
    id = id,
    type = if (type == LocalMediaItem.TYPE_SERIES) ContentType.SERIES else ContentType.MOVIE,
    name = title,
    poster = null,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = "Local storage • $displayQuality • $displayFileSize",
    releaseInfo = year?.toString(),
    imdbRating = null,
    genres = listOf("Local NAS", displayQuality)
)

fun LocalSeriesSummary.toMetaPreview(): MetaPreview = MetaPreview(
    id = episodes.firstOrNull()?.id ?: "local:series:${title.lowercase().replace(' ', '_')}",
    type = ContentType.SERIES,
    name = title,
    poster = null,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = "Local NAS • $totalSeasons Seasons • $totalEpisodes Episodes",
    releaseInfo = year?.toString(),
    imdbRating = null,
    genres = listOf("Local NAS", displayQuality)
)
