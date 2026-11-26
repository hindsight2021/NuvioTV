package com.nuvio.tv.core.playlist

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.data.local.RandomEpisodeHistoryStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class SmartRandomEpisodeSelectorTest {

    private lateinit var watchProgressPreferences: WatchProgressPreferences
    private lateinit var watchedItemsPreferences: WatchedItemsPreferences
    private lateinit var traktProvider: TrackingProgressProvider
    private lateinit var simklProvider: TrackingProgressProvider
    private lateinit var registry: TrackingProgressProviderRegistry
    private lateinit var historyStore: RandomEpisodeHistoryStore
    private lateinit var selector: SmartRandomEpisodeSelector

    private val now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        watchProgressPreferences = mockk(relaxed = true)
        watchedItemsPreferences = mockk(relaxed = true)
        traktProvider = mockk(relaxed = true)
        simklProvider = mockk(relaxed = true)
        historyStore = mockk(relaxed = true)

        every { traktProvider.providerId } returns TrackingProviderId.TRAKT
        every { simklProvider.providerId } returns TrackingProviderId.SIMKL
        every { traktProvider.allProgress } returns flowOf(emptyList())
        every { simklProvider.allProgress } returns flowOf(emptyList())
        every { traktProvider.watchedItems } returns flowOf(emptyList())
        every { simklProvider.watchedItems } returns flowOf(emptyList())
        coEvery { traktProvider.watchedShowEpisodes() } returns emptyMap()
        coEvery { simklProvider.watchedShowEpisodes() } returns emptyMap()
        coEvery { traktProvider.showIdSiblings() } returns emptyMap()
        coEvery { simklProvider.showIdSiblings() } returns emptyMap()

        registry = TrackingProgressProviderRegistry(setOf(traktProvider, simklProvider))

        selector = SmartRandomEpisodeSelector(
            watchProgressPreferences = watchProgressPreferences,
            watchedItemsPreferences = watchedItemsPreferences,
            trackingProgressProviders = registry,
            randomEpisodeHistoryStore = historyStore
        )
    }

    private fun video(season: Int, episode: Int): Video = Video(
        id = "v-$season-$episode",
        title = "Episode $episode",
        released = "2020-01-01",
        thumbnail = null,
        streams = emptyList(),
        season = season,
        episode = episode,
        overview = null,
        available = true
    )

    private fun progress(
        contentId: String,
        videoId: String,
        season: Int,
        episode: Int,
        lastWatched: Long,
        position: Long = 1000L,
        duration: Long = 1000L
    ): WatchProgress = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = null,
        source = WatchProgress.SOURCE_LOCAL
    )

    private fun watchedItem(
        contentId: String,
        season: Int,
        episode: Int,
        watchedAt: Long
    ): WatchedItem = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = "Show",
        season = season,
        episode = episode,
        watchedAt = watchedAt
    )

    @Test
    fun `selects unwatched episode when Nuvio Trakt and Simkl mark different episodes watched`() = runTest {
        val contentId = "tt12345"
        val episodes = listOf(video(1, 1), video(1, 2), video(1, 3), video(1, 4))

        every { watchProgressPreferences.allProgress } returns flowOf(
            listOf(progress(contentId, "v-1-1", 1, 1, now - 60_000L))
        )
        every { watchedItemsPreferences.allItems } returns flowOf(emptyList())

        every { traktProvider.isAuthenticated } returns flowOf(true)
        every { traktProvider.episodeProgress(contentId) } returns flowOf(
            mapOf((1 to 2) to progress(contentId, "v-1-2", 1, 2, now - 120_000L))
        )

        every { simklProvider.isAuthenticated } returns flowOf(true)
        every { simklProvider.episodeProgress(contentId) } returns flowOf(
            mapOf((1 to 3) to progress(contentId, "v-1-3", 1, 3, now - 180_000L))
        )

        coEvery { historyStore.getRecentPicks(any(), any()) } returns emptyList()

        val selection = selector.selectRandomAndQueue(
            contentId = contentId,
            episodes = episodes,
            recordPick = true,
            nowMs = now
        )

        assertNotNull(selection)
        assertEquals(1, selection!!.chosen.season)
        assertEquals(4, selection.chosen.episode)

        coVerify {
            historyStore.recordPick(
                contentId = contentId,
                season = 1,
                episode = 4,
                siblingIds = any(),
                nowMs = now,
                profileId = null
            )
        }
    }

    @Test
    fun `resolves sibling IDs across Trakt or Simkl to match recent watch history stored under imdb alias`() = runTest {
        val contentId = "tmdb:999"
        val episodes = listOf(video(1, 1), video(1, 2))

        every { watchProgressPreferences.allProgress } returns flowOf(emptyList())
        every { watchedItemsPreferences.allItems } returns flowOf(
            listOf(watchedItem("tt12345", 1, 1, now - 3_600_000L))
        )

        every { traktProvider.isAuthenticated } returns flowOf(true)
        every { traktProvider.episodeProgress(any()) } returns flowOf(emptyMap())
        coEvery { traktProvider.showIdSiblings() } returns mapOf("tmdb:999" to setOf("tt12345"))
        every { simklProvider.isAuthenticated } returns flowOf(false)
        coEvery { historyStore.getRecentPicks(any(), any()) } returns emptyList()

        val selection = selector.selectRandomAndQueue(
            contentId = contentId,
            episodes = episodes,
            nowMs = now
        )

        assertNotNull(selection)
        assertEquals(1, selection!!.chosen.season)
        assertEquals(2, selection.chosen.episode)
    }

    @Test
    fun `does not stall and falls back cleanly when a remote tracking provider throws`() = runTest {
        val contentId = "tt12345"
        val episodes = listOf(video(1, 1), video(1, 2))

        every { watchProgressPreferences.allProgress } returns flowOf(emptyList())
        every { watchedItemsPreferences.allItems } returns flowOf(emptyList())

        every { traktProvider.isAuthenticated } returns flowOf(true)
        every { traktProvider.episodeProgress(any()) } returns flow {
            throw RuntimeException("network error")
        }

        every { simklProvider.isAuthenticated } returns flowOf(true)
        every { simklProvider.episodeProgress(any()) } returns flowOf(emptyMap())
        every { simklProvider.watchedItems } returns flowOf(
            listOf(watchedItem(contentId, 1, 1, now - 60_000L))
        )
        coEvery { historyStore.getRecentPicks(any(), any()) } returns emptyList()

        val selection = selector.selectRandomAndQueue(
            contentId = contentId,
            episodes = episodes,
            nowMs = now
        )

        assertNotNull(selection)
        assertEquals(1, selection!!.chosen.season)
        assertEquals(2, selection.chosen.episode)
    }

    @Test
    fun `respects recent random pick history so same episode is not repeated`() = runTest {
        val contentId = "tt12345"
        val episodes = listOf(video(1, 1), video(1, 2))

        every { watchProgressPreferences.allProgress } returns flowOf(emptyList())
        every { watchedItemsPreferences.allItems } returns flowOf(emptyList())
        every { traktProvider.isAuthenticated } returns flowOf(false)
        every { simklProvider.isAuthenticated } returns flowOf(false)

        coEvery { historyStore.getRecentPicks(any(), any()) } returns listOf(
            RandomPickRecord(season = 1, episode = 1, pickedAtMs = now - 60_000L)
        )

        val selection = selector.selectRandomAndQueue(
            contentId = contentId,
            episodes = episodes,
            nowMs = now
        )

        assertNotNull(selection)
        assertEquals(1, selection!!.chosen.season)
        assertEquals(2, selection.chosen.episode)
    }
}
