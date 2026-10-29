package com.nuvio.tv.domain.model

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingDismissalTest {
    @Test
    fun `legacy whole show removals cannot hide newly released episodes`() {
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 6, true, setOf("show")))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 6, true, setOf("show|1|5")))
        assertTrue(isContinueWatchingNextUpDismissed("show", 1, 5, false, setOf("show")))
    }

    @Test
    fun `explicit removal hides that released episode but not another episode or season`() {
        val keys = setOf(nextUpEpisodeDismissKey("show", 2, 6))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, true, keys))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, true, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 3, 1, true, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show-other", 2, 6, true, keys))
    }

    @Test
    fun `legacy and episode removals can coexist without weakening a new explicit removal`() {
        val keys = setOf("show", nextUpEpisodeDismissKey("show", 2, 6))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, true, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, true, keys))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 7, false, keys))
    }
}
