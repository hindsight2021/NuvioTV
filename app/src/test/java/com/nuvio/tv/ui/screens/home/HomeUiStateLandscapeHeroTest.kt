package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateLandscapeHeroTest {

    private fun sampleMeta(
        id: String,
        name: String,
        imdbId: String? = null,
        type: ContentType = ContentType.SERIES
    ): MetaPreview = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = "https://example.com/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://example.com/fanart.jpg",
        logo = null,
        description = "Description for $name",
        releaseInfo = "2026",
        imdbRating = 8.0f,
        genres = listOf("Drama"),
        imdbId = imdbId
    )

    private fun cwInProgress(contentId: String, name: String): ContinueWatchingItem =
        ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = contentId,
                contentType = "series",
                name = name,
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "$contentId:1:1",
                season = 1,
                episode = 1,
                episodeTitle = null,
                position = 1000L,
                duration = 3000L,
                lastWatched = System.currentTimeMillis()
            )
        )

    @Test
    fun `excludes items whose id matches a continue watching item`() {
        val state = HomeUiState(
            trendingHeroItems = listOf(
                sampleMeta("tt1111111", "Show A"),
                sampleMeta("tt2222222", "Show B")
            ),
            continueWatchingItems = listOf(
                cwInProgress("tt1111111", "Show A")
            )
        )

        val result = state.landscapeHeroItems
        assertEquals(1, result.size)
        assertEquals("tt2222222", result[0].id)
    }

    @Test
    fun `excludes items whose imdbId matches a continue watching item`() {
        val state = HomeUiState(
            trendingHeroItems = listOf(
                sampleMeta("simkl:9999", "Show A", imdbId = "tt1111111"),
                sampleMeta("simkl:8888", "Show B", imdbId = "tt2222222")
            ),
            continueWatchingItems = listOf(
                cwInProgress("tt1111111", "Different Name")
            )
        )

        val result = state.landscapeHeroItems
        assertEquals(1, result.size)
        assertEquals("simkl:8888", result[0].id)
    }

    @Test
    fun `excludes items whose normalized title matches continue watching item`() {
        val state = HomeUiState(
            trendingHeroItems = listOf(
                sampleMeta("tt1111111", "Coyote vs. Acme"),
                sampleMeta("tt2222222", "Breaking Bad")
            ),
            continueWatchingItems = listOf(
                cwInProgress("different_id", "coyote vs acme")
            )
        )

        val result = state.landscapeHeroItems
        assertEquals(1, result.size)
        assertEquals("tt2222222", result[0].id)
    }

    @Test
    fun `falls back to heroItems when trendingHeroItems is empty and filters continue watching`() {
        val state = HomeUiState(
            heroItems = listOf(
                sampleMeta("tt1111111", "Watched Show"),
                sampleMeta("tt3333333", "Fresh Show")
            ),
            trendingHeroItems = emptyList(),
            continueWatchingItems = listOf(
                cwInProgress("tt1111111", "Watched Show")
            )
        )

        val result = state.landscapeHeroItems
        assertEquals(1, result.size)
        assertEquals("tt3333333", result[0].id)
    }

    @Test
    fun `retains all trending items when continue watching is empty`() {
        val state = HomeUiState(
            trendingHeroItems = listOf(
                sampleMeta("tt1111111", "Show A"),
                sampleMeta("tt2222222", "Show B")
            ),
            continueWatchingItems = emptyList()
        )

        val result = state.landscapeHeroItems
        assertEquals(2, result.size)
    }
}
