package com.nuvio.tv.ambient.engine

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.context.AmbientTimeContext
import com.nuvio.tv.ambient.history.AmbientHistoryRepository
import com.nuvio.tv.ambient.history.AmbientPreferencesDataStore
import com.nuvio.tv.ambient.source.NuvioCinemaAmbientSource
import com.nuvio.tv.ambient.source.YouTubeAmbientSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Resolves ambient feed candidates by aggregating sources, filtering disliked content,
 * and ranking through the intelligent scoring engine.
 */
@Singleton
class AmbientFeedResolver @Inject constructor(
    private val youTubeSource: YouTubeAmbientSource,
    private val cinemaSource: NuvioCinemaAmbientSource,
    private val rankingEngine: AmbientRankingEngine,
    private val timeContext: AmbientTimeContext,
    private val preferencesDataStore: AmbientPreferencesDataStore,
    private val historyRepository: AmbientHistoryRepository
) {

    suspend fun resolveNextCandidate(
        channel: AmbientChannel,
        lastPlayedCandidate: AmbientCandidate? = null
    ): AmbientCandidate? {
        val pool = buildFilteredPool(channel)
        if (pool.isEmpty()) return null

        val ranked = rankingEngine.rankCandidates(pool, channel, lastPlayedCandidate)
        if (ranked.isEmpty()) return pool.firstOrNull()

        val topCandidates = ranked.take(TOP_CANDIDATE_WINDOW).map { it.first }
        return selectWeighted(topCandidates)
    }

    suspend fun resolveCandidates(channel: AmbientChannel): List<AmbientCandidate> {
        val pool = buildFilteredPool(channel)
        if (pool.isEmpty()) return emptyList()
        return rankingEngine.rankCandidates(pool, channel, lastPlayedCandidate = null).map { it.first }
    }

    private suspend fun buildFilteredPool(channel: AmbientChannel): List<AmbientCandidate> {
        val timeBucket = timeContext.getCurrentTimeBucket()
        val season = timeContext.getCurrentSeason()

        val youTubeCandidates = youTubeSource.getCandidates(channel, timeBucket, season)
        val cinemaCandidates = cinemaSource.getCandidates(channel, timeBucket, season)

        val combined = youTubeCandidates + cinemaCandidates
        if (combined.isEmpty()) return emptyList()

        val dislikedCategories = preferencesDataStore.getDislikedCategories()

        val filtered = combined.filterNot { candidate ->
            dislikedCategories.contains(candidate.category.lowercase()) ||
                historyRepository.isDisliked(candidate.id)
        }

        return filtered.ifEmpty { combined }
    }

    private fun selectWeighted(candidates: List<AmbientCandidate>): AmbientCandidate? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val weights = IntArray(candidates.size) { index -> candidates.size - index }
        val totalWeight = weights.sum()
        var roll = Random.nextInt(totalWeight)

        for (i in candidates.indices) {
            roll -= weights[i]
            if (roll < 0) return candidates[i]
        }
        return candidates.first()
    }

    companion object {
        const val TOP_CANDIDATE_WINDOW = 3
    }
}
