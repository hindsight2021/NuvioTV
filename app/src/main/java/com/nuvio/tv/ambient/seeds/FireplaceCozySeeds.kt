package com.nuvio.tv.ambient.seeds

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Curated ambient seed entries for the [AmbientChannel.FIREPLACE] and
 * [AmbientChannel.WEATHER] channels.
 */
object FireplaceCozySeeds {

    private const val PROVIDER = "youtube"
    private const val FPS = 60
    private const val DURATION_SECONDS = 14400L
    private const val QUALITY_SCORE = 1.0
    private const val CALMNESS_SCORE = 0.98
    private const val RELIABILITY_SCORE = 1.0

    val items: List<AmbientCandidate> = listOf(
        AmbientCandidate(
            id = "yt_L_LUpnjgPso",
            provider = PROVIDER,
            youtubeVideoId = "L_LUpnjgPso",
            title = "4K Fireplace in Luxury Mountain Lodge with Falling Snow",
            location = "Aspen, Colorado",
            country = "United States",
            category = "fireplace",
            subcategories = listOf("cabin", "fireplace", "snow", "cozy"),
            weatherTags = listOf("snow", "cold"),
            seasonTags = listOf("winter", "autumn"),
            timeTags = listOf("night", "evening", "late_night"),
            is4K = true,
            isHdr = true,
            fps = FPS,
            durationSeconds = DURATION_SECONDS,
            qualityScore = QUALITY_SCORE,
            calmnessScore = CALMNESS_SCORE,
            reliabilityScore = RELIABILITY_SCORE
        ),
        AmbientCandidate(
            id = "yt_UG_X_7g63rY",
            provider = PROVIDER,
            youtubeVideoId = "UG_X_7g63rY",
            title = "Crackling Fireplace in Cozy Winter Cabin • 4K UHD",
            location = "Swiss Chalet",
            country = "Switzerland",
            category = "fireplace",
            subcategories = listOf("hearth", "flames", "cozy", "winter"),
            weatherTags = listOf("snow", "rain", "cold"),
            seasonTags = listOf("winter"),
            timeTags = listOf("night", "evening", "late_night"),
            is4K = true,
            isHdr = true,
            fps = FPS,
            durationSeconds = DURATION_SECONDS,
            qualityScore = QUALITY_SCORE,
            calmnessScore = CALMNESS_SCORE,
            reliabilityScore = RELIABILITY_SCORE
        ),
        AmbientCandidate(
            id = "yt_E__RvL41RF0",
            provider = PROVIDER,
            youtubeVideoId = "E__RvL41RF0",
            title = "Winter Snowstorm & Falling Snow Ambiance • 4K",
            location = "Manhattan",
            country = "United States",
            category = "weather",
            subcategories = listOf("snow", "blizzard", "urban"),
            weatherTags = listOf("snow", "blizzard"),
            seasonTags = listOf("winter"),
            timeTags = listOf("day", "evening", "night"),
            is4K = true,
            isHdr = true,
            fps = FPS,
            durationSeconds = DURATION_SECONDS,
            qualityScore = QUALITY_SCORE,
            calmnessScore = CALMNESS_SCORE,
            reliabilityScore = RELIABILITY_SCORE
        ),
        AmbientCandidate(
            id = "yt_YqzdaK2dzTY",
            provider = PROVIDER,
            youtubeVideoId = "YqzdaK2dzTY",
            title = "Cinematic Heavy Rain & Distant Thunder • 4K",
            location = "New York",
            country = "United States",
            category = "weather",
            subcategories = listOf("rain", "thunder", "storm"),
            weatherTags = listOf("rain", "thunderstorm"),
            seasonTags = listOf("summer", "spring", "autumn"),
            timeTags = listOf("evening", "night", "late_night"),
            is4K = true,
            isHdr = true,
            fps = FPS,
            durationSeconds = DURATION_SECONDS,
            qualityScore = QUALITY_SCORE,
            calmnessScore = CALMNESS_SCORE,
            reliabilityScore = RELIABILITY_SCORE
        )
    )
}
