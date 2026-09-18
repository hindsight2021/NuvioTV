package com.nuvio.tv.ambient.source

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientSeason
import com.nuvio.tv.ambient.AmbientTimeBucket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curated ambient source providing cinematic landscape and aesthetic scenes.
 */
@Singleton
class NuvioCinemaAmbientSource @Inject constructor() : AmbientSource {

    override val name: String = "nuvio_cinema"

    override suspend fun getCandidates(
        channel: AmbientChannel,
        timeBucket: AmbientTimeBucket,
        season: AmbientSeason
    ): List<AmbientCandidate> {
        if (channel != AmbientChannel.CINEMA && channel != AmbientChannel.FOR_YOU) {
            return emptyList()
        }
        return CURATED_CINEMA_CANDIDATES
    }

    private companion object {
        private const val CATEGORY = "cinema"
        private const val QUALITY_SCORE = 1.0

        private val CURATED_CINEMA_CANDIDATES: List<AmbientCandidate> = listOf(
            AmbientCandidate(
                id = "cinema_interstellar_cosmic",
                provider = "youtube",
                youtubeVideoId = "Slx91ASCiXw",
                title = "Interstellar Cosmic Travel • 4K",
                location = "Deep Cosmos",
                category = CATEGORY,
                subcategories = listOf("space", "cinematic", "nebula"),
                timeTags = listOf("night", "late_night"),
                is4K = true,
                isHdr = true,
                fps = 60,
                durationSeconds = 7200L,
                qualityScore = QUALITY_SCORE,
                calmnessScore = 0.95,
                reliabilityScore = 1.0
            ),
            AmbientCandidate(
                id = "cinema_blade_runner_neon",
                provider = "youtube",
                youtubeVideoId = "lp_9JKEYFFQ",
                title = "Neo-Tokyo Rainy Neon Ambiance • 4K",
                location = "Tokyo",
                category = CATEGORY,
                subcategories = listOf("neon", "cyberpunk", "rain"),
                weatherTags = listOf("rain"),
                timeTags = listOf("night", "late_night"),
                is4K = true,
                isHdr = true,
                fps = 60,
                durationSeconds = 10800L,
                qualityScore = QUALITY_SCORE,
                calmnessScore = 0.95,
                reliabilityScore = 1.0
            ),
            AmbientCandidate(
                id = "cinema_grand_nature",
                provider = "youtube",
                youtubeVideoId = "5Vz1y7P_a0s",
                title = "Cinematic Canadian Wilderness • 4K HDR",
                location = "Lake Louise",
                country = "Canada",
                category = CATEGORY,
                subcategories = listOf("mountains", "lakes", "cinematic"),
                weatherTags = listOf("clear", "sunny"),
                timeTags = listOf("morning", "day", "golden_hour"),
                is4K = true,
                isHdr = true,
                fps = 60,
                durationSeconds = 7200L,
                qualityScore = QUALITY_SCORE,
                calmnessScore = 0.96,
                reliabilityScore = 1.0
            )
        )
    }
}
