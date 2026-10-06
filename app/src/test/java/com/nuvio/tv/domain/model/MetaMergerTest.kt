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
    @Test
    fun mergeAnonymousIdempotentPreservesDuplicates() {
        val anonymous = video(1, 1).copy(id = "", season = null, episode = null, title = "anonymous")
        val primary = createTestMeta(videos = listOf(anonymous, anonymous))
        val secondary = createTestMeta(videos = listOf(anonymous, anonymous))
        val merged = MetaMerger.merge(primary, secondary)
        assertEquals(2, merged.videos.size)
        assertEquals(merged, MetaMerger.merge(merged, secondary))
    }

    @Test
    fun mergeAnonymousIdempotentDistinctBlankTitles() {
        val a = video(1, 1).copy(id = "", season = null, episode = null, title = "a")
        val b = video(1, 1).copy(id = "", season = null, episode = null, title = "b")
        val primary = createTestMeta(videos = listOf(a))
        val secondary = createTestMeta(videos = listOf(b))
        val merged = MetaMerger.merge(primary, secondary)
        assertEquals(2, merged.videos.size)
    }
    @org.junit.Test
    fun testPrimaryWinsWhenSameIdAndImdbIdDiffers() {
        val primary = createTestMeta(id = "show", name = "Show", videos = emptyList()).copy(imdbId = "tt111")
        val secondary = createTestMeta(id = "show", name = "Show", videos = listOf(video(9, 1))).copy(imdbId = "tt222")
        val merged = MetaMerger.merge(primary, secondary)
        org.junit.Assert.assertEquals(primary, merged)
    }

    @org.junit.Test
    fun testMergeVideosWhenIdsDiffer() {
        val primary = createTestMeta(id = "stub", name = "Show", videos = listOf(video(10, 1)))
        val secondary = createTestMeta(id = "catalog", name = "Show", videos = seasonEpisodes(1, 10))
        val merged = MetaMerger.merge(primary, secondary)
        org.junit.Assert.assertEquals("stub", merged.id)
        org.junit.Assert.assertEquals(11, merged.videos.size)
    }

    @org.junit.Test
    fun testProviderDeterministicPrecedenceFirstWins() {
        val primary = createTestMeta(id = "show", name = "Show", videos = listOf(video(1, 1).copy(thumbnail = "")))
        val secondaryA = createTestMeta(id = "show", name = "Show", videos = listOf(video(1, 1).copy(thumbnail = "thumbA")))
        val secondaryB = createTestMeta(id = "show", name = "Show", videos = listOf(video(1, 1).copy(thumbnail = "thumbB")))
        val merged = MetaMerger.mergeAll(primary, listOf(secondaryA, secondaryB))
        org.junit.Assert.assertEquals("thumbA", merged.videos.first { it.season == 1 && it.episode == 1 }.thumbnail)
    }

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
    fun `mergeAll keeps primary as canonical base and merges others in order`() {
        val primary = createTestMeta(
            id = "primary",
            videos = seasonEpisodes(season = 1, count = 10),
        )

        val extra = createTestMeta(
            id = "primary",
            videos = listOf(video(season = 2, episode = 1)),
        )

        val merged = MetaMerger.mergeAll(primary, listOf(extra))

        assertNotNull(merged)
        assertEquals("primary", merged.id)

        val seasons = merged.videos.mapNotNull { it.season }.distinct().sorted()
        assertEquals(listOf(1, 2), seasons)
        assertEquals(11, merged.videos.size)
    }

    @Test
    fun `merge rejects secondary with different identity`() {
        val primary = createTestMeta(
            id = "tt1234567",
            videos = seasonEpisodes(season = 1, count = 3),
        )
        val secondary = createTestMeta(
            id = "tt9999999",
            videos = listOf(video(season = 5, episode = 1)),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(3, merged.videos.size)
        assertFalse(merged.videos.any { it.season == 5 })
    }

    @Test
    fun `merge rejects same namespace conflict before shared title`() {
        val primary = createTestMeta(
            id = "tmdb:111",
            name = "Same Show",
            videos = seasonEpisodes(season = 1, count = 2),
        )
        val secondary = createTestMeta(
            id = "tmdb:222",
            name = "Same Show",
            videos = listOf(video(season = 9, episode = 1)),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(2, merged.videos.size)
        assertFalse(merged.videos.any { it.season == 9 })
    }

    @Test
    fun `merge accepts same exact id with localized titles`() {
        val primary = createTestMeta(
            id = "tt1234567",
            name = "Original Title",
            videos = seasonEpisodes(season = 1, count = 2),
        )
        val secondary = createTestMeta(
            id = "tt1234567",
            name = "Titulo Localizado",
            videos = listOf(video(season = 2, episode = 1)),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(3, merged.videos.size)
        assertTrue(merged.videos.any { it.season == 2 && it.episode == 1 })
    }

    @Test
    fun `merge preserves invalid coordinates via id fallback`() {
        val primary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "special-a", title = "Special A", season = null, episode = null, released = null, thumbnail = null, overview = null),
                Video(id = "special-b", title = "Special B", season = null, episode = null, released = null, thumbnail = null, overview = null),
            ),
        )
        val secondary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "special-a", title = "Special A", season = null, episode = null, released = null, thumbnail = null, overview = null),
                Video(id = "special-c", title = "Special C", season = null, episode = null, released = null, thumbnail = null, overview = null),
            ),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(3, merged.videos.size)
        assertTrue(merged.videos.any { it.id == "special-a" })
        assertTrue(merged.videos.any { it.id == "special-b" })
        assertTrue(merged.videos.any { it.id == "special-c" })
    }

    @Test
    fun `merge preserves blank entries with unique keys`() {
        val primary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "", title = "Blank 1", season = null, episode = null, released = null, thumbnail = null, overview = null),
                Video(id = "", title = "Blank 2", season = null, episode = null, released = null, thumbnail = null, overview = null),
            ),
        )
        val secondary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "", title = "Blank 3", season = null, episode = null, released = null, thumbnail = null, overview = null),
            ),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(3, merged.videos.size)
    }

    @Test
    fun `merge distinguishes missing season from special zero`() {
        val primary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "missing", title = "Missing", season = null, episode = 1, released = null, thumbnail = null, overview = null),
            ),
        )
        val secondary = createTestMeta(
            id = "show",
            videos = listOf(
                Video(id = "special", title = "Special", season = 0, episode = 1, released = null, thumbnail = null, overview = null),
            ),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(2, merged.videos.size)
    }

    @Test
    fun `merge enriches same count without adding episodes`() {
        val primary = createTestMeta(
            id = "show",
            videos = listOf(
                video(season = 1, episode = 1, thumbnail = null, overview = null),
            ),
        )
        val secondary = createTestMeta(
            id = "show",
            videos = listOf(
                video(season = 1, episode = 1, thumbnail = "thumb", overview = "overview"),
            ),
        )

        val merged = MetaMerger.merge(primary, secondary)

        assertEquals(1, merged.videos.size)
        assertEquals("thumb", merged.videos.first().thumbnail)
        assertEquals("overview", merged.videos.first().overview)
    }

    @Test
    fun `mergeAll skips other identical to primary`() {
        val primary = createTestMeta(
            id = "show",
            videos = seasonEpisodes(season = 1, count = 2),
        )

        val merged = MetaMerger.mergeAll(primary, listOf(primary))

        assertEquals(2, merged.videos.size)
    }
}
