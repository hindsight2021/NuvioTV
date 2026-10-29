package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import io.mockk.coEvery
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.flow
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeContinueWatchingRefreshTest {
    private lateinit var vm: HomeViewModel
    private val repo = mockk<MetaRepository>(relaxed = true)
    private lateinit var holder: WatchedSeriesStateHolder
    private val southPark = "tt0121955"
    private val scrubs = "tt40197357"
    private val now = 10_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val profiles = mockk<ProfileManager>(relaxed = true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        holder = spyk(WatchedSeriesStateHolder(mockk(relaxed = true), profiles))
        every { holder.filterStaleIds(any()) } answers { firstArg<Set<String>>() }
        every { holder.recordSeriesAirState(any(), any(), any(), any()) } just Runs
        every { holder.updateWithValidation(any(), any(), any(), any()) } just Runs
        every { holder.removeFullyWatched(any(), any()) } just Runs
        val cache = mockk<ContinueWatchingEnrichmentCache>(relaxed = true) {
            every { cacheCleared } returns MutableStateFlow(0)
        }
        val progress = mockk<WatchProgressRepository>(relaxed = true) {
            every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
        }
        vm = HomeViewModel(
            appContext = mockk(relaxed = true), addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true), catalogRepository = mockk(relaxed = true),
            watchProgressRepository = progress, libraryRepository = mockk(relaxed = true),
            metaRepository = repo, collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true), playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true), mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true), authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true), tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true), trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = holder, cwEnrichmentCache = cache,
            profileManager = profiles, tvRecommendationManager = mockk(relaxed = true)
        )
        vm.currentTmdbSettings = TmdbSettings(enabled = false)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `expired metadata and negative results are evicted together without erasing visible cards`() {
        val visible = vm._uiState.value
        vm.cwMetadataCheckedAt[southPark] = now - 300_000L
        vm.cwMetadataCheckedAt[scrubs] = now - 1L
        vm.cwMetaCache["series:$southPark"] = summary(southPark)
        vm.cwMetaCache["series:$scrubs"] = summary(scrubs)
        vm.cwMetaCache["series:tmdb:9"] = summary("tmdb:9")
        vm.cwMetaNegativeCacheTimestamps["tv:$southPark"] = 1L
        vm.cwBadgeEpisodeCache["series:$southPark"] = setOf(29 to 3)
        vm.cwNextUpResolutionCache["$southPark|29|3|false"] = null
        vm.cwNextUpNegativeCacheTimestamps["$southPark|29|3|false"] = 1L
        vm.cwNextUpResolutionCache["$scrubs|2|5|false"] = null
        vm.invalidateDueContinueWatchingMetadata(setOf(southPark, scrubs), now)
        assertFalse(vm.cwMetaCache.containsKey("series:$southPark"))
        assertTrue(vm.cwMetaCache.containsKey("series:$scrubs"))
        assertTrue(vm.cwMetaCache.containsKey("series:tmdb:9"))
        assertTrue(vm.cwMetaNegativeCacheTimestamps.isEmpty())
        assertTrue(vm.cwBadgeEpisodeCache.isEmpty())
        assertFalse(vm.cwNextUpResolutionCache.containsKey("$southPark|29|3|false"))
        assertTrue(vm.cwNextUpResolutionCache.containsKey("$scrubs|2|5|false"))
        assertTrue(vm.cwNextUpNegativeCacheTimestamps.isEmpty())
        assertEquals(visible, vm._uiState.value)
        assertTrue(southPark in vm.cwForcedNextUpRefreshIds)
        verify(exactly = 1) { repo.clearCacheForId(southPark) }
        verify(exactly = 0) { repo.clearCacheForId(scrubs) }
    }

    @Test
    fun `fallback rechecks fresh badge metadata and exact tmdb ids`() {
        every { holder.filterStaleIds(any()) } returns emptySet()
        vm.cwMetadataCheckedAt["tmdb:9"] = now - 900_000L
        vm.cwMetaCache["series:tmdb:9"] = summary("tmdb:9")
        vm.cwMetaCache["series:tmdb:99"] = summary("tmdb:99")
        vm.invalidateDueContinueWatchingMetadata(setOf("tmdb:9"), now)
        assertFalse(vm.cwMetaCache.containsKey("series:tmdb:9"))
        assertTrue(vm.cwMetaCache.containsKey("series:tmdb:99"))
        assertTrue("tmdb:9" in vm.cwForcedNextUpRefreshIds)
    }

    @Test
    fun `ambiguous sibling cluster is never invalidated through another show`() {
        vm.cwLastShowIdSiblings = mapOf(southPark to setOf("__ambiguous__", scrubs))
        vm.cwMetadataCheckedAt[southPark] = now - 300_000L
        vm.cwMetadataCheckedAt[scrubs] = now
        vm.cwMetaCache["series:$scrubs"] = summary(scrubs)
        vm.invalidateDueContinueWatchingMetadata(setOf(southPark, scrubs), now)
        assertTrue(vm.cwMetaCache.containsKey("series:$scrubs"))
        verify(exactly = 0) { repo.clearCacheForId(scrubs) }
    }

    @Test
    fun `first observation and retry backoff preserve metadata`() {
        vm.cwMetaCache["series:$southPark"] = summary(southPark)
        vm.invalidateDueContinueWatchingMetadata(setOf(southPark), now)
        vm.invalidateDueContinueWatchingMetadata(setOf(southPark), now + 299_999L)
        assertTrue(vm.cwMetaCache.containsKey("series:$southPark"))
        verify(exactly = 0) { repo.clearCacheForId(any()) }
    }

    private fun summary(id: String) = CwMetaSummary(
        id = id, name = id, poster = null, backdropUrl = null, logo = null,
        description = null, genres = emptyList(), releaseInfo = null, imdbRating = null,
        language = null, country = null, videos = emptyList()
    )

    @Test
    fun `real pipeline publishes South Park and Scrubs while unrelated provider is stalled`() = runBlocking {
        val timestamp = System.currentTimeMillis()
        fun seed(id: String, name: String, season: Int, episode: Int) = WatchProgress(
            contentId = id, contentType = "series", name = name, poster = null,
            backdrop = null, logo = null, videoId = "$id:$season:$episode",
            season = season, episode = episode, episodeTitle = null,
            position = 1L, duration = 1L, lastWatched = timestamp, progressPercent = 100f
        )
        val seeds = listOf(seed(southPark, "South Park", 29, 3), seed(scrubs, "Scrubs", 2, 5),
            seed("tt999", "Slow provider", 1, 1))
        every { vm.watchProgressRepository.allProgress } returns MutableStateFlow(emptyList())
        every { vm.watchProgressRepository.observeNextUpSeeds() } returns MutableStateFlow(seeds)
        every { vm.watchProgressRepository.observeRemoteProgressLoaded() } returns MutableStateFlow(true)
        every { vm.watchProgressRepository.watchedItems } returns MutableStateFlow(emptyList())
        every { vm.watchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(any(), any()) } returns null
        every { vm.watchProgressRepository.shouldUseAsNextUpSeed(any(), any()) } returns true
        val releaseScrubs = CompletableDeferred<Unit>()
        coEvery { vm.watchProgressRepository.prepareNextUpSeed(any()) } coAnswers {
            val progress = firstArg<WatchProgress>()
            if (progress.contentId == scrubs) releaseScrubs.await()
            progress
        }
        coEvery { vm.watchProgressRepository.getWatchedShowEpisodes() } returns emptyMap()
        coEvery { vm.watchProgressRepository.getShowIdSiblings() } returns emptyMap()
        every { holder.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        every { vm.traktSettingsDataStore.continueWatchingDaysCap } returns MutableStateFlow(0)
        every { vm.traktSettingsDataStore.dismissedNextUpKeys } returns MutableStateFlow(emptySet())
        every { vm.layoutPreferenceDataStore.showUnairedNextUp } returns MutableStateFlow(false)
        every { vm.layoutPreferenceDataStore.nextUpFromFurthestEpisode } returns MutableStateFlow(true)
        every { vm.layoutPreferenceDataStore.continueWatchingSortMode } returns MutableStateFlow(ContinueWatchingSortMode.DEFAULT)
        coEvery { vm.cwEnrichmentCache.getNextUpSnapshot() } returns emptyList()
        coEvery { vm.cwEnrichmentCache.getInProgressSnapshot() } returns emptyList()
        every { repo.getMetaFromAllAddons(any(), "tt999", any()) } returns flow { awaitCancellation() }
        every { repo.getMetaFromPrimaryAddon(any(), "tt999") } returns flow { awaitCancellation() }
        seeds.take(2).forEach { progress ->
            vm.cwMetaCache["series:${progress.contentId}"] = summary(progress.contentId).copy(
                name = progress.name, releaseInfo = "2026-", videos = listOf(progress.episode!!, progress.episode + 1).map { ep ->
                    CwVideoSummary("${progress.contentId}:${progress.season}:$ep", "Episode $ep",
                        "2026-10-01T00:00:00Z", null, progress.season, ep, null, true)
                }
            )
        }
        vm.loadContinueWatchingPipeline()
        withTimeout(6_000L) {
            while (vm.uiState.value.continueWatchingItems.none { it is ContinueWatchingItem.NextUp && it.info.contentId == southPark }) delay(20L)
        }
        releaseScrubs.complete(Unit)
        withTimeout(6_000L) {
            while (vm.uiState.value.continueWatchingItems.filterIsInstance<ContinueWatchingItem.NextUp>().size < 2) delay(20L)
        }
        val cards = vm.uiState.value.continueWatchingItems.filterIsInstance<ContinueWatchingItem.NextUp>()
        assertEquals(setOf(southPark to (29 to 4), scrubs to (2 to 6)),
            cards.map { it.info.contentId to (it.info.season to it.info.episode) }.toSet())
        assertTrue("the stalled provider must still be running", vm.cwPipelineJob?.isActive == true)
        verify(exactly = 0) { repo.clearCacheForId(southPark) }
        verify(exactly = 0) { repo.clearCacheForId(scrubs) }
        vm.cwPipelineJob?.cancel()
        vm.cwPipelineJob?.join()
        Unit
    }
}
