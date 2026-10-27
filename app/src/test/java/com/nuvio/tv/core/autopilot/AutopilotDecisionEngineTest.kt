package com.nuvio.tv.core.autopilot

import android.content.Context
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AutopilotDecisionEngine].
 */
class AutopilotDecisionEngineTest {

    private lateinit var context: Context
    private lateinit var continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache
    private lateinit var aiManager: AiManager
    private lateinit var engine: AutopilotDecisionEngine

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        continueWatchingEnrichmentCache = mockk(relaxed = true)
        aiManager = mockk(relaxed = true)
        engine = AutopilotDecisionEngine(context, continueWatchingEnrichmentCache, aiManager)
    }

    // ---------------------------------------------------------------------
    // selectDefault
    // ---------------------------------------------------------------------

    @Test
    fun selectDefault_prioritizesNewReleaseOverOlderWatched() {
        val candidateA = buildCandidate(
            contentId = "a",
            lastWatched = 1000L,
            isNewRelease = false
        )
        val candidateB = buildCandidate(
            contentId = "b",
            lastWatched = 500L,
            isNewRelease = true
        )

        val result = engine.selectDefault(listOf(candidateA, candidateB))

        assertNotNull(result)
        assertEquals("b", result!!.candidate.contentId)
        assertEquals(AutopilotMode.DEFAULT, result.mode)
        assertTrue(result.aiRationale?.contains("New release") == true)
    }

    @Test
    fun selectDefault_fallsBackToMostRecentlyWatchedWhenNoNewReleases() {
        val candidateA = buildCandidate(
            contentId = "a",
            lastWatched = 1000L,
            isNewRelease = false
        )
        val candidateB = buildCandidate(
            contentId = "b",
            lastWatched = 2000L,
            isNewRelease = false
        )

        val result = engine.selectDefault(listOf(candidateA, candidateB))

        assertNotNull(result)
        assertEquals("b", result!!.candidate.contentId)
        assertEquals(AutopilotMode.DEFAULT, result.mode)
    }

    // ---------------------------------------------------------------------
    // loadCandidates
    // ---------------------------------------------------------------------

    @Test
    fun loadCandidates_filtersOutCurrentContentId() = runTest {
        val nextUpShow1 = buildNextUpItem(contentId = "show_1", hasAired = true)
        val nextUpShow2 = buildNextUpItem(contentId = "show_2", hasAired = true)

        coEvery { continueWatchingEnrichmentCache.getNextUpSnapshot() } returns
            listOf(nextUpShow1, nextUpShow2)
        coEvery { continueWatchingEnrichmentCache.getInProgressSnapshot() } returns emptyList()

        val candidates = engine.loadCandidates("show_1")

        assertEquals(1, candidates.size)
        assertEquals("show_2", candidates.first().contentId)
    }

    @Test
    fun loadCandidates_filtersOutUnairedNextUpEpisodes() = runTest {
        val unaired = buildNextUpItem(contentId = "show_1", hasAired = false)

        coEvery { continueWatchingEnrichmentCache.getNextUpSnapshot() } returns listOf(unaired)
        coEvery { continueWatchingEnrichmentCache.getInProgressSnapshot() } returns emptyList()

        val candidates = engine.loadCandidates("other")

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun loadCandidates_filtersOutCompletedInProgress() = runTest {
        val completed = buildInProgressItem(contentId = "show_1", progressPercent = 0.98f)

        coEvery { continueWatchingEnrichmentCache.getNextUpSnapshot() } returns emptyList()
        coEvery { continueWatchingEnrichmentCache.getInProgressSnapshot() } returns listOf(completed)

        val candidates = engine.loadCandidates("other")

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun loadCandidates_deduplicatesNextUpAndInProgress() = runTest {
        val nextUp = buildNextUpItem(contentId = "show_1", hasAired = true)
        val inProgress = buildInProgressItem(contentId = "show_1", progressPercent = 0.5f)

        coEvery { continueWatchingEnrichmentCache.getNextUpSnapshot() } returns listOf(nextUp)
        coEvery { continueWatchingEnrichmentCache.getInProgressSnapshot() } returns listOf(inProgress)

        val candidates = engine.loadCandidates("other")

        assertEquals(1, candidates.size)
        assertEquals("show_1", candidates.first().contentId)
    }

    // ---------------------------------------------------------------------
    // selectAiCurated
    // ---------------------------------------------------------------------

    @Test
    fun selectAiCurated_returnsChosenCandidateWhenGeminiSucceeds() = runTest {
        val candidateA = buildCandidate(contentId = "a", lastWatched = 1000L, isNewRelease = false)
        val candidateB = buildCandidate(contentId = "b", lastWatched = 2000L, isNewRelease = false)
        val candidates = listOf(candidateA, candidateB)

        coEvery {
            aiManager.queryRaw(
                context = any(),
                systemPrompt = any(),
                prompt = any(),
                expectJson = any()
            )
        } returns Result.success("{\"selectedIndex\": 1, \"rationale\": \"Pacing match\"}")

        val result = engine.selectAiCurated(candidates)

        assertNotNull(result)
        assertEquals("b", result!!.candidate.contentId)
        assertEquals(AutopilotMode.AI_CURATED, result.mode)
        assertEquals("Pacing match", result.aiRationale)
    }

    @Test
    fun selectAiCurated_fallsBackToDefaultWhenGeminiFailsOrInvalidJson() = runTest {
        val candidateA = buildCandidate(contentId = "a", lastWatched = 1000L, isNewRelease = false)
        val candidateB = buildCandidate(contentId = "b", lastWatched = 2000L, isNewRelease = false)
        val candidates = listOf(candidateA, candidateB)

        coEvery {
            aiManager.queryRaw(
                context = any(),
                systemPrompt = any(),
                prompt = any(),
                expectJson = any()
            )
        } returns Result.failure(RuntimeException("Network error"))

        val result = engine.selectAiCurated(candidates)

        assertNotNull(result)
        assertEquals(AutopilotMode.AI_CURATED, result!!.mode)
        // Default selection picks the most recently watched candidate ("b")
        assertEquals("b", result.candidate.contentId)
    }

    // ---------------------------------------------------------------------
    // selectNextEpisode
    // ---------------------------------------------------------------------

    @Test
    fun selectNextEpisode_returnsNullWhenNoCandidates() = runTest {
        coEvery { continueWatchingEnrichmentCache.getNextUpSnapshot() } returns emptyList()
        coEvery { continueWatchingEnrichmentCache.getInProgressSnapshot() } returns emptyList()

        val result = engine.selectNextEpisode()

        assertNull(result)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun buildCandidate(
        contentId: String,
        lastWatched: Long,
        isNewRelease: Boolean
    ): AutopilotCandidate = AutopilotCandidate(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        videoId = "$contentId:1:1",
        lastWatched = lastWatched,
        isNewRelease = isNewRelease
    )

    private fun buildNextUpItem(
        contentId: String,
        hasAired: Boolean
    ): CachedNextUpItem = CachedNextUpItem(
        contentId = contentId,
        contentType = "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:1",
        season = 1,
        episode = 1,
        episodeTitle = "Episode 1",
        thumbnail = null,
        hasAired = hasAired,
        lastWatched = 1000L,
        sortTimestamp = 1000L
    )

    private fun buildInProgressItem(
        contentId: String,
        progressPercent: Float
    ): CachedInProgressItem = CachedInProgressItem(
        contentId = contentId,
        contentType = "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:1",
        season = 1,
        episode = 1,
        episodeTitle = "Episode 1",
        position = 1000L,
        duration = 2000L,
        lastWatched = 1000L,
        progressPercent = progressPercent
    )
}
