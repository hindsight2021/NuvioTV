package com.nuvio.tv.ambient.source

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientSeason
import com.nuvio.tv.ambient.AmbientTimeBucket
import com.nuvio.tv.ambient.seeds.AerialWorldSeeds
import com.nuvio.tv.ambient.seeds.CityNightSeeds
import com.nuvio.tv.ambient.seeds.FireplaceCozySeeds
import com.nuvio.tv.ambient.seeds.NatureOceanSeeds
import com.nuvio.tv.ambient.seeds.SpaceSeeds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ambient source backed by curated 4K/HDR YouTube videos.
 */
@Singleton
class YouTubeAmbientSource @Inject constructor() : AmbientSource {

    override val name: String = "youtube_curated"

    private val allCandidates: List<AmbientCandidate> by lazy {
        buildList {
            addAll(AerialWorldSeeds.items)
            addAll(CityNightSeeds.items)
            addAll(SpaceSeeds.items)
            addAll(NatureOceanSeeds.items)
            addAll(FireplaceCozySeeds.items)
        }
    }

    private val smallPoolThreshold: Int = 8

    override suspend fun getCandidates(
        channel: AmbientChannel,
        timeBucket: AmbientTimeBucket,
        season: AmbientSeason
    ): List<AmbientCandidate> {
        return when (channel) {
            AmbientChannel.FOR_YOU -> forYouCandidates(timeBucket, season)
            else -> channelCandidates(channel, timeBucket, season)
        }
    }

    private fun forYouCandidates(
        timeBucket: AmbientTimeBucket,
        season: AmbientSeason
    ): List<AmbientCandidate> {
        val timeTag = timeBucket.name.lowercase()
        val seasonTag = season.name.lowercase()
        val matched = allCandidates.filter { candidate ->
            candidate.timeTags.contains(timeTag) ||
                candidate.seasonTags.contains(seasonTag)
        }
        return if (matched.size >= smallPoolThreshold) matched else allCandidates
    }

    private fun channelCandidates(
        channel: AmbientChannel,
        timeBucket: AmbientTimeBucket,
        season: AmbientSeason
    ): List<AmbientCandidate> {
        val byChannel = allCandidates.filter { matchesChannel(it, channel) }

        val timeTag = timeBucket.name.lowercase()
        val seasonTag = season.name.lowercase()
        val refined = byChannel.filter { candidate ->
            candidate.timeTags.contains(timeTag) ||
                candidate.seasonTags.contains(seasonTag)
        }

        val pool = refined.ifEmpty { byChannel }
        return pool.ifEmpty { allCandidates }
    }

    private fun matchesChannel(candidate: AmbientCandidate, channel: AmbientChannel): Boolean {
        val category = candidate.category.lowercase()

        return when (channel) {
            AmbientChannel.FOR_YOU -> true
            AmbientChannel.AFTER_DARK ->
                category == "after_dark" ||
                    candidate.timeTags.any { it == "night" || it == "late_night" }
            AmbientChannel.WEATHER ->
                category == "weather" || candidate.weatherTags.isNotEmpty()
            AmbientChannel.FIREPLACE -> category == "fireplace"
            AmbientChannel.AERIAL -> category == "aerial"
            AmbientChannel.CITIES -> category == "cities"
            AmbientChannel.SPACE -> category == "space"
            AmbientChannel.NATURE -> category == "nature"
            AmbientChannel.CINEMA -> category == "cinema"
        }
    }
}
