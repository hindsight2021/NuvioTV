package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.nuvio.tv.core.util.BROADCAST_AIR_BUFFER_HOURS
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant

/**
 * Regression tests for currently-airing series leaving Continue Watching on air day.
 *
 * Scenario (Nuvio Sync, not Trakt):
 * 1. User finishes all *aired* episodes of a mid-season show → fully-watched checkmark
 * 2. Next episode is still unaired → may stay on CW as countdown next-up
 * 3. Episode airs same day → old code dropped the series from CW (fully-watched + hasAired)
 * 4. Fix keeps the series and clears the stale checkmark
 */
class ContinueWatchingAiringRulesTest {

    // --- Core decision (the air-day bug) ---

    @Test
    fun `fully watched with unaired next-up stays on CW and keeps badge`() {
        assertEquals(
            FullyWatchedNextUpAction.KEEP_WITH_BADGE,
            fullyWatchedNextUpAction(nextUpHasAired = false),
        )
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired = false))
    }

    @Test
    fun `fully watched with aired next-up stays on CW and clears badge`() {
        // This is the user-reported path: checkmark + new episode air day.
        assertEquals(
            FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE,
            fullyWatchedNextUpAction(nextUpHasAired = true),
        )
        assertFalse(
            "aired next-up must NOT drop fully-watched airing series from CW",
            shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired = true),
        )
    }

    @Test
    fun `legacy buggy rule would drop on air day — documents the regression`() {
        // 71e4fb6 / a58809 interaction: hasAired → drop.
        assertTrue(legacyShouldDropFullyWatchedNextUp(nextUpHasAired = true))
        assertFalse(legacyShouldDropFullyWatchedNextUp(nextUpHasAired = false))
        // Current rule must disagree with legacy on the air-day case.
        assertTrue(legacyShouldDropFullyWatchedNextUp(true))
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(true))
    }

    @Test
    fun `mid-season air day end-to-end pure flow`() {
        // Caught up through S1E5; badge cache only knows aired E1–E5.
        val airedBeforeDrop = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        val watched = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        assertTrue(isFullyWatchedAgainstAiredList(airedBeforeDrop, watched))

        // E6 airs. Next-up resolves as aired.
        val action = fullyWatchedNextUpAction(nextUpHasAired = true)
        assertEquals(FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE, action)
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(true))

        // Badge cache absorbs E6 so publishBadgeUpdate does not re-checkmark.
        val airedAfter = mergeAiredNextEpisodeIntoBadgeCache(airedBeforeDrop, nextSeason = 1, nextEpisode = 6)!!
        assertEquals(
            setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to 6),
            airedAfter,
        )
        assertFalse(
            "after E6 is known as aired and unwatched, series is no longer fully watched",
            isFullyWatchedAgainstAiredList(airedAfter, watched),
        )
    }

    // --- Badge cache merge ---

    @Test
    fun `merge aired next episode into empty cache returns null`() {
        assertNull(mergeAiredNextEpisodeIntoBadgeCache(null, 1, 6))
    }

    @Test
    fun `merge with missing season or episode leaves set unchanged`() {
        val existing = setOf(1 to 1, 1 to 2)
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, null, 6))
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, 1, null))
    }

    @Test
    fun `merge is idempotent when episode already present`() {
        val existing = setOf(1 to 1, 1 to 6)
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, 1, 6))
    }

    // --- Mid-season revalidation deadline ---

    @Test
    fun `earliestUpcomingEpisodeMs uses next mid-season air time not only next season`() {
        val now = Instant.parse("2026-07-12T12:00:00Z")
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.of(2026, 7, 13)
        val nextSeasonPremiere = LocalDate.of(2026, 9, 1)

        val meta = CwMetaSummary(
            id = "tt-test",
            name = "Airing Show",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, "2026-06-01"),
                episode(1, 2, "2026-06-08"),
                episode(1, 3, tomorrow.toString()), // mid-season next
                episode(2, 1, nextSeasonPremiere.toString()),
            ),
        )

        val ms = meta.earliestUpcomingEpisodeMs(now)
        assertNotNull(ms)
        val expected = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, ms)

        // Revalidation should prefer the nearer mid-season episode over S2 premiere window.
        val revalidate = meta.earliestRevalidationMs(now)
        assertEquals(ms, revalidate)
    }

    @Test
    fun `earliestUpcomingEpisodeMs returns null when fully finished with no future dates`() {
        val now = Instant.parse("2026-07-12T12:00:00Z")
        val meta = CwMetaSummary(
            id = "tt-done",
            name = "Finished Show",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, "2025-01-01"),
                episode(1, 2, "2025-01-08"),
            ),
        )
        assertNull(meta.earliestUpcomingEpisodeMs(now))
    }

    @Test
    fun `watchableEpisodes excludes future mid-season episodes so checkmark can apply`() {
        // Documents a58809 behavior: only aired eps count → caught-up checkmark possible.
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString()
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
        val meta = CwMetaSummary(
            id = "tt-airing",
            name = "Airing",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, yesterday),
                episode(1, 2, yesterday),
                episode(1, 3, tomorrow),
            ),
        )
        val watchable = meta.watchableEpisodes().map { it.season to it.episode }
        assertEquals(listOf(1 to 1, 1 to 2), watchable)
        // User watched all watchable → fully watched badge eligible, which then
        // used to trigger the CW drop on air day (covered above).
        assertTrue(
            isFullyWatchedAgainstAiredList(
                airedEpisodes = watchable.mapNotNull { (s, e) ->
                    if (s != null && e != null) s to e else null
                }.toSet(),
                watchedEpisodes = setOf(1 to 1, 1 to 2),
            ),
        )
    }

    // --- Release alert tests ---

    @Test
    fun `episode released within window and after last watched returns true`() {
        val nowMs = 1759359600000L
        val releaseMs = nowMs - 10L * 24 * 60 * 60 * 1000
        val lastWatchedMs = nowMs - 30L * 24 * 60 * 60 * 1000

        assertTrue(
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = releaseMs,
                lastWatched = lastWatchedMs,
                isNewSeason = false,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `episode released within window and watched on air day returns true`() {
        val nowMs = 1759359600000L
        val releaseMs = nowMs - 5L * 24 * 60 * 60 * 1000
        val lastWatchedMs = releaseMs + 2L * 60 * 60 * 1000 // watched 2 hours after release

        assertTrue(
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = releaseMs,
                lastWatched = lastWatchedMs,
                isNewSeason = false,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `new season episode released within window returns true even if last watched after release`() {
        val nowMs = 1759359600000L
        val releaseMs = nowMs - 20L * 24 * 60 * 60 * 1000
        val lastWatchedMs = nowMs - 1L * 24 * 60 * 60 * 1000

        assertTrue(
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = releaseMs,
                lastWatched = lastWatchedMs,
                isNewSeason = true,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `episode released more than 60 days ago returns false`() {
        val nowMs = 1759359600000L
        val releaseMs = nowMs - 61L * 24 * 60 * 60 * 1000
        val lastWatchedMs = nowMs - 90L * 24 * 60 * 60 * 1000

        assertFalse(
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = releaseMs,
                lastWatched = lastWatchedMs,
                isNewSeason = false,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `unaired episode returns false for release alert`() {
        val nowMs = 1759359600000L
        val releaseMs = nowMs - 1L * 24 * 60 * 60 * 1000

        assertFalse(
            computeNextUpReleaseAlert(
                hasAired = false,
                releaseTimestamp = releaseMs,
                lastWatched = nowMs - 10L * 24 * 60 * 60 * 1000,
                isNewSeason = false,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun `null release timestamp returns false for release alert`() {
        val nowMs = 1759359600000L

        assertFalse(
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = null,
                lastWatched = nowMs - 10L * 24 * 60 * 60 * 1000,
                isNewSeason = false,
                nowMs = nowMs
            )
        )
    }

    // --- isNextUpEpisodeEligible tests ---

    @Test
    fun `season rollover with null release date is never eligible`() {
        val today = LocalDate.of(2026, 10, 7)
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = null,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = true,
                today = today
            )
        )
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = null,
                available = null,
                isSeasonRollover = true,
                showUnairedNextUp = false,
                today = today
            )
        )
    }

    @Test
    fun `mid-season episode with null release date is eligible if available not false`() {
        val today = LocalDate.of(2026, 10, 7)
        assertTrue(
            isNextUpEpisodeEligible(
                releaseDate = null,
                available = true,
                isSeasonRollover = false,
                showUnairedNextUp = false,
                today = today
            )
        )
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = null,
                available = false,
                isSeasonRollover = false,
                showUnairedNextUp = false,
                today = today
            )
        )
    }

    @Test
    fun `season rollover with future release date within 7 days is eligible only when showUnairedNextUp is true`() {
        val today = LocalDate.of(2026, 10, 7)
        val nearFuture = today.plusDays(5)
        assertTrue(
            isNextUpEpisodeEligible(
                releaseDate = nearFuture,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = true,
                today = today
            )
        )
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = nearFuture,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = false,
                today = today
            )
        )
    }

    @Test
    fun `season rollover with future release date beyond 7 days is not eligible even when showUnairedNextUp is true`() {
        val today = LocalDate.of(2026, 10, 7)
        val distantFuture = today.plusDays(14)
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = distantFuture,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = true,
                today = today
            )
        )
    }

    @Test
    fun `season rollover with already aired episode is eligible`() {
        val today = LocalDate.of(2026, 10, 7)
        val past = today.minusDays(1)
        assertTrue(
            isNextUpEpisodeEligible(
                releaseDate = past,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = false,
                today = today
            )
        )
        assertTrue(
            isNextUpEpisodeEligible(
                releaseDate = today,
                available = true,
                isSeasonRollover = true,
                showUnairedNextUp = false,
                today = today
            )
        )
    }

    @Test
    fun `available false is never eligible regardless of other parameters`() {
        val today = LocalDate.of(2026, 10, 7)
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = today.minusDays(1),
                available = false,
                isSeasonRollover = false,
                showUnairedNextUp = true,
                today = today
            )
        )
        assertFalse(
            isNextUpEpisodeEligible(
                releaseDate = today.minusDays(1),
                available = false,
                isSeasonRollover = true,
                showUnairedNextUp = true,
                today = today
            )
        )
    }

    // --- Specific regressions: South Park & Dragons' Den Canada ---

    @Test
    fun `South Park S29E3 does not air prematurely and airs at release instant`() {
        val s29e3Released = "2026-10-15T06:00:00.000Z"
        val releaseMs = parseEpisodeReleaseInstant(s29e3Released)!!.toEpochMilli()

        // 10:00 PM EDT on Oct 14 is 02:00:00Z on Oct 15 (4 hours before release timestamp)
        val beforeAirTime = Instant.parse("2026-10-15T02:00:00.000Z")
        val beforeClock = Clock.fixed(beforeAirTime, ZoneOffset.UTC)
        val hasAiredBefore = isEpisodeReleaseAired(s29e3Released, beforeClock, airBufferHours = BROADCAST_AIR_BUFFER_HOURS)
        assertEquals(false, hasAiredBefore)

        val alertBefore = computeNextUpReleaseAlert(
            hasAired = hasAiredBefore == true,
            releaseTimestamp = releaseMs,
            lastWatched = beforeAirTime.toEpochMilli() - 14L * 24 * 60 * 60 * 1000,
            isNewSeason = false,
            nowMs = beforeAirTime.toEpochMilli()
        )
        assertFalse("Must NOT trigger release alert before streaming release instant", alertBefore)

        // At release instant (06:00:00Z) or next morning (12:00:00Z)
        val afterAirTime = Instant.parse("2026-10-15T12:00:00.000Z")
        val afterClock = Clock.fixed(afterAirTime, ZoneOffset.UTC)
        val hasAiredAfter = isEpisodeReleaseAired(s29e3Released, afterClock, airBufferHours = BROADCAST_AIR_BUFFER_HOURS)
        assertEquals(true, hasAiredAfter)

        val alertAfter = computeNextUpReleaseAlert(
            hasAired = hasAiredAfter == true,
            releaseTimestamp = releaseMs,
            lastWatched = afterAirTime.toEpochMilli() - 14L * 24 * 60 * 60 * 1000,
            isNewSeason = false,
            nowMs = afterAirTime.toEpochMilli()
        )
        assertTrue("Must trigger release alert once aired", alertAfter)
    }

    @Test
    fun `isOngoingSeries returns true for continuing statuses case-insensitively`() {
        val ongoingStatuses = listOf(
            "Continuing",
            "continuing",
            "CONTINUING",
            "returning series",
            "Returning Series",
            "RETURNING SERIES",
            "in production",
            "In Production",
            "IN PRODUCTION",
            "planned",
            "Planned",
            "PLANNED"
        )
        for (status in ongoingStatuses) {
            assertTrue(
                "Expected ongoing for status='$status'",
                isOngoingSeries(releaseInfo = null, status = status)
            )
        }
    }

    @Test
    fun `isOngoingSeries returns false for ended or canceled status without trailing dash`() {
        val endedStatuses = listOf(
            "Ended",
            "ended",
            "ENDED",
            "Canceled",
            "canceled",
            "CANCELED"
        )
        for (status in endedStatuses) {
            assertFalse(
                "Expected not ongoing for status='$status'",
                isOngoingSeries(releaseInfo = null, status = status)
            )
        }
    }

    @Test
    fun `isOngoingSeries handles trailing hyphen en-dash and em-dash`() {
        assertTrue("Trailing ASCII hyphen", isOngoingSeries(releaseInfo = "2006-"))
        assertTrue("Trailing Unicode en-dash", isOngoingSeries(releaseInfo = "2006\u2013"))
        assertTrue("Trailing Unicode em-dash", isOngoingSeries(releaseInfo = "2006\u2014"))
        assertTrue("Continuing with trailing dash", isOngoingSeries(releaseInfo = "2006\u2013", status = "Continuing"))
        assertTrue("Trailing dash overrides ended status", isOngoingSeries(releaseInfo = "2006-", status = "Ended"))
    }

    @Test
    fun `isOngoingSeries returns false for closed range single year or null`() {
        assertFalse("Closed range hyphen", isOngoingSeries(releaseInfo = "2006-2024"))
        assertFalse("Closed range en-dash", isOngoingSeries(releaseInfo = "2006\u20132024"))
        assertFalse("Single year", isOngoingSeries(releaseInfo = "2020"))
        assertFalse("Null releaseInfo and status", isOngoingSeries(releaseInfo = null, status = null))
        assertFalse("Blank strings", isOngoingSeries(releaseInfo = "   ", status = "   "))
    }

    @Test
    fun `computeNextUpReleaseAlert never fires before release instant even if hasAired is true`() {
        val futureReleaseMs = 1792053600000L
        val beforeMs = futureReleaseMs - 4L * 60 * 60 * 1000
        assertFalse(
            "Must not alert before release instant even if hasAired passed as true",
            computeNextUpReleaseAlert(
                hasAired = true,
                releaseTimestamp = futureReleaseMs,
                lastWatched = beforeMs - 10000L,
                isNewSeason = false,
                nowMs = beforeMs
            )
        )
    }

    @Test
    fun `Dragons Den Canada two episodes behind is eligible and raises release alert`() {
        val nowMs = Instant.parse("2026-10-15T12:00:00.000Z").toEpochMilli()
        // S21E2 watched 21 days ago
        val lastWatchedMs = nowMs - 21L * 24 * 60 * 60 * 1000
        // S21E3 released 14 days ago
        val s21e3Released = "2026-10-01T20:00:00.000Z"
        val s21e3Ms = parseEpisodeReleaseInstant(s21e3Released)!!.toEpochMilli()

        val isAlert = computeNextUpReleaseAlert(
            hasAired = true,
            releaseTimestamp = s21e3Ms,
            lastWatched = lastWatchedMs,
            isNewSeason = false,
            nowMs = nowMs
        )
        assertTrue("Dragons' Den Canada S21E3 must trigger a release alert", isAlert)

        // Effective sort timestamp should use release timestamp (Oct 1) which is newer than lastWatched (Sep 24)
        val sortTimestamp = if (isAlert) s21e3Ms else lastWatchedMs
        assertEquals(s21e3Ms, sortTimestamp)

        // With 14 days cutoff, sortTimestamp (Oct 1) is >= cutoffMs
        val cutoffMs = nowMs - 14L * 24 * 60 * 60 * 1000
        assertTrue("Sort timestamp must be >= cutoffMs", sortTimestamp >= cutoffMs)

        // isNextUpEpisodeEligible for S21E3
        val eligible = isNextUpEpisodeEligible(
            releaseDate = LocalDate.of(2026, 10, 1),
            available = true,
            isSeasonRollover = false,
            showUnairedNextUp = false,
            today = LocalDate.of(2026, 10, 15),
            hasAired = true
        )
        assertTrue("S21E3 must be eligible", eligible)
    }

    private fun episode(season: Int, ep: Int, released: String?) = CwVideoSummary(
        id = "tt-test:$season:$ep",
        title = "E$ep",
        released = released,
        thumbnail = null,
        season = season,
        episode = ep,
        overview = null,
    )
}
