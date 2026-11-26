package com.nuvio.tv.core.tmdb

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

class NewReleasesCatalogServiceTest {

    private fun fakePrefs(): SharedPreferences {
        val store = linkedMapOf<String, String?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            store[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } returns Unit

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } answers {
            store[firstArg()] ?: secondArg()
        }
        every { prefs.edit() } returns editor
        return prefs
    }

    private fun serviceWithMocks(
        tmdbApi: TmdbApi = mockk(relaxed = true),
        language: String = "en-US"
    ): NewReleasesCatalogService {
        val prefs = fakePrefs()
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        val settingsStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        every { settingsStore.settings } returns MutableStateFlow(TmdbSettings(language = language))

        return NewReleasesCatalogService(
            appContext = context,
            tmdbApi = tmdbApi,
            tmdbSettingsDataStore = settingsStore
        )
    }

    private fun discoverShow(
        id: Int,
        name: String,
        firstAirDate: String?,
        posterPath: String? = "/poster_$id.jpg",
        voteCount: Int = 25,
        voteAverage: Double = 7.8
    ) = TmdbDiscoverResult(
        id = id,
        name = name,
        firstAirDate = firstAirDate,
        posterPath = posterPath,
        backdropPath = "/bg_$id.jpg",
        overview = "Overview $id",
        voteAverage = voteAverage,
        voteCount = voteCount
    )

    @Test
    fun `buildNewReleasesItems sorts strictly by newest release date, filters unreleased, and deduplicates`() {
        val service = serviceWithMocks()
        val today = LocalDate.of(2026, 11, 26)

        val raw = listOf(
            discoverShow(1, "Older Show", "2026-11-10", voteCount = 200),
            discoverShow(2, "Unreleased Future Show", "2026-12-01", voteCount = 500),
            discoverShow(3, "Brand New Today", "2026-11-26", voteCount = 12),
            discoverShow(4, "Yesterday High Votes", "2026-11-25", voteCount = 80),
            discoverShow(5, "Yesterday Lower Votes", "2026-11-25", voteCount = 15),
            discoverShow(3, "Brand New Today Duplicate", "2026-11-26", voteCount = 12),
            discoverShow(6, "No Artwork", "2026-11-26", posterPath = null).copy(backdropPath = null)
        )

        val items = service.buildNewReleasesItems(raw, today)

        assertEquals(listOf("tmdb:3", "tmdb:4", "tmdb:5", "tmdb:1"), items.map { it.id })
        assertEquals("2026-11-26", items.first().released)
        assertEquals("2026", items.first().releaseInfo)
    }

    @Test
    fun `getOrRefreshDailyRow caches once per day and refreshes only when date changes`() = runTest {
        val api = mockk<TmdbApi>()
        val day1 = LocalDate.of(2026, 11, 26)
        val day2 = LocalDate.of(2026, 11, 27)

        val page1Day1 = (1..12).map { idx ->
            discoverShow(idx, "Show $idx", day1.minusDays(idx.toLong()).toString())
        }
        val page1Day2 = listOf(
            discoverShow(999, "Next Day Premiere", day2.toString(), voteCount = 50)
        ) + page1Day1

        coEvery {
            api.discoverTv(
                apiKey = any(),
                language = any(),
                page = 1,
                sortBy = "first_air_date.desc",
                withCompanies = any(),
                withNetworks = any(),
                firstAirDateLte = day1.toString(),
                voteCountGte = any(),
                withGenres = any(),
                firstAirDateGte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                withOriginalLanguage = any(),
                withOriginCountry = any(),
                withKeywords = any(),
                firstAirDateYear = any(),
                withStatus = any(),
                watchRegion = any(),
                withWatchProviders = any(),
                withWatchMonetizationTypes = any(),
                withoutCompanies = any(),
                withoutGenres = any(),
                withoutKeywords = any(),
                withoutWatchProviders = any()
            )
        } returns Response.success(TmdbDiscoverResponse(page = 1, results = page1Day1, totalPages = 2))

        coEvery {
            api.discoverTv(
                apiKey = any(),
                language = any(),
                page = 2,
                sortBy = "first_air_date.desc",
                withCompanies = any(),
                withNetworks = any(),
                firstAirDateLte = day1.toString(),
                voteCountGte = any(),
                withGenres = any(),
                firstAirDateGte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                withOriginalLanguage = any(),
                withOriginCountry = any(),
                withKeywords = any(),
                firstAirDateYear = any(),
                withStatus = any(),
                watchRegion = any(),
                withWatchProviders = any(),
                withWatchMonetizationTypes = any(),
                withoutCompanies = any(),
                withoutGenres = any(),
                withoutKeywords = any(),
                withoutWatchProviders = any()
            )
        } returns Response.success(TmdbDiscoverResponse(page = 2, results = emptyList(), totalPages = 2))

        coEvery {
            api.discoverTv(
                apiKey = any(),
                language = any(),
                page = 1,
                sortBy = "first_air_date.desc",
                withCompanies = any(),
                withNetworks = any(),
                firstAirDateLte = day2.toString(),
                voteCountGte = any(),
                withGenres = any(),
                firstAirDateGte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                withOriginalLanguage = any(),
                withOriginCountry = any(),
                withKeywords = any(),
                firstAirDateYear = any(),
                withStatus = any(),
                watchRegion = any(),
                withWatchProviders = any(),
                withWatchMonetizationTypes = any(),
                withoutCompanies = any(),
                withoutGenres = any(),
                withoutKeywords = any(),
                withoutWatchProviders = any()
            )
        } returns Response.success(TmdbDiscoverResponse(page = 1, results = page1Day2, totalPages = 2))

        coEvery {
            api.discoverTv(
                apiKey = any(),
                language = any(),
                page = 2,
                sortBy = "first_air_date.desc",
                withCompanies = any(),
                withNetworks = any(),
                firstAirDateLte = day2.toString(),
                voteCountGte = any(),
                withGenres = any(),
                firstAirDateGte = any(),
                voteAverageGte = any(),
                voteAverageLte = any(),
                withOriginalLanguage = any(),
                withOriginCountry = any(),
                withKeywords = any(),
                firstAirDateYear = any(),
                withStatus = any(),
                watchRegion = any(),
                withWatchProviders = any(),
                withWatchMonetizationTypes = any(),
                withoutCompanies = any(),
                withoutGenres = any(),
                withoutKeywords = any(),
                withoutWatchProviders = any()
            )
        } returns Response.success(TmdbDiscoverResponse(page = 2, results = emptyList(), totalPages = 2))

        val service = serviceWithMocks(tmdbApi = api)

        // First call on Day 1 fetches from TMDB and populates daily cache
        val firstRow = service.getOrRefreshDailyRow(today = day1)
        assertNotNull(firstRow)
        assertEquals("New Releases", firstRow!!.catalogName)
        assertEquals(12, firstRow.items.size)
        assertTrue(service.isCacheFreshForToday(today = day1, language = "en-US"))

        // Second call on Day 1 serves directly from daily cache (no extra network call!)
        val cachedRow = service.getOrRefreshDailyRow(today = day1)
        assertEquals(firstRow.items.map { it.id }, cachedRow?.items?.map { it.id })
        coVerify(exactly = 2) {
            api.discoverTv(
                apiKey = any(), language = any(), page = any(), sortBy = any(),
                withCompanies = any(), withNetworks = any(), firstAirDateLte = day1.toString(),
                voteCountGte = any(), withGenres = any(), firstAirDateGte = any(),
                voteAverageGte = any(), voteAverageLte = any(), withOriginalLanguage = any(),
                withOriginCountry = any(), withKeywords = any(), firstAirDateYear = any(),
                withStatus = any(), watchRegion = any(), withWatchProviders = any(),
                withWatchMonetizationTypes = any(), withoutCompanies = any(), withoutGenres = any(),
                withoutKeywords = any(), withoutWatchProviders = any()
            )
        }

        // Next day: cache is stale -> fetches Day 2 fresh releases!
        assertFalse(service.isCacheFreshForToday(today = day2, language = "en-US"))
        val day2Row = service.getOrRefreshDailyRow(today = day2)
        assertEquals("tmdb:999", day2Row?.items?.firstOrNull()?.id)
    }
}
