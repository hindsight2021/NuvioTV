package com.nuvio.tv.data.simkl.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklCalendarFilterTest {

    @Test
    fun normalizeTitle_removesSpecialCharactersAndSpaces() {
        assertEquals("severance", SimklCalendarRepository.normalizeTitle("Severance"))
        assertEquals("thelastofus", SimklCalendarRepository.normalizeTitle("The Last of Us: Season 2"))
        assertEquals("arcane", SimklCalendarRepository.normalizeTitle("Arcane (2024)"))
    }

    @Test
    fun trackedMediaFilter_matchesTrackedEntries() {
        val filter = SimklCalendarRepository.TrackedMediaFilter(
            imdbIds = setOf("tt1234567"),
            tmdbIds = setOf("98765"),
            simklIds = setOf(112233L),
            normalizedTitles = setOf("thelastofus", "severance")
        )

        assertFalse(filter.isEmpty)

        // Matches by IMDb
        assertTrue(filter.matches("tt1234567", null, null, "Something Else"))
        // Matches by TMDb
        assertTrue(filter.matches(null, "98765", null, "Random Title"))
        // Matches by Simkl ID
        assertTrue(filter.matches(null, null, 112233L, null))
        // Matches by normalized title
        assertTrue(filter.matches(null, null, null, "The Last of Us"))
        assertTrue(filter.matches(null, null, null, "Severance!"))

        // Does not match untracked media
        assertFalse(filter.matches("tt9999999", "11111", 44444L, "Unrelated Show"))
    }

    @Test
    fun parseEpisodeMarker_parsesStandardAndFlexibleFormats() {
        assertEquals(Pair(1, 4), SimklCalendarRepository.parseEpisodeMarker("s01e04"))
        assertEquals(Pair(2, 10), SimklCalendarRepository.parseEpisodeMarker("S2E10"))
        assertEquals(Pair(null, 5), SimklCalendarRepository.parseEpisodeMarker("e05"))
        assertEquals(Pair(null, 12), SimklCalendarRepository.parseEpisodeMarker("E12"))
        assertEquals(null, SimklCalendarRepository.parseEpisodeMarker("invalid"))
        assertEquals(null, SimklCalendarRepository.parseEpisodeMarker(null))
    }

    @Test
    fun userCalendarFilters_resolvesShowStatusAcrossAllKeys() {
        val status = SimklCalendarRepository.ShowWatchStatus(
            isActivelyWatching = true,
            isWatchlist = false,
            nextSeason = 2,
            nextEpisode = 1,
            lastWatchedSeason = 1,
            lastWatchedEpisode = 10,
            seasonMaxEpisodes = mapOf(1 to 10, 2 to 8),
            totalWatchedEpisodes = 10
        )

        val filters = SimklCalendarRepository.UserCalendarFilters(
            userShows = SimklCalendarRepository.TrackedMediaFilter(imdbIds = setOf("tt1234567")),
            userMovies = SimklCalendarRepository.TrackedMediaFilter(),
            showStatusByImdb = mapOf("tt1234567" to status),
            showStatusByTmdb = mapOf("999" to status),
            showStatusBySimkl = mapOf(555L to status),
            showStatusByTitle = mapOf("severance" to status)
        )

        assertEquals(status, filters.findShowStatus("tt1234567", null, null, null))
        assertEquals(status, filters.findShowStatus(null, "999", null, null))
        assertEquals(status, filters.findShowStatus(null, null, 555L, null))
        assertEquals(status, filters.findShowStatus(null, null, null, "Severance"))
        assertEquals(null, filters.findShowStatus("tt0000000", null, null, "Other"))
    }

    @Test
    fun emptyTrackedMediaFilter_identifiesCorrectly() {
        val emptyFilter = SimklCalendarRepository.TrackedMediaFilter()
        assertTrue(emptyFilter.isEmpty)
        assertFalse(emptyFilter.matches("tt1234567", null, null, "Severance"))
    }
}
