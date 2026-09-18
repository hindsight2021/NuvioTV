package com.nuvio.tv.ambient.seeds

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Curated ambient nature seed content focused on ocean, lakes, fjords, and coastal scenery.
 */
object NatureOceanSeeds {

    private const val DEFAULT_DURATION_SECONDS = 7200L
    private const val DEFAULT_FPS = 60
    private const val DEFAULT_QUALITY_SCORE = 1.0
    private const val DEFAULT_CALMNESS_SCORE = 0.96
    private const val DEFAULT_RELIABILITY_SCORE = 1.0

    val items: List<AmbientCandidate> = listOf(
        createCandidate(
            youtubeId = "5Vz1y7P_a0s",
            title = "Canada 4K HDR – Lake Louise, Waterfalls & Forests",
            location = "Lake Louise",
            country = "Canada",
            subcategories = listOf("lakes", "waterfalls", "forests"),
            weatherTags = listOf("clear", "sunny", "clouds"),
            seasonTags = listOf("summer", "spring", "autumn"),
            timeTags = listOf("day", "morning", "afternoon"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "9v0oVzLqV5Q",
            title = "Canada's Pristine Nature • 4K UHD Glacial Waters",
            location = "Alberta",
            country = "Canada",
            subcategories = listOf("rivers", "mountains", "forest"),
            weatherTags = listOf("clear", "sunny"),
            seasonTags = listOf("summer", "spring"),
            timeTags = listOf("day", "afternoon"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "kYJzXm-k3z0",
            title = "Newfoundland 4K - Ocean Fjords & Coastal Cliffs",
            location = "Newfoundland",
            country = "Canada",
            subcategories = listOf("ocean", "cliffs", "fjords"),
            weatherTags = listOf("clouds", "fog", "clear"),
            seasonTags = listOf("summer", "autumn"),
            timeTags = listOf("day", "morning", "golden_hour"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "Gf5rSeTKpj4",
            title = "Banff National Park • 4K Crystal Alpine Lakes",
            location = "Banff",
            country = "Canada",
            subcategories = listOf("lakes", "alps", "reflections"),
            weatherTags = listOf("clear", "sunny"),
            seasonTags = listOf("summer", "autumn"),
            timeTags = listOf("morning", "day", "golden_hour"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "CxwJrzEdw1U",
            title = "Norway Fjord Waters & Mountain Reflections • 4K",
            location = "Lofoten",
            country = "Norway",
            subcategories = listOf("fjords", "ocean", "mountains"),
            weatherTags = listOf("clear", "clouds"),
            seasonTags = listOf("summer", "autumn"),
            timeTags = listOf("day", "evening", "golden_hour"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "bQmzk05I3nw",
            title = "Great Barrier Reef & Coral Seas • 4K",
            location = "Queensland",
            country = "Australia",
            subcategories = listOf("ocean", "coral_reef", "marine_life"),
            weatherTags = listOf("clear", "sunny"),
            seasonTags = listOf("summer", "spring"),
            timeTags = listOf("day", "afternoon"),
            isHdr = true
        ),
        createCandidate(
            youtubeId = "AybNYgQi9hY",
            title = "Iceland Glacial Cascades & Volcanic Rivers • 4K",
            location = "Skógafoss",
            country = "Iceland",
            subcategories = listOf("waterfalls", "rivers", "glaciers"),
            weatherTags = listOf("clouds", "mist", "rain"),
            seasonTags = listOf("summer", "autumn", "winter"),
            timeTags = listOf("day", "afternoon"),
            isHdr = true
        )
    )

    private fun createCandidate(
        youtubeId: String,
        title: String,
        location: String,
        country: String,
        subcategories: List<String>,
        weatherTags: List<String>,
        seasonTags: List<String>,
        timeTags: List<String>,
        isHdr: Boolean
    ): AmbientCandidate = AmbientCandidate(
        id = "yt_$youtubeId",
        provider = "youtube",
        youtubeVideoId = youtubeId,
        title = title,
        location = location,
        country = country,
        category = "nature",
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
