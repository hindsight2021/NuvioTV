package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingDismissalTest {

    @Test
    fun `newly released episodes are not dismissed even if bare show ID or legacy prefix exists`() {
        // South Park S29E4 and Scrubs S2E6 released yesterday
        assertFalse(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf("tt0121955")))
        assertFalse(isContinueWatchingNextUpDismissed("tt40197357", 2, 6, isReleaseAlert = true, dismissedKeys = setOf("tt40197357")))
        assertFalse(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf("tt0121955|1|1")))
    }

    @Test
    fun `non-release alerts stay dismissed if bare show ID exists`() {
        // Futurama, The Middle, Lioness have no new episodes and were previously dismissed/completed
        assertTrue(isContinueWatchingNextUpDismissed("tmdb:615", 11, 5, isReleaseAlert = false, dismissedKeys = setOf("tmdb:615")))
        assertTrue(isContinueWatchingNextUpDismissed("tt1442464", 2, 7, isReleaseAlert = false, dismissedKeys = setOf("tt1442464")))
        assertTrue(isContinueWatchingNextUpDismissed("tt13111078", 2, 8, isReleaseAlert = false, dismissedKeys = setOf("tt13111078")))
    }

    @Test
    fun `explicit episode dismissal hides that exact episode regardless of release alert`() {
        val key = nextUpEpisodeDismissKey("tt0121955", 29, 4)
        assertTrue(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf(key)))
        assertTrue(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = false, dismissedKeys = setOf(key)))
        // Next episode 29x5 is not hidden
        assertFalse(isContinueWatchingNextUpDismissed("tt0121955", 29, 5, isReleaseAlert = true, dismissedKeys = setOf(key)))
    }

    @Test
    fun `legacy pipe delimited episode removal matches exact episode`() {
        val keys = setOf("show|2|6")
        assertTrue(isContinueWatchingNextUpDismissed("show", 2, 6, isReleaseAlert = false, dismissedKeys = keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, 7, isReleaseAlert = true, dismissedKeys = keys))
    }

    @Test
    fun `different show is not dismissed`() {
        assertFalse(isContinueWatchingNextUpDismissed("other-show", 1, 1, isReleaseAlert = false, dismissedKeys = setOf("tt0121955")))
    }

    @Test
    fun `missing season or episode cannot be dismissed`() {
        val keys = setOf("show|2|6", nextUpEpisodeDismissKey("show", 2, 6))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, 6, isReleaseAlert = false, dismissedKeys = keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, null, isReleaseAlert = false, dismissedKeys = keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, null, isReleaseAlert = false, dismissedKeys = keys))
    }
}
