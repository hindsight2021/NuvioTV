package com.nuvio.tv.core.playlist

import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.domain.model.Video
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Aggregated watch signal for a single episode, merged across Nuvio local/synced history,
 * Trakt, and Simkl.
 */
data class EpisodeWatchSignal(
    val season: Int,
    val episode: Int,
    val isCompleted: Boolean = false,
    val isInProgress: Boolean = false,
    val lastWatchedMs: Long = 0L
)

/** Record of a previously random-picked or shuffle-played episode. */
data class RandomPickRecord(
    val season: Int,
    val episode: Int,
    val pickedAtMs: Long
)

/** Result of smart random selection: the chosen episode plus the full smart-ordered queue. */
data class SmartRandomSelection(
    val chosen: Video,
    val orderedQueue: List<Video>
)

/**
 * Plans intelligent, non-repetitive random episode selection and smart-shuffle queues.
 *
 * Prioritizes unwatched episodes and episodes not watched recently, excludes recently random-picked
 * episodes across an adaptive cooldown window, favors stale over recent rewatches, and encourages
 * variety across seasons and episode numbers.
 */
object SmartRandomEpisodePlanner {

    internal const val RECENT_PICK_COOLDOWN_MS = 72L * 60L * 60L * 1000L
    private const val DAY_MS = 86_400_000.0
    private const val MAX_QUEUE_HEAD = 60
    private const val MIN_WEIGHT = 0.01

    internal const val TIER_2_STALE_MS = 30L * 24L * 60L * 60L * 1000L
    internal const val TIER_3_STALE_MS = 7L * 24L * 60L * 60L * 1000L
    internal const val TIER_4_STALE_MS = 24L * 60L * 60L * 1000L

    /** Merges watch signals across multiple sources (Nuvio, Trakt, Simkl) for the same episode. */
    fun mergeWatchSignals(
        existing: EpisodeWatchSignal?,
        incoming: EpisodeWatchSignal
    ): EpisodeWatchSignal {
        if (existing == null) return incoming
        val completed = existing.isCompleted || incoming.isCompleted
        val inProgress = !completed && (existing.isInProgress || incoming.isInProgress)
        return EpisodeWatchSignal(
            season = incoming.season,
            episode = incoming.episode,
            isCompleted = completed,
            isInProgress = inProgress,
            lastWatchedMs = maxOf(existing.lastWatchedMs, incoming.lastWatchedMs)
        )
    }

    /**
     * Filters and deduplicates episodes to those eligible for random episode playback.
     * Prefers season > 0, available != false, and aired releases, falling back safely if empty.
     */
    fun filterEligibleEpisodes(episodes: List<Video>): List<Video> {
        val withNumbers = episodes.filter { it.season != null && it.episode != null }
        if (withNumbers.isEmpty()) return emptyList()

        val regularSeasons = withNumbers.filter { (it.season ?: 0) > 0 }
        val pool = if (regularSeasons.isNotEmpty()) regularSeasons else withNumbers

        val deduped = LinkedHashMap<Pair<Int, Int>, Video>(pool.size)
        for (ep in pool) {
            val s = ep.season ?: continue
            val e = ep.episode ?: continue
            val key = s to e
            if (!deduped.containsKey(key)) {
                deduped[key] = ep
            }
        }
        val unique = deduped.values.toList()
        if (unique.isEmpty()) return emptyList()

        val preferred = unique.filter { ep ->
            ep.available != false && isEpisodeReleaseAired(ep.released) != false
        }
        return if (preferred.isNotEmpty()) preferred else unique
    }

