package com.nuvio.tv.ambient

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * High-level ambient content channel. [AUTO] delegates selection to the engine,
 * while the remaining values represent curated thematic buckets.
 */
@Serializable
enum class AmbientChannel(
    val displayName: String,
    val description: String
) {
    @SerialName("for_you")
    FOR_YOU(
        displayName = "For You",
        description = "Automatically selects the best ambient scene for the moment."
    ),

    @SerialName("aerial")
    AERIAL(
        displayName = "Aerial",
        description = "Scenic aerial drone flights and landmarks from around the world."
    ),

    @SerialName("after_dark")
    AFTER_DARK(
        displayName = "After Dark",
        description = "Low-light, moody scenes for late-night ambience."
    ),

    @SerialName("nature")
    NATURE(
        displayName = "Nature",
        description = "Forests, oceans, mountains, and wildlife."
    ),

    @SerialName("space")
    SPACE(
        displayName = "Space",
        description = "Stars, planets, nebulae, and cosmic vistas."
    ),

    @SerialName("cities")
    CITIES(
        displayName = "Cities",
        description = "Urban skylines, streets, and city life."
    ),

    @SerialName("weather")
    WEATHER(
        displayName = "Weather",
        description = "Rain, snow, storms, and atmospheric conditions."
    ),

    @SerialName("fireplace")
    FIREPLACE(
        displayName = "Fireplace",
        description = "Cozy crackling fires and warm hearth scenes."
    ),

    @SerialName("cinema")
    CINEMA(
        displayName = "Cinema",
        description = "Cinematic, film-like ambient compositions."
    );

    companion object {
        val AUTO: AmbientChannel get() = FOR_YOU
        val WORLD: AmbientChannel get() = AERIAL
    }
}

/**
 * Time-of-day bucket used to match ambient content to the current clock.
 */
@Serializable
enum class AmbientTimeBucket {
    @SerialName("early_morning")
    EARLY_MORNING,

    @SerialName("day")
    DAY,

    @SerialName("golden_hour")
    GOLDEN_HOUR,

    @SerialName("evening")
    EVENING,

    @SerialName("late_night")
    LATE_NIGHT,

    @SerialName("overnight")
    OVERNIGHT
}

/**
 * Seasonal bucket used to match ambient content to the current season.
 */
@Serializable
enum class AmbientSeason {
    @SerialName("spring")
    SPRING,

    @SerialName("summer")
    SUMMER,

    @SerialName("autumn")
    AUTUMN,

    @SerialName("winter")
    WINTER
}

/**
 * A single playable ambient scene candidate produced by the content provider layer.
 *
 * All scoring fields are normalized to a 0.0..1.0 range unless otherwise noted.
 */
@Serializable
data class AmbientCandidate(
    val id: String,
    val provider: String = "youtube",
    val playbackUri: String? = null,
    val youtubeVideoId: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val country: String? = null,
    val category: String,
    val subcategories: List<String> = emptyList(),
    val weatherTags: List<String> = emptyList(),
    val seasonTags: List<String> = emptyList(),
    val timeTags: List<String> = emptyList(),
    val eventTags: List<String> = emptyList(),
    val is4K: Boolean = true,
    val isHdr: Boolean = false,
    val fps: Int = 60,
    val durationSeconds: Long = 3600L,
    val qualityScore: Double = 1.0,
    val calmnessScore: Double = 1.0,
    val motionScore: Double = 1.0,
    val reliabilityScore: Double = 1.0,
    val lastPlayedEpochMs: Long? = null,
    val timesPlayed: Int = 0
) {
    /**
     * Resolves the best available playback target, preferring an explicit URI
     * and falling back to a YouTube watch URL when a video id is present.
     */
    val resolvedPlaybackUri: String?
        get() = playbackUri
            ?: youtubeVideoId?.let { "https://www.youtube.com/watch?v=$it" }
}

/**
 * Discrete ambient events emitted by sensors, integrations, or the user.
 */
@Serializable
enum class AmbientEventType {
    @SerialName("weather_changed")
    WEATHER_CHANGED,

    @SerialName("sunset_started")
    SUNSET_STARTED,

    @SerialName("sunrise_started")
    SUNRISE_STARTED,

    @SerialName("snow_started")
    SNOW_STARTED,

    @SerialName("thunderstorm_started")
    THUNDERSTORM_STARTED,

    @SerialName("rain_started")
    RAIN_STARTED,

    @SerialName("fog_started")
    FOG_STARTED,

    @SerialName("severe_weather")
    SEVERE_WEATHER,

    @SerialName("holiday")
    HOLIDAY,

    @SerialName("room_occupied")
    ROOM_OCCUPIED,

    @SerialName("room_empty")
    ROOM_EMPTY,

    @SerialName("movie_finished")
    MOVIE_FINISHED,

    @SerialName("tv_episode_finished")
    TV_EPISODE_FINISHED,

    @SerialName("ha_custom")
    HA_CUSTOM,

    @SerialName("calendar_event")
    CALENDAR_EVENT,

    @SerialName("user_request")
    USER_REQUEST
}

/**
 * A time-bounded visual event that can influence ambient scene selection.
 *
 * Higher [priority] values take precedence when multiple events are active.
 */
@Serializable
data class AmbientVisualEvent(
    val type: AmbientEventType,
    val priority: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long,
    val categories: List<String> = emptyList(),
    val mood: String? = null,
    val reason: String
) {
    /**
     * Returns true when the event is still active at [nowEpochMs].
     */
    fun isActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs < expiresAtEpochMs
}

/**
 * Immutable snapshot of the ambient UI layer consumed by Compose.
 *
 * [activePlayerSlot] is 0 for player A and 1 for player B, enabling
 * cross-fade transitions between [currentCandidate] and [nextCandidate].
 */
data class AmbientUiState(
    val isAmbientActive: Boolean = false,
    val currentChannel: AmbientChannel = AmbientChannel.AUTO,
    val currentCandidate: AmbientCandidate? = null,
    val nextCandidate: AmbientCandidate? = null,
    val playerAAlpha: Float = 1f,
    val playerBAlpha: Float = 0f,
    val activePlayerSlot: Int = 0,
    val isOverlayVisible: Boolean = false,
    val overlayPositionIndex: Int = 0,
    val dimPercent: Int = 0,
    val isChannelPickerOpen: Boolean = false,
    val isQuickActionsOpen: Boolean = false,
    val errorMessage: String? = null
)
