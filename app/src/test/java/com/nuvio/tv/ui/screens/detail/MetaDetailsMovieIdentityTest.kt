package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MetaDetailsMovieIdentityTest {
    @Test
    fun `movie metadata keeps the verified imdb identity despite addon tmdb echo`() {
        val original = meta("tmdb:123")
        val canonical = original.withResolvedMovieId("tmdb:123", "tt0000123")

        assertEquals("tt0000123", canonical.id)
        assertEquals(original, canonical.copy(id = original.id))
    }

    @Test
    fun `already canonical metadata remains unchanged`() {
        val original = meta("tt0000123")
        assertSame(original, original.withResolvedMovieId("tmdb:123", "tt0000123"))
    }

    @Test
    fun `series metadata and episode identities remain unchanged`() {
        val episode = Video(
            id = "tmdb:123:2:4", title = "Episode", released = null,
            thumbnail = null, season = 2, episode = 4, overview = null
        )
        val original = meta("tmdb:123").copy(type = ContentType.SERIES, rawType = "series", videos = listOf(episode))
        val result = original.withResolvedMovieId("tmdb:123", "tt0000123")

        assertSame(original, result)
        assertEquals("tmdb:123:2:4", result.videos.single().id)
    }

    @Test
    fun `unresolved or malformed ids do not rewrite movie metadata`() {
        val original = meta("tmdb:123")
        listOf("", "tmdb:123", "tt", "tt123:1:2", "ttinvalid").forEach { resolved ->
            assertSame(original, original.withResolvedMovieId("tmdb:123", resolved))
        }
        listOf("", "tt0000123", "tmdb:invalid", "tmdb:123:1:2").forEach { requested ->
            assertSame(original, original.withResolvedMovieId(requested, "tt0000123"))
        }
    }

    @Test
    fun `metadata for a different tmdb movie is not relabeled`() {
        val original = meta("tmdb:456")
        assertSame(original, original.withResolvedMovieId("tmdb:123", "tt0000123"))
    }

    @Test
    fun `conflicting addon imdb claim is not overwritten`() {
        val original = meta("tmdb:123").copy(imdbId = "tt0000456")
        assertSame(original, original.withResolvedMovieId("tmdb:123", "tt0000123"))
    }

    @Test
    fun `matching or blank addon imdb claim allows verified identity`() {
        listOf("tt0000123", "").forEach { claim ->
            val original = meta("tmdb:123").copy(imdbId = claim)
            assertEquals("tt0000123", original.withResolvedMovieId("tmdb:123", "tt0000123").id)
        }
    }

    @Test
    fun `canonicalizing metadata twice is idempotent`() {
        val canonical = meta("tmdb:123").withResolvedMovieId("tmdb:123", "tt0000123")
        assertSame(canonical, canonical.withResolvedMovieId("tmdb:123", "tt0000123"))
    }

    private fun meta(id: String) = Meta(
        id = id, type = ContentType.MOVIE, name = "Sample movie", poster = "/poster.jpg",
        posterShape = PosterShape.POSTER, background = "/background.jpg", logo = null,
        description = "Description", releaseInfo = "2024", imdbRating = 7.5f,
        genres = listOf("Drama"), runtime = "120", director = listOf("Director"),
        cast = listOf("Actor"), videos = emptyList(), country = "US", awards = null,
        language = "en", links = emptyList()
    )
}