    /**
     * Picks a single smart random episode avoiding recent watches and recent random picks.
     */
    fun pickEpisode(
        episodes: List<Video>,
        signalsByEpisode: Map<Pair<Int, Int>, EpisodeWatchSignal>,
        recentPicks: List<RandomPickRecord>,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): Video? {
        val eligible = filterEligibleEpisodes(episodes)
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible.first()

        val sortedPicks = recentPicks.sortedByDescending { it.pickedAtMs }
        val pool = selectNonRepeatingPool(eligible, sortedPicks, nowMs)
        val tiered = selectTier(pool, signalsByEpisode, nowMs)
        if (tiered.isEmpty()) return pool.firstOrNull()

        val distinctSeasons = tiered.mapNotNull { it.season }.distinct().size
        val lastPick = sortedPicks.getOrNull(0)
        val secondLastPick = sortedPicks.getOrNull(1)
        val priorPickKeys = sortedPicks.mapTo(HashSet(sortedPicks.size)) { it.season to it.episode }

        val weights = tiered.map { ep ->
            val season = ep.season ?: 0
            val episode = ep.episode ?: 0
            val key = season to episode
            var weight = baseWeight(signalsByEpisode[key], nowMs)

            if (key in priorPickKeys) {
                weight *= 0.45
            }
            if (lastPick != null) {
                if (distinctSeasons > 1 && season == lastPick.season) {
                    weight *= 0.25
                }
                if (season == lastPick.season && abs(episode - lastPick.episode) <= 2) {
                    weight *= 0.25
                }
            }
            if (secondLastPick != null && distinctSeasons > 1 && season == secondLastPick.season) {
                weight *= 0.60
            }
            weight.coerceAtLeast(MIN_WEIGHT)
        }

        return weightedSample(tiered, weights, random)
    }

    /**
     * Builds a smart-ordered shuffle queue where the chosen episode is index 0 and subsequent
     * episodes maintain unwatched/staleness priority, non-repetition, and season diversity.
     */
    fun buildSmartShuffleQueue(
        episodes: List<Video>,
        signalsByEpisode: Map<Pair<Int, Int>, EpisodeWatchSignal>,
        recentPicks: List<RandomPickRecord>,
        nowMs: Long = System.currentTimeMillis(),
        random: Random = Random.Default
    ): SmartRandomSelection? {
        val eligible = filterEligibleEpisodes(episodes)
        if (eligible.isEmpty()) return null

        val virtualPicks = recentPicks.sortedByDescending { it.pickedAtMs }.toMutableList()
        val remaining = eligible.toMutableList()
        val ordered = ArrayList<Video>(eligible.size)

        val headTarget = minOf(MAX_QUEUE_HEAD, eligible.size)
        while (ordered.size < headTarget && remaining.isNotEmpty()) {
            val pick = pickEpisode(remaining, signalsByEpisode, virtualPicks, nowMs, random) ?: break
            ordered.add(pick)
            remaining.removeAll { it.season == pick.season && it.episode == pick.episode }
            virtualPicks.add(
                0,
                RandomPickRecord(
                    season = pick.season ?: 0,
                    episode = pick.episode ?: 0,
                    pickedAtMs = nowMs
                )
            )
        }

        if (remaining.isNotEmpty()) {
            val tail = remaining.sortedWith(
                compareByDescending<Video> {
                    stalenessScore(signalsByEpisode[(it.season ?: 0) to (it.episode ?: 0)], nowMs)
                }.thenBy { random.nextDouble() }
            )
            ordered.addAll(tail)
        }

        val chosen = ordered.firstOrNull() ?: return null
        return SmartRandomSelection(chosen = chosen, orderedQueue = ordered)
    }

    private fun selectNonRepeatingPool(
        eligible: List<Video>,
        sortedPicks: List<RandomPickRecord>,
        nowMs: Long
    ): List<Video> {
        if (sortedPicks.isEmpty() || eligible.size <= 1) return eligible

        val eligibleKeys = eligible.mapTo(HashSet(eligible.size)) { (it.season ?: 0) to (it.episode ?: 0) }
        val maxExclude = eligible.size - 1
        val windowSize = (eligible.size * 0.40).toInt()
            .coerceIn(3, 20)
            .coerceAtMost(maxExclude)

        val excluded = LinkedHashSet<Pair<Int, Int>>()
        for (pick in sortedPicks) {
            if (excluded.size >= windowSize) break
            val key = pick.season to pick.episode
            if (key in eligibleKeys) excluded.add(key)
        }
        for (pick in sortedPicks) {
            if (excluded.size >= maxExclude) break
            if (nowMs - pick.pickedAtMs <= RECENT_PICK_COOLDOWN_MS) {
                val key = pick.season to pick.episode
                if (key in eligibleKeys) excluded.add(key)
            }
        }

        if (excluded.isNotEmpty()) {
            val filtered = eligible.filter { ((it.season ?: 0) to (it.episode ?: 0)) !in excluded }
            if (filtered.isNotEmpty()) return filtered
        }

        val mostRecent = sortedPicks.first()
        val mostRecentKey = mostRecent.season to mostRecent.episode
        val withoutImmediateLast = eligible.filter { ((it.season ?: 0) to (it.episode ?: 0)) != mostRecentKey }
        return if (withoutImmediateLast.isNotEmpty()) withoutImmediateLast else eligible
    }

