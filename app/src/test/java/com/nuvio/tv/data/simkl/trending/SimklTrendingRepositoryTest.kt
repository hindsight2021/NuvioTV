package com.nuvio.tv.data.simkl.trending

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.screens.home.HomeTab
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklTrendingRepositoryTest {

    private val sampleJson = """
        {
          "tv": [
            {
              "title": "Lanterns",
              "poster": "20/20357975b9962aefa0",
              "fanart": "18/18323529692467f4f9",
              "ids": {
                "simkl_id": 1247732,
                "imdb": "tt26545992",
                "tmdb": "95350"
              },
              "overview": "Intergalactic cops mystery.",
              "ratings": {
                "imdb": { "rating": 7.8, "votes": 45000 }
              },
              "trailer": "7UIBOsuUwc4",
              "genres": ["Action", "Sci-Fi"]
            }
          ],
          "movies": [
            {
              "title": "Coyote vs. Acme",
              "poster": "20/206165290b264c0f2d",
              "fanart": "20/20590235c4b24fc11b",
              "ids": {
                "simkl_id": 2297101,
                "imdb": "tt1756855",
                "tmdb": "1204680"
              },
              "overview": "Wile E. Coyote hires a lawyer.",
              "ratings": {
                "imdb": { "rating": 7.4, "votes": 34000 }
              },
              "trailer": "kMsiD1Nky5I",
              "genres": ["Animation", "Comedy"]
            }
          ],
          "anime": [
            {
              "title": "Mushoku Tensei",
              "poster": "20/2021633876c3baf055",
              "ids": {
                "simkl_id": 2832226,
                "tmdb": "94664"
              },
              "overview": "Jobless reincarnation season 3.",
              "ratings": {
                "simkl": { "rating": 8.8, "votes": 580 }
              },
              "genres": ["Fantasy", "Isekai"]
            }
          ]
        }
    """.trimIndent()

    private fun createRepository(jsonResponse: String, statusCode: Int = 200): SimklTrendingRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(jsonResponse.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return SimklTrendingRepository(client)
    }

    @Test
    fun `parses and interleaves trending tv and movies in combined mode`() = runBlocking {
        val repo = createRepository(sampleJson)
        val items = repo.getTrending(tab = null, separateMoviesTv = false)

        assertEquals(3, items.size)
        // TV is first, Movie is second, Anime is third
        assertEquals("tt26545992", items[0].id)
        assertEquals("Lanterns", items[0].name)
        assertEquals(ContentType.SERIES, items[0].type)
        assertEquals("TRENDING", items[0].badgeText)
        assertEquals("https://simkl.in/posters/20/20357975b9962aefa0_m.webp", items[0].poster)
        assertEquals("https://simkl.in/fanart/18/18323529692467f4f9_medium.webp", items[0].background)
        assertEquals(listOf("7UIBOsuUwc4"), items[0].trailerYtIds)

        assertEquals("tt1756855", items[1].id)
        assertEquals("Coyote vs. Acme", items[1].name)
        assertEquals(ContentType.MOVIE, items[1].type)

        assertEquals("tmdb:94664", items[2].id)
        assertEquals("Mushoku Tensei", items[2].name)
        assertEquals(ContentType.SERIES, items[2].type)
    }

    @Test
    fun `filters to tv and anime when separateMoviesTv is true and TV tab is selected`() = runBlocking {
        val repo = createRepository(sampleJson)
        val items = repo.getTrending(tab = HomeTab.TV_SHOWS, separateMoviesTv = true)

        assertEquals(2, items.size)
        assertEquals("Lanterns", items[0].name)
        assertEquals(ContentType.SERIES, items[0].type)
        assertEquals("Mushoku Tensei", items[1].name)
        assertEquals(ContentType.SERIES, items[1].type)
    }

    @Test
    fun `filters to movies when separateMoviesTv is true and Movies tab is selected`() = runBlocking {
        val repo = createRepository(sampleJson)
        val items = repo.getTrending(tab = HomeTab.MOVIES, separateMoviesTv = true)

        assertEquals(1, items.size)
        assertEquals("Coyote vs. Acme", items[0].name)
        assertEquals(ContentType.MOVIE, items[0].type)
    }

    @Test
    fun `handles empty or failed responses gracefully`() = runBlocking {
        val repo = createRepository("", statusCode = 500)
        val items = repo.getTrending(tab = null, separateMoviesTv = false)
        assertTrue(items.isEmpty())
    }
}
