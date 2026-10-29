package com.nuvio.tv.data.local

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchedSeriesStateHolderTest {

    private val profileId = 1
    private lateinit var stores: MutableMap<Int, TestPreferencesStore>
    private lateinit var factory: ProfileDataStoreFactory
    private lateinit var profileManager: ProfileManager
    private lateinit var holder: WatchedSeriesStateHolder

    @Before
    fun setUp() {
        stores = mutableMapOf()
        factory = mockk()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg()) { TestPreferencesStore() }
        }

        profileManager = mockk(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(profileId)

        holder = WatchedSeriesStateHolder(factory, profileManager)
    }

    // ---------------------------------------------------------------------
    // 1. removeFullyWatched preserves revalidation state
    // ---------------------------------------------------------------------

    @Test
    fun `removeFullyWatched removes ids from flow but preserves revalidation state`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 10L * 24 * 60 * 60 * 1000

        holder.updateWithValidation(
            ids = setOf("show-a", "show-b"),
            validatedIds = setOf("show-a", "show-b"),
            seriesStates = mapOf(
                "show-a" to SeriesValidationInput(SeriesAirStatus.ONGOING, nextAir),
                "show-b" to SeriesValidationInput(SeriesAirStatus.ENDED, null)
            ),
            profileId = profileId
        )

        val statusBefore = holder.getSeriesStatus("show-a")
        val nextAirBefore = holder.getNextAirTimestamp("show-a")
        assertNotNull(statusBefore)
        assertEquals(SeriesAirStatus.ONGOING, statusBefore)
        assertEquals(nextAir, nextAirBefore)

        holder.removeFullyWatched(setOf("show-a"), profileId = profileId)

        // Flow no longer contains the removed id
        assertFalse(holder.fullyWatchedSeriesIds.value.contains("show-a"))
        assertTrue(holder.fullyWatchedSeriesIds.value.contains("show-b"))

        // Revalidation state preserved!
        assertEquals(SeriesAirStatus.ONGOING, holder.getSeriesStatus("show-a"))
        assertEquals(nextAir, holder.getNextAirTimestamp("show-a"))
        assertTrue(holder.hasBeenValidated("show-a"))
        assertTrue(holder.isKnownOngoing("show-a"))
    }

    @Test
    fun `removeFullyWatched with empty set is a no-op`() = runTest {
        holder.update(setOf("show-a"))
        holder.removeFullyWatched(emptySet(), profileId = profileId)
        assertEquals(setOf("show-a"), holder.fullyWatchedSeriesIds.value)
    }

    // ---------------------------------------------------------------------
    // 2. recordSeriesAirState stores ONGOING state with nextAirTimestampMs
    // ---------------------------------------------------------------------

    @Test
    fun `recordSeriesAirState stores ongoing state with next air timestamp`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 3L * 24 * 60 * 60 * 1000

        holder.recordSeriesAirState(
            contentId = "show-x",
            status = SeriesAirStatus.ONGOING,
            nextAirTimestampMs = nextAir,
            profileId = profileId
        )

        assertEquals(SeriesAirStatus.ONGOING, holder.getSeriesStatus("show-x"))
        assertEquals(nextAir, holder.getNextAirTimestamp("show-x"))
        assertTrue(holder.isKnownOngoing("show-x"))
        assertTrue(holder.hasBeenValidated("show-x"))

        // Does NOT add to fullyWatchedSeriesIds
        assertFalse(holder.fullyWatchedSeriesIds.value.contains("show-x"))
    }

    // ---------------------------------------------------------------------
    // 3. updateWithValidation retains existing revalidation state
    // ---------------------------------------------------------------------

    @Test
    fun `updateWithValidation does not wipe revalidation state for un-watched series`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 5L * 24 * 60 * 60 * 1000

        // Pre-record state for show-a
        holder.recordSeriesAirState(
            contentId = "show-a",
            status = SeriesAirStatus.ONGOING,
            nextAirTimestampMs = nextAir,
            profileId = profileId
        )

        // Update fully watched with only show-b, validating show-b
        holder.updateWithValidation(
            ids = setOf("show-b"),
            validatedIds = setOf("show-b"),
            seriesStates = mapOf(
                "show-b" to SeriesValidationInput(SeriesAirStatus.ENDED, null)
            ),
            profileId = profileId
        )

        // show-a must still have its ongoing status and next air date
        assertEquals(SeriesAirStatus.ONGOING, holder.getSeriesStatus("show-a"))
        assertEquals(nextAir, holder.getNextAirTimestamp("show-a"))
        assertTrue(holder.isKnownOngoing("show-a"))
    }

    // ---------------------------------------------------------------------
    // 4. filterStaleIds respects deadlines
    // ---------------------------------------------------------------------

    @Test
    fun `filterStaleIds returns ids that were never validated`() {
        val stale = holder.filterStaleIds(setOf("never-validated"))
        assertEquals(setOf("never-validated"), stale)
    }

    @Test
    fun `filterStaleIds excludes ids with future deadlines`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 10L * 24 * 60 * 60 * 1000

        holder.recordSeriesAirState(
            contentId = "fresh-ongoing",
            status = SeriesAirStatus.ONGOING,
            nextAirTimestampMs = nextAir,
            profileId = profileId
        )

        val stale = holder.filterStaleIds(setOf("fresh-ongoing"))
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `filterStaleIds handles mixed fresh and stale sets`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 10L * 24 * 60 * 60 * 1000

        holder.recordSeriesAirState(
            contentId = "fresh",
            status = SeriesAirStatus.ONGOING,
            nextAirTimestampMs = nextAir,
            profileId = profileId
        )

        val stale = holder.filterStaleIds(setOf("fresh", "unknown-1", "unknown-2"))
        assertEquals(setOf("unknown-1", "unknown-2"), stale)
    }

    // ---------------------------------------------------------------------
    // 5. isSeriesValidationFresh only for fully-watched shows
    // ---------------------------------------------------------------------

    @Test
    fun `isSeriesValidationFresh is false for non fully watched series`() = runTest {
        holder.recordSeriesAirState(
            contentId = "not-watched",
            status = SeriesAirStatus.ONGOING,
            nextAirTimestampMs = System.currentTimeMillis() + 10L * 24 * 60 * 60 * 1000,
            profileId = profileId
        )

        assertFalse(holder.isSeriesValidationFresh("not-watched"))
    }

    @Test
    fun `isSeriesValidationFresh is true for freshly validated watched series`() = runTest {
        val now = System.currentTimeMillis()
        val nextAir = now + 10L * 24 * 60 * 60 * 1000

        holder.updateWithValidation(
            ids = setOf("watched"),
            validatedIds = setOf("watched"),
            seriesStates = mapOf(
                "watched" to SeriesValidationInput(SeriesAirStatus.ONGOING, nextAir)
            ),
            profileId = profileId
        )

        assertTrue(holder.isSeriesValidationFresh("watched"))
    }

    // ---------------------------------------------------------------------
    // 6. clearValidationState wipes revalidation state but keeps watched ids
    // ---------------------------------------------------------------------

    @Test
    fun `clearValidationState wipes revalidation state but keeps watched ids`() = runTest {
        holder.updateWithValidation(
            ids = setOf("show-a"),
            validatedIds = setOf("show-a"),
            seriesStates = mapOf(
                "show-a" to SeriesValidationInput(
                    SeriesAirStatus.ONGOING,
                    System.currentTimeMillis() + 10L * 24 * 60 * 60 * 1000
                )
            ),
            profileId = profileId
        )

        holder.clearValidationState(profileId = profileId)

        assertEquals(setOf("show-a"), holder.fullyWatchedSeriesIds.value)
        assertNull(holder.getSeriesStatus("show-a"))
        assertFalse(holder.hasBeenValidated("show-a"))
    }
}
