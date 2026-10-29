package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContinueWatchingRefreshPolicyTest {
    @Test
    fun `expired schedule refreshes without any watch progress change`() {
        assertFalse(shouldRefreshCwSeries(null, true, 0L))
        assertFalse(shouldRefreshCwSeries(0L, true, 299_999L))
        assertTrue(shouldRefreshCwSeries(0L, true, 300_000L))
    }

    @Test
    fun `provider missing schedule is rechecked even with a fresh badge deadline`() {
        assertFalse(shouldRefreshCwSeries(0L, false, 899_999L))
        assertTrue(shouldRefreshCwSeries(0L, false, 900_000L))
    }

    @Test
    fun `clock rollback cannot lock out discovery`() {
        assertTrue(shouldRefreshCwSeries(10_000L, false, 9_999L))
        assertFalse(shouldRefreshCwSeries(10_000L, true, 10_000L))
    }

    @Test
    fun `new snapshot cancels both older scan and badge writer before publishing`() = runTest {
        val snapshots = MutableSharedFlow<Int>()
        val events = mutableListOf<String>()
        val published = mutableListOf<String>()
        val collector = launch {
            snapshots.collectLatestCwSnapshot { value ->
                if (value == 1) {
                    launch {
                        published += "South Park 29x4"
                        try { awaitCancellation() } finally { events += "scan cancelled" }
                    }
                    launch {
                        try { awaitCancellation() } finally { events += "badge cancelled" }
                    }
                } else {
                    assertEquals(setOf("scan cancelled", "badge cancelled"), events.toSet())
                    published += "Scrubs 2x6"
                }
            }
        }
        runCurrent()
        snapshots.emit(1)
        runCurrent()
        assertEquals(listOf("South Park 29x4"), published)
        snapshots.emit(2)
        runCurrent()
        assertEquals(listOf("South Park 29x4", "Scrubs 2x6"), published)
        collector.cancel()
        runCurrent()
    }

    @Test
    fun `profile collector cancellation joins the old publication worker`() = runTest {
        val snapshots = MutableSharedFlow<Int>()
        val cancelled = CompletableDeferred<Unit>()
        val collector = launch {
            snapshots.collectLatestCwSnapshot {
                launch {
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
        }
        runCurrent()
        snapshots.emit(1)
        runCurrent()
        collector.cancel()
        runCurrent()
        assertTrue(cancelled.isCompleted)
    }
}
