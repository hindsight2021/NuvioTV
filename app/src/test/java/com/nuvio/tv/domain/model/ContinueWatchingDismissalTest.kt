package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingDismissalTest {

    @Test
    fun `sanitizeDismissedNextUpKeys filters out bare show ids and keeps coordinates`() {
        val input = setOf("show", "tt0121955", "show|1|5", nextUpEpisodeDismissKey("show", 2, 6))
        val result = sanitizeDismissedNextUpKeys(input)
        assertEquals(setOf("show|1|5", nextUpEpisodeDismissKey("show", 2, 6)), result)
    }

    @Test
    fun `legacy whole show removals never hide episodes whether release alert or not`() {
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 6, true, setOf("show")))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 6, false, setOf("show")))
        assertFalse(isContinueWatchingNextUpDismissed("show", 1, 5, false, setOf("show")))
    }

    @Test
    fun `explicit episode removal hides only that exact episode`() {
        val keys = setOf(nextUpEpisodeDismissKey("show", 2, 6))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, true, keys))
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, true, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 3, 1, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show-other", 2, 6, false, keys))
    }

    @Test
    fun `legacy pipe delimited episode removal matches exact episode`() {
        val keys = setOf("show|2|6")
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, false, keys))
    }

    @Test
    fun `missing season or episode cannot be dismissed`() {
        val keys = setOf("show|2|6", nextUpEpisodeDismissKey("show", 2, 6))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, 6, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, null, false, keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, null, false, keys))
    }
}
