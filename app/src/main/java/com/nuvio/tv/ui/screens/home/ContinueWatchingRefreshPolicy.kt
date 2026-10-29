package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

internal fun shouldRefreshCwSeries(
    lastCheckedMs: Long?,
    validationExpired: Boolean,
    nowMs: Long
): Boolean {
    if (lastCheckedMs == null) return false
    if (nowMs < lastCheckedMs) return true
    val elapsed = nowMs - lastCheckedMs
    return elapsed >= 5L * 60_000L && (validationExpired || elapsed >= 15L * 60_000L)
}

/** Each snapshot owns its scans and snapshot writes; replacement cancels and joins them. */
internal suspend fun <T> Flow<T>.collectLatestCwSnapshot(
    block: suspend CoroutineScope.(T) -> Unit
) = collectLatest { value -> coroutineScope { block(value) } }
