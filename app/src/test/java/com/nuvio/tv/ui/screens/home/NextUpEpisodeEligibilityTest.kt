package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NextUpEpisodeEligibilityTest {
    @Test
    fun nullReleaseDateIsUnairedFalse() {
        assertFalse(isNextUpEpisodeUnaired(null, today))
    }

    @Test
    fun nullReleaseDateEligibleRegardlessOfShowUnaired() {
        assertTrue(isNextUpEpisodeEligible(null, null, false, false, today))
        assertTrue(isNextUpEpisodeEligible(null, null, false, true, today))
        assertTrue(isNextUpEpisodeEligible(null, true, false, false, today))
        assertTrue(isNextUpEpisodeEligible(null, true, false, true, today))
    }

    @Test
    fun explicitUnavailableIsIneligible() {
        assertFalse(isNextUpEpisodeEligible(null, false, false, true, today))
        assertFalse(isNextUpEpisodeEligible(today, false, false, true, today))
        assertFalse(isNextUpEpisodeEligible(today.plusDays(1), false, false, true, today))
    }

    @Test
    fun futureSameSeasonToggledByShowUnaired() {
        val future = today.plusDays(1)
        assertFalse(isNextUpEpisodeEligible(future, null, false, false, today))
        assertTrue(isNextUpEpisodeEligible(future, null, false, true, today))
    }

    @Test
    fun nextSeasonRolloverWithin7DaysEligible() {
        val future = today.plusDays(7)
        assertTrue(isNextUpEpisodeEligible(future, null, true, true, today))
    }

    @Test
    fun nextSeasonRollover8DaysIneligible() {
        val future = today.plusDays(8)
        assertFalse(isNextUpEpisodeEligible(future, null, true, true, today))
    }


    private val today: LocalDate = LocalDate.of(2026, 8, 19)

    @Test
    fun `a past date is aired`() {
        assertFalse(isNextUpEpisodeUnaired(today.minusDays(1), today))
    }

    @Test
    fun `an episode airing today is aired`() {
        assertFalse(isNextUpEpisodeUnaired(today, today))
    }

    @Test
    fun `a future date is unaired`() {
        assertTrue(isNextUpEpisodeUnaired(today.plusDays(1), today))
    }

    @Test
    fun `a missing date is unknown rather than unaired`() {
        assertFalse(isNextUpEpisodeUnaired(null, today))
    }
}
