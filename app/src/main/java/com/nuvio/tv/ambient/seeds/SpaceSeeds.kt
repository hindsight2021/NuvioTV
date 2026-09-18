package com.nuvio.tv.ambient.seeds

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Curated ambient video seeds for the [AmbientChannel.SPACE] channel.
 */
object SpaceSeeds {

    private const val DEFAULT_DURATION_SECONDS = 7200L
    private const val DEFAULT_FPS = 60
    private const val DEFAULT_QUALITY_SCORE = 1.0
    private const val DEFAULT_CALMNESS_SCORE = 0.98
    private const val DEFAULT_RELIABILITY_SCORE = 1.0

    private fun spaceCandidate(
        youtubeId: String,
        title: String,
        location: String,
        country: String,
        subcategories: List<String>,
        timeTags: List<String>,
        isHdr: Boolean
    ): AmbientCandidate = AmbientCandidate(
        id = "yt_$youtubeId",
        provider = "youtube",
        youtubeVideoId = youtubeId,
        title = title,
        location = location,
        country = country,
        category = "space",
        subcategories = subcategories,
        weatherTags = emptyList(),
        seasonTags = emptyList(),
        timeTags = timeTags,
        is4K = true,
        isHdr = isHdr,
        fps = DEFAULT_FPS,
        durationSeconds = DEFAULT_DURATION_SECONDS,
        qualityScore = DEFAULT_QUALITY_SCORE,
        calmnessScore = DEFAULT_CALMNESS_SCORE,
        reliabilityScore = DEFAULT_RELIABILITY_SCORE
    )

    val items: List<AmbientCandidate> = listOf(
        spaceCandidate(
            youtubeId = "6tmbeLTHC_0",
            title = "Thermonuclear Art – The Sun In Ultra-HD (4K)",
            location = "Sun (SDO)",
            country = "Space",
            subcategories = listOf("sun", "solar_flares", "nasa"),
            timeTags = listOf("day", "night", "golden_hour", "morning", "afternoon"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "84Jkt5RwRUc",
            title = "Earth & Life in Orbit • 4K Ultra HD",
            location = "Low Earth Orbit",
            country = "Space",
            subcategories = listOf("earth", "iss", "orbit"),
            timeTags = listOf("night", "late_night", "evening", "day"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "F0S12T4R7q8",
            title = "A Full Year in Orbit • 4K Continuous ISS Earth Views",
            location = "International Space Station",
            country = "Space",
            subcategories = listOf("earth", "clouds", "continents"),
            timeTags = listOf("night", "late_night", "day"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "gT88k2J4uQc",
            title = "AURORA - ISS Space Timelapse in 4K",
            location = "Southern & Northern Auroras",
            country = "Space",
            subcategories = listOf("aurora", "earth", "night_lights"),
            timeTags = listOf("night", "late_night"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "FspEJ83xMTg",
            title = "Earth from Space in 4K • Orbital Digital Window",
            location = "Earth Orbit",
            country = "Space",
            subcategories = listOf("earth", "atmosphere", "blue_marble"),
            timeTags = listOf("day", "night", "late_night"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "R9U0s8h1v5A",
            title = "Earth from Space: Night & Day Terminus • 4K",
            location = "Earth Orbit",
            country = "Space",
            subcategories = listOf("day_night", "sunset_from_space", "oceans"),
            timeTags = listOf("night", "golden_hour", "evening"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "Slx91ASCiXw",
            title = "The Pillars of Creation: 3D Cosmic Exploration • 4K",
            location = "Eagle Nebula",
            country = "Deep Space",
            subcategories = listOf("pillars_of_creation", "jwst", "nebula"),
            timeTags = listOf("night", "late_night"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "A_s4T3-7jJ4",
            title = "James Webb Telescope: Deep Cosmic Space & Nebulae • 4K",
            location = "Deep Space",
            country = "Space",
            subcategories = listOf("galaxies", "nebulae", "deep_field"),
            timeTags = listOf("night", "late_night"),
            isHdr = true
        ),
        spaceCandidate(
            youtubeId = "0_0HJbNzGb8",
            title = "4K Cosmic Deep Space Journey • James Webb Imagery",
            location = "Deep Space",
            country = "Space",
            subcategories = listOf("stars", "cosmos", "jwst"),
            timeTags = listOf("night", "late_night"),
            isHdr = true
        )
    )
}
