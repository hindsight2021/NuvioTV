package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MetaMerger].
 */
class MetaMergerTest {

    private fun video(
        season: Int,
        episode: Int,
        thumbnail: String? = null,
        overview: String? = null,
    ): Video = Video(
        id = "s${season}e$episode",
        title = "S${season}E$episode",
        season = season,
        episode = episode,
        thumbnail = thumbnail,
        overview = overview,
        released = null
    )

    private fun createTestMeta(
        id: String = "show",
        name: String = "Show",
        videos: List<Video> = emptyList(),
        background: String? = null,
        poster: String? = null,
    ): Meta = Meta(
        id = id,
        type = ContentType.SERIES,
        rawType = "series",
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = "2024-",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = videos,
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )

    private fun seasonEpisodes(season: Int, count: Int): List<Video> =
        (1..count).map { video(season = season, episode = it) }

    @Test
    fun `isStub returns true for single episode`() {
        val stub = createTestMeta(videos = listOf(video(season = 1, episode = 1)))
        assertTrue(MetaMerger.isStub(stub))
    }

    @Test
    fun `isStub returns true for two episodes in a season greater than one`() {
        val stub = createTestMeta(
            videos = listOf(
                video(season = 2, episode = 1),
                video(season = 2, episode = 2),
            ),
        )
        assertTrue(MetaMerger.isStub(stub))
    }

    @Test
    fun `isStub returns false for normal show with ten episodes`() {
        val show = createTestMeta(videos = seasonEpisodes(season = 1, count = 10))
        assertFalse(MetaMerger.isStub(show))
    }

    @Test
    fun `merge combines primary seasons one through nine with secondary season ten`() {
        val primaryVideos = (1..9).flatMap { seasonEpisodes(season = it, count = 2) }
        val primary = createTestMeta(id = "primary", videos = primaryVideos)

        val secondary = createTestMeta(
            id = "secondary",
            videos = listOf(video(season = 10, episode = 1)),
        )

        val merged = MetaMerger.merge(primary, secondary)

        val seasons = merged.videos.mapNotNull { it.season }.distinct().sorted()
        assertEquals((1..10).toList(), seasons)

        assertEquals(primaryVideos.size + 1, merged.videos.size)
        assertTrue(merged.videos.any { it.season == 10 && it.episode == 1 })
    }

    @Test
    fun `merge fills missing thumbnail and overview from secondary`() {
        val primary = createTestMeta(
            id = "primary",
            videos = listOf(
                video(season = 1, episode = 1, thumbnail = null, overview = null),
                video(season = 1, episode = 2, thumbnail = "primary-thumb", overview = "primary-overview"),
            ),
        )

        val secondary = createTestMeta(
            id = "secondary",
            videos = listOf(
                video(season = 1, episode = 1, thumbnail = "secondary-thumb", overview = "secondary-overview"),
                video(season = 1, episode = 2, thumbnail = "secondary-thumb-2", overview = "secondary-overview-2"),
            ),
        )

        val merged = MetaMerger.merge(primary, secondary)

        val episodeOne = merged.videos.first { it.season == 1 && it.episode == 1 }
        assertEquals("secondary-thumb", episodeOne.thumbnail)
        assertEquals("secondary-overview", episodeOne.overview)

        val episodeTwo = merged.videos.first { it.season == 1 && it.episode == 2 }
        assertEquals("primary-thumb", episodeTwo.thumbnail)
        assertEquals("primary-overview", episodeTwo.overview)
    }

    @Test
    fun `mergeAll picks non-stub base and merges episodes across multiple sources`() {
        val stub = createTestMeta(
            id = "stub",
            videos = listOf(video(season = 10, episode = 1)),
        )

        val base = createTestMeta(
            id = "base",
            videos = seasonEpisodes(season = 1, count = 10),
        )

        val extra = createTestMeta(
            id = "extra",
            videos = listOf(video(season = 2, episode = 1)),
        )

        val merged = MetaMerger.mergeAll(stub, listOf(base, extra))

        assertNotNull(merged)
        assertEquals("base", merged.id)

        val seasons = merged.videos.mapNotNull { it.season }.distinct().sorted()
        assertEquals(listOf(1, 2, 10), seasons)

        assertEquals(12, merged.videos.size)
        assertTrue(merged.videos.any { it.season == 10 && it.episode == 1 })
    }
}
