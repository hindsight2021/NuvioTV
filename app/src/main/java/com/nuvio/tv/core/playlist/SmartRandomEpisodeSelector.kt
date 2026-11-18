package com.nuvio.tv.core.playlist

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.data.local.RandomEpisodeHistoryStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects intelligent random episodes and smart-shuffle queues by combining:
 * - Nuvio local & synced watch progress + watched items
 * - Connected Trakt progress & episode history
 * - Connected Simkl progress & episode history
 * - Persistent per-profile random-pick history
 */
@Singleton
class SmartRandomEpisodeSelector @Inject constructor(
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val trackingProgressProviders: TrackingProgressProviderRegistry,
    private val randomEpisodeHistoryStore: RandomEpisodeHistoryStore
) {
    private class EpisodeSignals {
        private val map = LinkedHashMap<Pair<Int, Int>, EpisodeWatchSignal>()

        @Synchronized
        fun mergeWatchProgress(season: Int, episode: Int, wp: WatchProgress) {
            val key = season to episode
            val completed = wp.isCompleted()
            val inProgress = wp.isInProgress()
            val incoming = EpisodeWatchSignal(
                season = season,
                episode = episode,
                isCompleted = completed,
                isInProgress = inProgress,
                lastWatchedMs = wp.lastWatched
            )
            map[key] = SmartRandomEpisodePlanner.mergeWatchSignals(map[key], incoming)
        }

        @Synchronized
        fun mergeWatchedItem(season: Int, episode: Int, item: WatchedItem) {
            markCompleted(season, episode, item.watchedAt)
        }

        @Synchronized
        fun markCompleted(season: Int, episode: Int, lastWatchedMs: Long) {
            val key = season to episode
            val incoming = EpisodeWatchSignal(
                season = season,
                episode = episode,
                isCompleted = true,
                isInProgress = false,
                lastWatchedMs = lastWatchedMs
            )
            map[key] = SmartRandomEpisodePlanner.mergeWatchSignals(map[key], incoming)
        }

        @Synchronized
        fun snapshot(): Map<Pair<Int, Int>, EpisodeWatchSignal> = map.toMap()
    }

    suspend fun selectRandomAndQueue(
        contentId: String,
        episodes: List<Video>,
        knownEpisodeProgress: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
        knownWatchedEpisodes: Set<Pair<Int, Int>> = emptySet(),
        recordPick: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): SmartRandomSelection? {
        val eligible = SmartRandomEpisodePlanner.filterEligibleEpisodes(episodes)
        if (eligible.isEmpty()) return null

        val providers = trackingProgressProviders.providers()
        val authenticatedProviders = supervisorScope {
            providers.map { provider ->
                async {
                    val isAuth = withTimeoutOrNull(800L) {
                        safeProviderCall { provider.isAuthenticated.first() }
                    } == true
                    if (isAuth) provider else null
                }
            }.awaitAll().filterNotNull()
        }

        val candidateContentIds = expandCandidateIdsWithSiblings(
            baseIds = buildCandidateContentIds(contentId, episodes),
            authenticatedProviders = authenticatedProviders
        )

        val signals = EpisodeSignals()

        supervisorScope {
            val jobs = buildList {
                // 1. Nuvio local / cloud-synced episode progress
                add(async {
                    safeCall {
                        val progress = watchProgressPreferences.allProgress.first()
                        for (wp in progress) {
                            val normalized = normalizeKey(wp.contentId) ?: continue
                            if (normalized !in candidateContentIds) continue
                            val season = wp.season ?: continue
                            val episode = wp.episode ?: continue
                            signals.mergeWatchProgress(season, episode, wp)
                        }
                    }
                })

                // 2. Nuvio local / cloud-synced watched items
                add(async {
                    safeCall {
                        val items = watchedItemsPreferences.allItems.first()
                        for (item in items) {
                            val normalized = normalizeKey(item.contentId) ?: continue
                            if (normalized !in candidateContentIds) continue
                            val season = item.season ?: continue
                            val episode = item.episode ?: continue
                            signals.mergeWatchedItem(season, episode, item)
                        }
                    }
                })

                // 3. Connected Trakt and/or Simkl tracking providers
                for (provider in authenticatedProviders) {
                    add(async {
                        withTimeoutOrNull(1_500L) {
                            collectProviderSignals(
                                provider = provider,
                                contentId = contentId,
                                candidateContentIds = candidateContentIds,
                                eligible = eligible,
                                signals = signals
                            )
                        }
                    })
                }
            }
            jobs.awaitAll()
        }

        // 4. Caller-provided UI progress/watched state
        for ((pair, wp) in knownEpisodeProgress) {
            signals.mergeWatchProgress(pair.first, pair.second, wp)
        }
        for ((season, episode) in knownWatchedEpisodes) {
            signals.markCompleted(season, episode, lastWatchedMs = 0L)
        }

        val recentPicks = safeCall {
            randomEpisodeHistoryStore.getRecentPicks(candidateContentIds)
        }.orEmpty()

        val selection = SmartRandomEpisodePlanner.buildSmartShuffleQueue(
            episodes = eligible,
            signalsByEpisode = signals.snapshot(),
            recentPicks = recentPicks,
            nowMs = nowMs
        )

        if (recordPick && selection != null) {
            val chosen = selection.chosen
            val season = chosen.season
            val episode = chosen.episode
            if (season != null && episode != null) {
                safeCall {
                    randomEpisodeHistoryStore.recordPick(
                        contentId = contentId,
                        season = season,
                        episode = episode,
                        siblingIds = candidateContentIds,
                        nowMs = nowMs
                    )
                }
            }
        }

        return selection
    }

    private suspend fun expandCandidateIdsWithSiblings(
        baseIds: Set<String>,
        authenticatedProviders: List<TrackingProgressProvider>
    ): Set<String> {
        if (authenticatedProviders.isEmpty()) return baseIds
        val expanded = LinkedHashSet<String>(baseIds)
        val siblingMaps = supervisorScope {
            authenticatedProviders.map { provider ->
                async {
                    withTimeoutOrNull(800L) {
                        safeProviderCall { provider.showIdSiblings() }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        for (siblings in siblingMaps) {
            for ((rawKey, siblingSet) in siblings) {
                val normKey = normalizeKey(rawKey) ?: continue
                val normSiblings = siblingSet.mapNotNull(::normalizeKey)
                if (normKey in expanded || normSiblings.any { it in expanded }) {
                    expanded.add(normKey)
                    expanded.addAll(normSiblings)
                }
            }
        }
        return expanded
    }

    private suspend fun collectProviderSignals(
        provider: TrackingProgressProvider,
        contentId: String,
        candidateContentIds: Set<String>,
        eligible: List<Video>,
        signals: EpisodeSignals
    ) {
        safeProviderCall {
            val primary = provider.episodeProgress(contentId).first()
            if (primary.isNotEmpty()) {
                for ((pair, wp) in primary) {
                    signals.mergeWatchProgress(pair.first, pair.second, wp)
                }
            } else {
                val normalizedPrimary = normalizeKey(contentId)
                val firstSibling = candidateContentIds.firstOrNull { it != normalizedPrimary }
                if (firstSibling != null) {
                    val siblingProgress = provider.episodeProgress(firstSibling).first()
                    for ((pair, wp) in siblingProgress) {
                        signals.mergeWatchProgress(pair.first, pair.second, wp)
                    }
                }
            }
        }

        safeProviderCall {
            val items = provider.watchedItems.first()
            for (item in items) {
                val normalized = normalizeKey(item.contentId) ?: continue
                if (normalized !in candidateContentIds) continue
                val season = item.season ?: continue
                val episode = item.episode ?: continue
                signals.mergeWatchedItem(season, episode, item)
            }
        }

        safeProviderCall {
            val progress = provider.allProgress.first()
            for (wp in progress) {
                val normalized = normalizeKey(wp.contentId) ?: continue
                if (normalized !in candidateContentIds) continue
                val season = wp.season ?: continue
                val episode = wp.episode ?: continue
                signals.mergeWatchProgress(season, episode, wp)
            }
        }

        safeProviderCall {
            val watchedShows = provider.watchedShowEpisodes()
            for ((rawShowId, watchedSet) in watchedShows) {
                val normalizedShow = normalizeKey(rawShowId) ?: continue
                if (normalizedShow !in candidateContentIds) continue
                for ((season, episode) in watchedSet) {
                    signals.markCompleted(season, episode, lastWatchedMs = 0L)
                }
            }
        }

        safeProviderCall {
            for (video in eligible) {
                val season = video.season ?: continue
                val episode = video.episode ?: continue
                if (provider.isWatchedByVideoId(video.id, episode) == true) {
                    signals.markCompleted(season, episode, lastWatchedMs = 0L)
                }
            }
        }
    }

    private fun buildCandidateContentIds(contentId: String, episodes: List<Video>): Set<String> {
        val result = LinkedHashSet<String>()
        normalizeKey(contentId)?.let(result::add)

        parseContentIds(contentId).let { parsed ->
            parsed.imdb?.let { normalizeKey(it)?.let(result::add) }
            parsed.tmdb?.let { normalizeKey("tmdb:$it")?.let(result::add) }
            parsed.trakt?.let { normalizeKey("trakt:$it")?.let(result::add) }
            parsed.simkl?.let { normalizeKey("simkl:$it")?.let(result::add) }
        }

        for (video in episodes) {
            extractShowPrefixFromVideoId(video.id)?.let { prefix ->
                normalizeKey(prefix)?.let(result::add)
            }
        }
        return result
    }

    private fun extractShowPrefixFromVideoId(videoId: String): String? {
        val trimmed = videoId.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.isEmpty()) return null
        val first = parts[0].lowercase()
        return when {
            first.startsWith("tt") && first.length > 2 -> first
            first in PREFIXED_SHOW_NAMESPACES && parts.size >= 2 && parts[1].isNotBlank() ->
                "$first:${parts[1]}"
            else -> null
        }
    }

    private fun normalizeKey(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        return trimmed.ifEmpty { null }
    }

    private inline fun <T> safeCall(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend inline fun <T> safeProviderCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private companion object {
        val PREFIXED_SHOW_NAMESPACES = setOf("tmdb", "trakt", "simkl", "mal", "anilist", "kitsu", "anidb")
    }
}
