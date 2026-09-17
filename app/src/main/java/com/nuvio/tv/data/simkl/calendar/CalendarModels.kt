package com.nuvio.tv.data.simkl.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate

/**
 * The type of media item shown in the Simkl calendar.
 */
enum class CalendarItemType {
    TV_EPISODE,
    DIGITAL_MOVIE,
    THEATRICAL_MOVIE
}

/**
 * Calendar filter categories with a human-readable display name.
 */
enum class CalendarCategory(val displayName: String) {
    ALL("All"),
    TV_EPISODES("TV Episodes"),
    DIGITAL_STREAMING("Available to Stream"),
    MOVIES("In Theaters")
}

/**
 * Domain model representing a single media item in the calendar.
 */
data class CalendarMediaItem(
    val id: String,
    val title: String,
    val type: CalendarItemType,
    val date: LocalDate,
    val airTimeString: String? = null,
    val digitalReleaseDate: LocalDate? = null,
    val theatricalReleaseDate: LocalDate? = null,
    val isAvailableToStream: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Float? = null,
    val ratingVotes: Int? = null,
    val rank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val simklId: Long? = null
)

/**
 * Domain model grouping calendar items by day with display labels.
 */
data class CalendarDayGroup(
    val date: LocalDate,
    val label: String,
    val shortLabel: String,
    val isToday: Boolean = false,
    val items: List<CalendarMediaItem> = emptyList()
)

data class CalendarData(
    val days: List<CalendarDayGroup> = emptyList(),
    val allItems: List<CalendarMediaItem> = emptyList(),
    val availableToStreamCount: Int = 0
)

/**
 * Builds a Simkl poster URL from a poster path, or null if blank.
 */
fun simklPosterUrl(posterPath: String?): String? =
    if (posterPath.isNullOrBlank()) null else "https://simkl.in/posters/${posterPath}_m.webp"

/**
 * Builds a Simkl fanart URL from a fanart path, or null if blank.
 */
fun simklFanartUrl(fanartPath: String?): String? =
    if (fanartPath.isNullOrBlank()) null else "https://simkl.in/fanart/${fanartPath}_medium.webp"

// ---------------------------------------------------------------------------
// Raw serialization models for Simkl Calendar API responses
// ---------------------------------------------------------------------------

@Serializable
data class SimklTvCalendarItem(
    val title: String? = null,
    val poster: String? = null,
    val date: String? = null,
    @SerialName("release_date") val release_date: String? = null,
    val rank: Int? = null,
    val ratings: SimklRatings? = null,
    val url: String? = null,
    val ids: Map<String, JsonElement> = emptyMap(),
    val episode: SimklTvEpisode? = null
)

@Serializable
data class SimklDvdReleaseItem(
    val title: String? = null,
    val poster: String? = null,
    val fanart: String? = null,
    val url: String? = null,
    val ids: Map<String, JsonElement> = emptyMap(),
    @SerialName("release_date") val release_date: String? = null,
    val rank: Int? = null,
    val ratings: SimklRatings? = null,
    val runtime: String? = null,
    @SerialName("dvd_date") val dvd_date: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val theater: String? = null
)

@Serializable
data class SimklMovieCalendarItem(
    val title: String? = null,
    val poster: String? = null,
    val date: String? = null,
    val rank: Int? = null,
    val ids: Map<String, JsonElement> = emptyMap()
)

fun Map<String, JsonElement>.simklImdbId(): String? =
    (get("imdb") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

fun Map<String, JsonElement>.simklTmdbId(): String? =
    (get("tmdb") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

fun Map<String, JsonElement>.simklNumericId(): Long? =
    (get("simkl_id") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

@Serializable
data class SimklTvEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    val name: String? = null,
    val url: String? = null
)

@Serializable
data class SimklRatings(
    val simkl: SimklRatingValue? = null,
    val imdb: SimklRatingValue? = null
)

@Serializable
data class SimklRatingValue(
    val rating: Float? = null,
    val votes: Int? = null
)
