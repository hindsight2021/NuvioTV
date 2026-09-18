package com.nuvio.tv.ambient.engine

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientSeason
import com.nuvio.tv.ambient.AmbientTimeBucket
import com.nuvio.tv.ambient.context.AmbientRecentWatchContext
import com.nuvio.tv.ambient.context.AmbientTimeContext
import com.nuvio.tv.ambient.context.AmbientWeatherContext
import com.nuvio.tv.ambient.history.AmbientHistoryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AmbientRankingEngine].
 *
 * Verifies candidate ranking, quality scoring, 4K/HDR bonuses,
 * contextual boosts (time, weather, season), cooldown penalties,
 * failure penalties, and diversity guards.
 */
class AmbientRankingEngineTest {

    private lateinit var timeContext: AmbientTimeContext
    private lateinit var weatherContext: AmbientWeatherContext
    private lateinit var recentWatchContext: AmbientRecentWatchContext
    private lateinit var historyRepository: AmbientHistoryRepository
    private lateinit var engine: AmbientRankingEngine

    private val nowMs: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        timeContext = mockk(relaxed = true)
        weatherContext = mockk(relaxed = true)
        recentWatchContext = mockk(relaxed = true)
        historyRepository = mockk(relaxed = true)

        every { timeContext.getCurrentTimeBucket() } returns AmbientTimeBucket.DAY
        every { timeContext.getTimeTags(any()) } returns emptyList()
        every { timeContext.getCurrentSeason() } returns AmbientSeason.SPRING
        every { timeContext.getSeasonTags(any()) } returns emptyList()
        every { weatherContext.getCurrentWeatherTags() } returns emptyList()
        every { recentWatchContext.getCategoryBonus(any(), any()) } returns 0f
        coEvery { historyRepository.getLastPlayedTime(any()) } returns null
        coEvery { historyRepository.getFailureCount(any()) } returns 0

