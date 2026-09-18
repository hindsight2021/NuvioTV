package com.nuvio.tv.ambient.engine

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.context.AmbientRecentWatchContext
import com.nuvio.tv.ambient.context.AmbientTimeContext
import com.nuvio.tv.ambient.context.AmbientWeatherContext
import com.nuvio.tv.ambient.history.AmbientHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ranks [AmbientCandidate]s by computing a weighted score that blends intrinsic quality
 * metrics with contextual signals (time of day, weather, season, recent watch behavior)
 * and history-based penalties (cooldowns, failures, diversity guards).
 */
@Singleton
class AmbientRankingEngine @Inject constructor(
    private val timeContext: AmbientTimeContext,
    private val weatherContext: AmbientWeatherContext,
    private val recentWatchContext: AmbientRecentWatchContext,
    private val historyRepository: AmbientHistoryRepository
) {

    suspend fun rankCandidates(
        candidates: List<AmbientCandidate>,
        activeChannel: AmbientChannel,
        lastPlayedCandidate: AmbientCandidate? = null,
        nowMs: Long = System.currentTimeMillis()
    ): List<Pair<AmbientCandidate, Float>> {
        val timeBucket = timeContext.getCurrentTimeBucket()
        val timeTags = timeContext.getTimeTags(timeBucket).toSet()
        val weatherTags = weatherContext.getCurrentWeatherTags().toSet()
        val season = timeContext.getCurrentSeason()
        val seasonTags = timeContext.getSeasonTags(season).toSet()

        return candidates
            .map { candidate ->
                candidate to scoreCandidate(
                    candidate = candidate,
                    timeTags = timeTags,
                    weatherTags = weatherTags,
                    seasonTags = seasonTags,
                    lastPlayedCandidate = lastPlayedCandidate,
                    nowMs = nowMs
                )
            }
            .sortedByDescending { it.second }
    }

    private suspend fun scoreCandidate(
        candidate: AmbientCandidate,
        timeTags: Set<String>,
        weatherTags: Set<String>,
        seasonTags: Set<String>,
        lastPlayedCandidate: AmbientCandidate?,
        nowMs: Long
    ): Float {
        var score = (
            candidate.qualityScore * 0.4 +
                candidate.calmnessScore * 0.3 +
                candidate.reliabilityScore * 0.3
            ).toFloat()

        if (candidate.is4K) score += 0.25f
        if (candidate.isHdr) score += 0.15f

        if (candidate.timeTags.any { it in timeTags }) score += 0.30f
        if (candidate.weatherTags.any { it in weatherTags }) score += 0.25f
        if (candidate.seasonTags.any { it in seasonTags }) score += 0.20f

        score += recentWatchContext.getCategoryBonus(candidate.category, nowMs)

        // History-based cooldown: strongly discourage recently played candidates.
        val lastPlayed = historyRepository.getLastPlayedTime(candidate.id)
        if (lastPlayed != null) {
            val hoursSince = (nowMs - lastPlayed) / 3_600_000f
            when {
                hoursSince < 2.0f -> score -= 10.0f
                hoursSince < 168.0f -> {
                    val daysSince = hoursSince / 24.0f
                    score -= (7.0f - daysSince) * 0.15f
                }
            }
        }

        // Penalize candidates that have previously failed to play.
        if (historyRepository.getFailureCount(candidate.id) > 0) {
            score -= 5.0f
        }

        // Diversity guard: avoid back-to-back picks from the same location/country.
        if (lastPlayedCandidate != null) {
            if (lastPlayedCandidate.location != null &&
                lastPlayedCandidate.location.equals(candidate.location, ignoreCase = true)
            ) {
                score -= 0.50f
            }
            if (lastPlayedCandidate.country != null &&
                lastPlayedCandidate.country.equals(candidate.country, ignoreCase = true)
            ) {
                score -= 0.20f
            }
        }

        return score
    }
}
