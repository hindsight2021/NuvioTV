package com.nuvio.tv.ui.screens.home

import android.util.Log
import kotlinx.coroutines.flow.update

/** Called only while Home is resumed; no player-time scans. */
internal fun HomeViewModel.refreshContinueWatchingForClock() {
    val nowMs = System.currentTimeMillis()
    val checked = cwMetadataCheckedAt.toMap()
    val staleIds = watchedSeriesStateHolder.filterStaleIds(checked.keys)
    if (checked.any { (id, lastChecked) -> shouldRefreshCwSeries(lastChecked, id in staleIds, nowMs) }) {
        cwPipelineRefreshTrigger.update { it + 1 }
    }
}

/** Called inside the new snapshot scope, after old snapshot workers have been joined. */
internal fun HomeViewModel.invalidateDueContinueWatchingMetadata(
    seedIds: Set<String>,
    nowMs: Long = System.currentTimeMillis()
) {
    cwMetadataCheckedAt.keys.retainAll(seedIds)
    cwForcedNextUpRefreshIds.retainAll(seedIds)
    val staleIds = watchedSeriesStateHolder.filterStaleIds(seedIds)
    val dueIds = seedIds.filterTo(mutableSetOf()) { id ->
        val lastChecked = cwMetadataCheckedAt.putIfAbsent(id, nowMs)
        shouldRefreshCwSeries(lastChecked, id in staleIds, nowMs)
    }
    if (dueIds.isEmpty()) return
    val expanded = dueIds.toMutableSet()
    dueIds.forEach { id ->
        val siblings = cwLastShowIdSiblings[id].orEmpty()
        if ("__ambiguous__" !in siblings) {
            expanded.addAll(siblings.filterNot { it.startsWith("__") })
        }
    }
    expanded.forEach { cwMetadataCheckedAt[it] = nowMs }
    cwForcedNextUpRefreshIds.addAll(expanded)
    fun <V> clearTypedEntries(cache: MutableMap<String, V>) {
        synchronized(cache) {
            cache.keys.removeAll { it.substringAfter(':') in expanded }
        }
    }
    clearTypedEntries(cwMetaCache)
    clearTypedEntries(cwMetaNegativeCacheTimestamps)
    clearTypedEntries(cwBadgeEpisodeCache)
    synchronized(cwBadgeNextSeasonMs) { expanded.forEach(cwBadgeNextSeasonMs::remove) }
    synchronized(cwNextUpResolutionCache) {
        cwNextUpResolutionCache.keys.removeAll { it.substringBefore('|') in expanded }
    }
    cwNextUpNegativeCacheTimestamps.keys.removeAll { it.substringBefore('|') in expanded }
    expanded.forEach(metaRepository::clearCacheForId)
    cwLastBadgeEpisodeKeys = emptySet()
    Log.d("ContinueWatching", "revalidate ids=${expanded.joinToString()}")
}