        engine = AmbientRankingEngine(
            timeContext = timeContext,
            weatherContext = weatherContext,
            recentWatchContext = recentWatchContext,
            historyRepository = historyRepository
        )
    }

    private fun candidate(
        id: String,
        is4K: Boolean = true,
        isHdr: Boolean = false,
        location: String? = null,
        country: String? = null,
        category: String = "nature",
        weatherTags: List<String> = emptyList(),
        seasonTags: List<String> = emptyList(),
        timeTags: List<String> = emptyList(),
        qualityScore: Double = 0.9,
        calmnessScore: Double = 0.8,
        reliabilityScore: Double = 0.9
    ) = AmbientCandidate(
        id = id,
        title = "Candidate $id",
        location = location,
        country = country,
        category = category,
        is4K = is4K,
        isHdr = isHdr,
        qualityScore = qualityScore,
        calmnessScore = calmnessScore,
        reliabilityScore = reliabilityScore,
        weatherTags = weatherTags,
        seasonTags = seasonTags,
        timeTags = timeTags
    )

    private fun scoreOf(result: List<Pair<AmbientCandidate, Float>>, id: String): Float =
        result.first { it.first.id == id }.second

    @Test
    fun `rankCandidates returns candidates sorted in descending order of score`() = runTest {
        val low = candidate("low", qualityScore = 0.2, calmnessScore = 0.2, reliabilityScore = 0.2, is4K = false)
        val mid = candidate("mid", qualityScore = 0.5, calmnessScore = 0.5, reliabilityScore = 0.5, is4K = false)
        val high = candidate("high", qualityScore = 0.9, calmnessScore = 0.9, reliabilityScore = 0.9, is4K = true)

        val result = engine.rankCandidates(
            candidates = listOf(low, mid, high),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        assertEquals(listOf("high", "mid", "low"), result.map { it.first.id })
        assertTrue(result[0].second >= result[1].second)
        assertTrue(result[1].second >= result[2].second)
    }

    @Test
    fun `4K and HDR candidates score higher than 1080p SDR with identical base metrics`() = runTest {
        val sdr = candidate("sdr", is4K = false, isHdr = false)
        val uhd = candidate("uhd", is4K = true, isHdr = false)
        val hdr = candidate("hdr", is4K = false, isHdr = true)
        val both = candidate("both", is4K = true, isHdr = true)

        val result = engine.rankCandidates(
            candidates = listOf(sdr, uhd, hdr, both),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        val sdrScore = scoreOf(result, "sdr")
        val uhdScore = scoreOf(result, "uhd")
        val hdrScore = scoreOf(result, "hdr")
        val bothScore = scoreOf(result, "both")

        assertTrue(uhdScore > sdrScore)
        assertTrue(hdrScore > sdrScore)
        assertTrue(bothScore > uhdScore)
        assertTrue(bothScore > hdrScore)
    }

    @Test
    fun `time weather and season tag matches apply respective bonuses`() = runTest {
        every { timeContext.getTimeTags(any()) } returns listOf("day")
        every { weatherContext.getCurrentWeatherTags() } returns listOf("sunny")
        every { timeContext.getSeasonTags(any()) } returns listOf("spring")

        val baseline = candidate("baseline")
        val timeMatch = candidate("time", timeTags = listOf("day"))
        val weatherMatch = candidate("weather", weatherTags = listOf("sunny"))
        val seasonMatch = candidate("season", seasonTags = listOf("spring"))

        val result = engine.rankCandidates(
            candidates = listOf(baseline, timeMatch, weatherMatch, seasonMatch),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        val base = scoreOf(result, "baseline")
        assertEquals(base + 0.30f, scoreOf(result, "time"), 0.001f)
        assertEquals(base + 0.25f, scoreOf(result, "weather"), 0.001f)
        assertEquals(base + 0.20f, scoreOf(result, "season"), 0.001f)
    }

    @Test
    fun `recent playback within 2 hours applies cooldown penalty`() = runTest {
        val recentId = "recent"
        val freshId = "fresh"

        coEvery { historyRepository.getLastPlayedTime(recentId) } returns nowMs - 30 * 60 * 1000L
        coEvery { historyRepository.getLastPlayedTime(freshId) } returns null

        val recent = candidate(recentId)
        val fresh = candidate(freshId)

        val result = engine.rankCandidates(
            candidates = listOf(recent, fresh),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        val freshScore = scoreOf(result, freshId)
        val recentScore = scoreOf(result, recentId)

        assertEquals(freshScore - 10.0f, recentScore, 0.001f)
    }

    @Test
    fun `candidates with recorded playback failures receive failure penalty`() = runTest {
        val failingId = "failing"
        val healthyId = "healthy"

        coEvery { historyRepository.getFailureCount(failingId) } returns 2
        coEvery { historyRepository.getFailureCount(healthyId) } returns 0

        val failing = candidate(failingId)
        val healthy = candidate(healthyId)

        val result = engine.rankCandidates(
            candidates = listOf(failing, healthy),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        val healthyScore = scoreOf(result, healthyId)
        val failingScore = scoreOf(result, failingId)

        assertEquals(healthyScore - 5.0f, failingScore, 0.001f)
    }

    @Test
    fun `diversity guard penalizes candidate matching previous location or country`() = runTest {
        val previous = candidate("previous", location = "Banff", country = "Canada")
        val sameLoc = candidate("sameLoc", location = "Banff", country = "USA")
        val sameCountry = candidate("sameCountry", location = "Jasper", country = "Canada")
        val distinct = candidate("distinct", location = "Kyoto", country = "Japan")

        val result = engine.rankCandidates(
            candidates = listOf(sameLoc, sameCountry, distinct),
            activeChannel = AmbientChannel.FOR_YOU,
            lastPlayedCandidate = previous,
            nowMs = nowMs
        )

        val distinctScore = scoreOf(result, "distinct")
        assertTrue(scoreOf(result, "sameLoc") < distinctScore)
        assertTrue(scoreOf(result, "sameCountry") < distinctScore)
    }

    @Test
    fun `category bonus from recentWatchContext is applied`() = runTest {
        every { recentWatchContext.getCategoryBonus("space", nowMs) } returns 0.35f
        every { recentWatchContext.getCategoryBonus("nature", nowMs) } returns 0.0f

        val spaceCandidate = candidate("space", category = "space")
        val natureCandidate = candidate("nature", category = "nature")

        val result = engine.rankCandidates(
            candidates = listOf(spaceCandidate, natureCandidate),
            activeChannel = AmbientChannel.FOR_YOU,
            nowMs = nowMs
        )

        val natureScore = scoreOf(result, "nature")
        assertEquals(natureScore + 0.35f, scoreOf(result, "space"), 0.001f)
    }
}
