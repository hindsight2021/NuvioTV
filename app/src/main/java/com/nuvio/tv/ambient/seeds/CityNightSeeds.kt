package com.nuvio.tv.ambient.seeds

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Curated ambient video seeds for city and after-dark channels.
 */
object CityNightSeeds {

    private const val DEFAULT_DURATION_SECONDS = 7200L
    private const val DEFAULT_FPS = 60
    private const val DEFAULT_QUALITY_SCORE = 1.0
    private const val DEFAULT_CALMNESS_SCORE = 0.95
    private const val DEFAULT_RELIABILITY_SCORE = 1.0

    val items: List<AmbientCandidate> = listOf(
        candidate(
            youtubeId = "kOn56cUQYLk",
            title = "Tokyo Shinjuku Rainy Night Walk • 4K HDR",
            location = "Tokyo",
            country = "Japan",
            category = "cities",
            subcategories = listOf("shinjuku", "neon", "night", "rain"),
            weatherTags = listOf("rain", "clouds"),
            timeTags = listOf("night", "late_night"),
            isHdr = true
        ),
        candidate(
            youtubeId = "vvWShDvADgU",
            title = "Tokyo Shibuya to Harajuku Night Walk • 4K HDR",
            location = "Tokyo",
            country = "Japan",
            category = "cities",
            subcategories = listOf("shibuya", "night", "neon"),
            weatherTags = listOf("rain", "clear"),
            timeTags = listOf("night", "evening"),
            isHdr = true
        ),
        candidate(
            youtubeId = "stxazLpIc2Q",
            title = "Tokyo Sunset to Twilight Walk • 4K HDR",
            location = "Tokyo",
            country = "Japan",
            category = "cities",
            subcategories = listOf("twilight", "sunset", "neon"),
            timeTags = listOf("golden_hour", "evening"),
            isHdr = true
        ),
        candidate(
            youtubeId = "lp_9JKEYFFQ",
            title = "Tokyo Midnight Neon Rain Walk • 4K HDR",
            location = "Tokyo",
            country = "Japan",
            category = "after_dark",
            subcategories = listOf("rain", "neon", "midnight"),
            weatherTags = listOf("rain"),
            timeTags = listOf("late_night", "night"),
            isHdr = true
        ),
        candidate(
            youtubeId = "YqzdaK2dzTY",
            title = "New York Manhattan Rainy Evening Walk • 4K",
            location = "New York",
            country = "United States",
            category = "cities",
            subcategories = listOf("manhattan", "rain", "skylines"),
            weatherTags = listOf("rain", "thunderstorm"),
            timeTags = listOf("evening", "night"),
            isHdr = true
        ),
        candidate(
            youtubeId = "3koOEPntvqk",
            title = "New York Greenwich Village Morning Atmosphere • 4K",
            location = "New York",
            country = "United States",
            category = "cities",
            subcategories = listOf("greenwich_village", "streets", "morning"),
            weatherTags = listOf("clear", "sunny"),
            timeTags = listOf("morning", "day"),
            isHdr = false
        ),
        candidate(
            youtubeId = "E__RvL41RF0",
            title = "New York City Winter Snowstorm Walk • 4K",
            location = "New York",
            country = "United States",
            category = "weather",
            subcategories = listOf("snow", "blizzard", "manhattan"),
            weatherTags = listOf("snow"),
            timeTags = listOf("day", "evening"),
            seasonTags = listOf("winter"),
            isHdr = true
        ),
        candidate(
            youtubeId = "BeHPMEmGozg",
            title = "Central Park Autumn Foliage Walk • 4K HDR",
            location = "New York",
            country = "United States",
            category = "nature",
            subcategories = listOf("central_park", "autumn", "foliage"),
            weatherTags = listOf("clear", "sunny"),
            seasonTags = listOf("autumn"),
            timeTags = listOf("morning", "afternoon", "golden_hour"),
            isHdr = true
        )
    )

    private fun candidate(
        youtubeId: String,
        title: String,
        location: String,
        country: String,
        category: String,
        subcategories: List<String> = emptyList(),
        weatherTags: List<String> = emptyList(),
        seasonTags: List<String> = emptyList(),
        timeTags: List<String> = emptyList(),
        isHdr: Boolean
    ): AmbientCandidate = AmbientCandidate(
        id = "yt_$youtubeId",
        provider = "youtube",
        youtubeVideoId = youtubeId,
        title = title,
        location = location,
        country = country,
        category = category,
        subcategories = subcategories,
        weatherTags = weatherTags,
        seasonTags = seasonTags,
        timeTags = timeTags,
        is4K = true,
        isHdr = isHdr,
        fps = DEFAULT_FPS,
        durationSeconds = DEFAULT_DURATION_SECONDS,
        qualityScore = DEFAULT_QUALITY_SCORE,
        calmnessScore = DEFAULT_CALMNESS_SCORE,
        reliabilityScore = DEFAULT_RELIABILITY_SCORE
    )
}
