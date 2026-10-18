package com.nuvio.tv.core.poster

import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterUrlResolverTest {

    @Test
    fun `resolvePosterUrl returns null when provider is OFF`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.OFF,
            stremioId = "tt1234567",
            imdbId = "tt1234567"
        )
        assertNull(result)
    }

    @Test
    fun `resolvePosterUrl returns null when stremioId is null or blank`() {
        assertNull(
            PosterUrlResolver.resolvePosterUrl(
                provider = PosterProvider.POSTERS_PLUS,
                stremioId = null
            )
        )
        assertNull(
            PosterUrlResolver.resolvePosterUrl(
                provider = PosterProvider.POSTERS_PLUS,
                stremioId = "   "
            )
        )
    }

    @Test
    fun `POSTERS_PLUS resolves landscape when preferLandscape is true`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.POSTERS_PLUS,
            stremioId = "tt0903747",
            imdbId = "tt0903747",
            type = "series",
            preferLandscape = true
        )
        assertNotNull(result)
        assertTrue(result!!.contains("shape=landscape"))
        assertTrue(result.contains("type=series"))
        assertTrue(result.contains("stremio_id=tt0903747"))
        assertTrue(result.contains("imdb_id=tt0903747"))
    }

    @Test
    fun `POSTERS_PLUS resolves poster when preferLandscape is false and shape is POSTER`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.POSTERS_PLUS,
            stremioId = "tt0111161",
            imdbId = "tt0111161",
            type = "movie",
            shape = PosterShape.POSTER,
            preferLandscape = false
        )
        assertNotNull(result)
        assertTrue(result!!.contains("shape=poster"))
        assertTrue(result.contains("type=movie"))
        assertTrue(result.contains("stremio_id=tt0111161"))
    }

    @Test
    fun `POSTERS_PLUS extracts effective IMDb ID from stremioId when imdbId is null`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.POSTERS_PLUS,
            stremioId = "tt1234567:2:5",
            imdbId = null,
            type = "series"
        )
        assertNotNull(result)
        assertTrue(result!!.contains("imdb_id=tt1234567"))
        assertTrue(result.contains("stremio_id=tt1234567:2:5"))
    }

    @Test
    fun `RPDB returns correct poster url when key and imdbId are provided`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.RPDB,
            stremioId = "tt1234567",
            imdbId = "tt1234567",
            rpdbApiKey = "my_rpdb_key",
            preferLandscape = false,
            shape = PosterShape.POSTER
        )
        assertEquals(
            "https://api.ratingposterdb.com/my_rpdb_key/imdb/poster-default/tt1234567.jpg",
            result
        )
    }

    @Test
    fun `RPDB returns correct backdrop url when preferLandscape is true`() {
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.RPDB,
            stremioId = "tt1234567",
            imdbId = "tt1234567",
            rpdbApiKey = "my_rpdb_key",
            preferLandscape = true
        )
        assertEquals(
            "https://api.ratingposterdb.com/my_rpdb_key/imdb/backdrop-default/tt1234567.jpg",
            result
        )
    }

    @Test
    fun `RPDB returns null when key or imdbId is missing`() {
        assertNull(
            PosterUrlResolver.resolvePosterUrl(
                provider = PosterProvider.RPDB,
                stremioId = "kitsu:12",
                imdbId = null,
                rpdbApiKey = "my_rpdb_key"
            )
        )
        assertNull(
            PosterUrlResolver.resolvePosterUrl(
                provider = PosterProvider.RPDB,
                stremioId = "tt1234567",
                imdbId = "tt1234567",
                rpdbApiKey = ""
            )
        )
    }

    @Test
    fun `CUSTOM resolves custom pattern with all placeholders replaced`() {
        val pattern = "https://custom.art/{type}/{shape}/{id}?imdb={imdb_id}&tmdb={tmdb_id}&key={api_key?}"
        val result = PosterUrlResolver.resolvePosterUrl(
            provider = PosterProvider.CUSTOM,
            stremioId = "tt0903747",
            imdbId = "tt0903747",
            tmdbId = "1396",
            type = "series",
            shape = PosterShape.LANDSCAPE,
            customPattern = pattern,
            rpdbApiKey = "secret"
        )
        assertEquals(
            "https://custom.art/series/landscape/tt0903747?imdb=tt0903747&tmdb=1396&key=secret",
            result
        )
    }

    @Test
    fun `PosterProvider fromId handles unknown and null gracefully`() {
        assertEquals(PosterProvider.OFF, PosterProvider.fromId(null))
        assertEquals(PosterProvider.OFF, PosterProvider.fromId("unknown"))
        assertEquals(PosterProvider.POSTERS_PLUS, PosterProvider.fromId("posters_plus"))
        assertEquals(PosterProvider.RPDB, PosterProvider.fromId("rpdb"))
        assertEquals(PosterProvider.CUSTOM, PosterProvider.fromId("custom"))
    }
}
