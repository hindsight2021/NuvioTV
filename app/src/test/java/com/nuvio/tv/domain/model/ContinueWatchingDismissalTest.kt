package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingDismissalTest {

    @Test
    fun `whole show dismissal hides show even if newly released`() {
        // Simpsons S38E6, South Park S29E4, Scrubs S2E6: if user dismissed/deleted the whole show, it stays dismissed!
        assertTrue(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf("tt0121955")))
        assertTrue(isContinueWatchingNextUpDismissed("tt40197357", 2, 6, isReleaseAlert = true, dismissedKeys = setOf("tt40197357")))
        assertTrue(isContinueWatchingNextUpDismissed("tt0096697", 38, 6, isReleaseAlert = true, dismissedKeys = setOf("tt0096697")))
    }

    @Test
    fun `episode dismissal does not hide future released episodes`() {
        // Dismissing an earlier episode does not hide a newly released episode
        assertFalse(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf("tt0121955|1|1")))
        assertFalse(isContinueWatchingNextUpDismissed("tt0121955", 29, 4, isReleaseAlert = true, dismissedKeys = setOf(nextUpEpisodeDismissKey("tt0121955", 29, 3))))
    }

    @Test
    fun `non-release alerts stay dismissed if bare show ID exists`() {
        // Futurama, The Middle, Lioness have no new episodes and were previously dismissed/completed
        assertTrue(isContinueWatchingNextUpDismissed("tmdb:615", 11, 5, isReleaseAlert = false, dismissedKeys = setOf("tmdb:615")))
        assertTrue(isContinueWatchingNextUpDismissed("tt1442464", 2, 7, isReleaseAlert = false, dismissedKeys = setOf("tt1442464")))
        assertTrue(isContinueWatchingNextUpDismissed("tt13111078", 2, 8, isReleaseAlert = false, dismissedKeys = setOf("tt13111078")))
        assertTrue(isContinueWatchingNextUpDismissed("tt0096697", 36, 5, isReleaseAlert = false, dismissedKeys = setOf("tt0096697")))
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
    fun `missing season or episode cannot be dismissed by episode key, but bare show ID dismisses`() {
        val keys = setOf("show|2|6", nextUpEpisodeDismissKey("show", 2, 6))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, 6, isReleaseAlert = false, dismissedKeys = keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", 2, null, isReleaseAlert = false, dismissedKeys = keys))
        assertFalse(isContinueWatchingNextUpDismissed("show", null, null, isReleaseAlert = false, dismissedKeys = keys))
        assertTrue(isContinueWatchingNextUpDismissed("show", null, null, isReleaseAlert = false, dismissedKeys = setOf("show")))
    }
}
