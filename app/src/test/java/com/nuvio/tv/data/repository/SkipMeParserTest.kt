package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipMeParserTest {

    @Test
    fun formatAParsesAndFiltersBySeasonAndEpisode() {
        val json = """
            [
              {
                "segments": [
                  { "season": 1, "episode": 1, "segment": "intro", "start_ms": 1000, "end_ms": 20000, "submissions": 5 },
                  { "season": 1, "episode": 2, "segment": "intro", "start_ms": 2000, "end_ms": 22000, "submissions": 3 },
                  { "season": 2, "episode": 1, "segment": "intro", "start_ms": 3000, "end_ms": 23000, "submissions": 2 }
                ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = false, season = 1, episode = 1)

        assertEquals(1, result.size)
        val interval = result.first()
        assertEquals(1.0, interval.startTime, 0.0001)
        assertEquals(20.0, interval.endTime, 0.0001)
        assertEquals("intro", interval.type)
        assertEquals("skipme", interval.provider)
        assertTrue(interval.confidence in 0.7..0.99)
    }

    @Test
    fun formatAMapsCreditsToOutroForTvShow() {
        val json = """
            [
              {
                "segments": [
                  { "season": 3, "episode": 4, "segment": "credits", "start_ms": 1000, "end_ms": 90000, "submissions": 7 }
                ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = false, season = 3, episode = 4)

        assertEquals(1, result.size)
        assertEquals("outro", result.first().type)
    }

    @Test
    fun formatAMapsCreditsToMovieCreditsForMovie() {
        val json = """
            [
              {
                "segments": [
                  { "segment": "credits", "start_ms": 6000000, "end_ms": 6300000, "submissions": 12 }
                ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = true)

        assertEquals(1, result.size)
        assertEquals("movie-credits", result.first().type)
        assertEquals(6000.0, result.first().startTime, 0.0001)
        assertEquals(6300.0, result.first().endTime, 0.0001)
    }

    @Test
    fun formatAComputesConfidenceFromSubmissions() {
        val json = """
            [
              {
                "segments": [
                  { "season": 1, "episode": 1, "segment": "intro", "start_ms": 0, "end_ms": 10000, "submissions": 1 },
                  { "season": 1, "episode": 1, "segment": "credits", "start_ms": 10000, "end_ms": 20000, "submissions": 50 }
                ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = false, season = 1, episode = 1)

        assertEquals(2, result.size)
        val intro = result.first { it.type == "intro" }
        val outro = result.first { it.type == "outro" }
        assertTrue("Higher submissions should yield higher confidence", intro.confidence < outro.confidence)
        assertTrue(intro.confidence in 0.7..0.99)
        assertTrue(outro.confidence in 0.7..0.99)
    }

    @Test
    fun formatBParsesAllCategories() {
        val json = """
            [
              {
                "intro":   [ { "start_ms": 1000, "end_ms": 30000, "submissions": 10 } ],
                "recap":   [ { "start_ms": 0,    "end_ms": 1000,  "submissions": 4  } ],
                "credits": [ { "start_ms": 90000, "end_ms": 120000, "submissions": 8 } ],
                "preview": [ { "start_ms": 120000, "end_ms": 150000, "submissions": 3 } ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = false, season = 1, episode = 1)

        assertEquals(4, result.size)
        assertTrue(result.any { it.type == "intro" && it.startTime == 1.0 && it.endTime == 30.0 })
        assertTrue(result.any { it.type == "recap" && it.startTime == 0.0 && it.endTime == 1.0 })
        assertTrue(result.any { it.type == "outro" && it.startTime == 90.0 && it.endTime == 120.0 })
        assertTrue(result.any { it.type == "preview" && it.startTime == 120.0 && it.endTime == 150.0 })
    }

    @Test
    fun invalidSegmentsIgnored() {
        val json = """
            [
              {
                "segments": [
                  { "season": 1, "episode": 1, "segment": "intro", "start_ms": -1000, "end_ms": 10000, "submissions": 5 },
                  { "season": 1, "episode": 1, "segment": "intro", "start_ms": 10000, "end_ms": 10000, "submissions": 5 },
                  { "season": 1, "episode": 1, "segment": "credits", "start_ms": 20000, "end_ms": 15000, "submissions": 5 }
                ]
              }
            ]
        """.trimIndent()

        val result = SkipIntroRepository.parseSkipMe(json, isMovie = false, season = 1, episode = 1)

        assertTrue(result.isEmpty())
    }

    @Test
    fun malformedOrEmptyJsonHandledGracefully() {
        assertTrue(SkipIntroRepository.parseSkipMe("", isMovie = false).isEmpty())
        assertTrue(SkipIntroRepository.parseSkipMe("{}", isMovie = false).isEmpty())
        assertTrue(SkipIntroRepository.parseSkipMe("[]", isMovie = false).isEmpty())
        assertTrue(SkipIntroRepository.parseSkipMe("{ not json }", isMovie = false).isEmpty())
    }
}