    private fun selectTier(
        pool: List<Video>,
        signalsByEpisode: Map<Pair<Int, Int>, EpisodeWatchSignal>,
        nowMs: Long
    ): List<Video> {
        if (pool.isEmpty()) return emptyList()

        fun signalOf(ep: Video): EpisodeWatchSignal? =
            signalsByEpisode[(ep.season ?: 0) to (ep.episode ?: 0)]

        // Tier 1: Truly unwatched / untouched across all tracking sources.
        val tier1 = pool.filter { ep ->
            val s = signalOf(ep)
            s == null || (!s.isCompleted && !s.isInProgress && s.lastWatchedMs <= 0L)
        }
        if (tier1.isNotEmpty()) return tier1

        // Tier 2: Not watched in the past 30 days (or legacy mark without timestamp).
        val tier2 = pool.filter { ep ->
            val s = signalOf(ep) ?: return@filter false
            (s.lastWatchedMs > 0L && (nowMs - s.lastWatchedMs) > TIER_2_STALE_MS) ||
                (s.isCompleted && s.lastWatchedMs <= 0L)
        }
        if (tier2.isNotEmpty()) return tier2

        // Tier 3: Not watched in the past 7 days.
        val tier3 = pool.filter { ep ->
            val s = signalOf(ep) ?: return@filter false
            s.lastWatchedMs > 0L && (nowMs - s.lastWatchedMs) > TIER_3_STALE_MS
        }
        if (tier3.isNotEmpty()) return tier3

        // Tier 4: Not watched in the past 24 hours.
        val tier4 = pool.filter { ep ->
            val s = signalOf(ep) ?: return@filter false
            s.lastWatchedMs > 0L && (nowMs - s.lastWatchedMs) > TIER_4_STALE_MS
        }
        if (tier4.isNotEmpty()) return tier4

        // Tier 5: Everything watched within 24h -> exclude the single most recently watched episode.
        if (pool.size <= 1) return pool
        val mostRecentWatched = pool
            .mapNotNull { ep ->
                val s = signalOf(ep) ?: return@mapNotNull null
                if (s.lastWatchedMs > 0L) ep to s.lastWatchedMs else null
            }
            .maxByOrNull { it.second }
            ?.first
        return if (mostRecentWatched != null) pool.filter { it !== mostRecentWatched } else pool
    }

    private fun baseWeight(signal: EpisodeWatchSignal?, nowMs: Long): Double {
        if (signal == null) return 100.0
        if (!signal.isCompleted && !signal.isInProgress && signal.lastWatchedMs <= 0L) return 100.0
        if (signal.lastWatchedMs <= 0L) return 60.0
        val ageDays = ((nowMs - signal.lastWatchedMs).coerceAtLeast(0L) / DAY_MS)
        var weight = (1.0 + ageDays).pow(1.25).coerceIn(1.0, 500.0)
        if (signal.isInProgress && !signal.isCompleted) {
            weight *= 0.6
        }
        return weight
    }

    private fun stalenessScore(signal: EpisodeWatchSignal?, nowMs: Long): Double {
        if (signal == null) return Double.MAX_VALUE
        if (!signal.isCompleted && !signal.isInProgress && signal.lastWatchedMs <= 0L) return Double.MAX_VALUE
        if (signal.lastWatchedMs <= 0L) return Double.MAX_VALUE / 2.0
        return (nowMs - signal.lastWatchedMs).toDouble()
    }

    private fun weightedSample(
        items: List<Video>,
        weights: List<Double>,
        random: Random
    ): Video? {
        if (items.isEmpty()) return null
        val total = weights.sum()
        if (total <= 0.0) return items[random.nextInt(items.size)]
        var cursor = random.nextDouble() * total
        for (i in items.indices) {
            cursor -= weights[i]
            if (cursor <= 0.0) return items[i]
        }
        return items.last()
    }
}
