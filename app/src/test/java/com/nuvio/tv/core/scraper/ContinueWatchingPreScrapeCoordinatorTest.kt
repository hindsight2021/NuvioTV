package com.nuvio.tv.core.scraper

import android.content.Context
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingPreScrapeCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var streamRepository: StreamRepository
    private lateinit var streamLinkCacheDataStore: StreamLinkCacheDataStore
    private lateinit var playerSettingsDataStore: PlayerSettingsDataStore
    private lateinit var debridSettingsDataStore: DebridSettingsDataStore
    private lateinit var directDebridResolver: DirectDebridResolver
    private lateinit var addonRepository: AddonRepository
    private lateinit var bingeGroupCacheDataStore: BingeGroupCacheDataStore

    private lateinit var coordinator: ContinueWatchingPreScrapeCoordinator

    private val testProgress = WatchProgress(
        contentId = "tt1234567",
        contentType = "series",
        name = "Test Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1234567:1:1",
        season = 1,
        episode = 1,
        episodeTitle = "Pilot",
        position = 1000L,
        duration = 5000L,
        lastWatched = System.currentTimeMillis()
    )

    private val testItem = ContinueWatchingItem.InProgress(progress = testProgress)

    private val testStream = Stream(
        name = "RealDebrid 4K",
        title = "Test Stream",
        description = null,
        url = "https://example.com/stream.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Torrentio",
        addonLogo = null
    )

    private fun defaultPlayerSettings(
        preScrapeEnabled: Boolean = true,
        preScrapeCount: Int = 3
    ) = PlayerSettings(
        continueWatchingPreScrapeEnabled = preScrapeEnabled,
        continueWatchingPreScrapeCount = preScrapeCount,
        streamReuseLastLinkCacheHours = 24,
        streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
        streamAutoPlayRegex = null,
        streamAutoPlaySource = null,
        streamAutoPlaySelectedAddons = emptyList(),
        streamAutoPlaySelectedPlugins = emptyList(),
        streamAutoPlayPreferBingeGroupForNextEpisode = false
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        streamRepository = mockk(relaxed = true)
        streamLinkCacheDataStore = mockk(relaxed = true)
        playerSettingsDataStore = mockk(relaxed = true)
        debridSettingsDataStore = mockk(relaxed = true)
        directDebridResolver = mockk(relaxed = true)
        addonRepository = mockk(relaxed = true)
        bingeGroupCacheDataStore = mockk(relaxed = true)

        every { bingeGroupCacheDataStore.get(any()) } returns null
        coEvery { addonRepository.getInstalledAddons() } returns flowOf(emptyList<Addon>())

        coordinator = ContinueWatchingPreScrapeCoordinator(
            context = context,
            streamRepository = streamRepository,
            streamLinkCacheDataStore = streamLinkCacheDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            debridSettingsDataStore = debridSettingsDataStore,
            directDebridResolver = directDebridResolver,
            addonRepository = addonRepository,
            bingeGroupCacheDataStore = bingeGroupCacheDataStore,
            ambientCoordinatorProvider = null,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `when pre-scraping is disabled in PlayerSettings, onContinueWatchingItemsUpdated does not scrape`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(
            defaultPlayerSettings(preScrapeEnabled = false)
        )
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null

        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            streamLinkCacheDataStore.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when isPlaybackActive is true, pre-scraping is suppressed`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(defaultPlayerSettings())
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null

        coordinator.setPlaybackActive(true)
        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when isAmbientActive is true, pre-scraping is suppressed`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(defaultPlayerSettings())
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null

        coordinator.setAmbientActive(true)
        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when item has valid cached link in StreamLinkCacheDataStore, scraping is skipped`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(defaultPlayerSettings())
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns mockk(relaxed = true)

        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            streamLinkCacheDataStore.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `when item needs scraping, it queries streamRepository getStreamsFromAllAddons and saves resolved stream to StreamLinkCacheDataStore`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(defaultPlayerSettings())
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null

        coEvery {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        } returns flowOf(
            NetworkResult.Success(
                listOf(AddonStreams(addonName = "Torrentio", addonLogo = null, streams = listOf(testStream)))
            )
        )

        val savedUrl = slot<String>()
        val savedContentKey = slot<String>()
        coEvery {
            streamLinkCacheDataStore.save(
                contentKey = capture(savedContentKey),
                url = capture(savedUrl),
                streamName = any(),
                headers = any(),
                filename = any(),
                videoHash = any(),
                videoSize = any(),
                bingeGroup = any(),
                contentLanguage = any(),
                year = any()
            )
        } returns Unit

        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            streamRepository.getStreamsFromAllAddons(
                type = "series",
                videoId = "tt1234567:1:1",
                season = 1,
                episode = 1,
                forceRefresh = false
            )
        }

        coVerify(exactly = 1) {
            streamLinkCacheDataStore.save(
                contentKey = any(),
                url = any(),
                streamName = any(),
                headers = any(),
                filename = any(),
                videoHash = any(),
                videoSize = any(),
                bingeGroup = any(),
                contentLanguage = any(),
                year = any()
            )
        }

        assertEquals("series|tt1234567:1:1", savedContentKey.captured)
        assertEquals("https://example.com/stream.m3u8", savedUrl.captured)
    }

    @Test
    fun `clear cancels warmup and resets state`() = runTest(testDispatcher) {
        every { playerSettingsDataStore.playerSettings } returns flowOf(defaultPlayerSettings())
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null

        coordinator.setPlaybackActive(true)
        coordinator.clear()

        // After clear, playback active should be reset to false so pre-scraping can run
        coordinator.onContinueWatchingItemsUpdated(listOf(testItem))
        advanceTimeBy(3000L)
        advanceUntilIdle()

        coVerify(atLeast = 1) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any())
        }
    }
}
