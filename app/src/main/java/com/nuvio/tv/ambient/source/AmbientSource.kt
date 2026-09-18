package com.nuvio.tv.ambient.source

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientSeason
import com.nuvio.tv.ambient.AmbientTimeBucket

/**
 * Common contract for candidate visual ambient sources.
 */
interface AmbientSource {
    val name: String

    suspend fun getCandidates(
        channel: AmbientChannel,
        timeBucket: AmbientTimeBucket,
        season: AmbientSeason
    ): List<AmbientCandidate>
}
