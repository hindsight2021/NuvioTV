package com.nuvio.tv.core.scraper

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.contentId
import com.nuvio.tv.ui.screens.home.contentType
import com.nuvio.tv.ui.screens.home.episode
import com.nuvio.tv.ui.screens.home.season
import com.nuvio.tv.ui.screens.home.videoId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates background pre-scraping and debrid stream pre-resolution for the user's
 * Continue Watching shows.
 *
 * When Continue Watching settles on the Home Screen, this coordinator warms the in-memory
 * [StreamSearchSessionCache] and pre-resolves the best auto-play candidate directly into
 * [StreamLinkCacheDataStore]. Clicking any warmed Continue Watching title launches playback
 * in sub-seconds with zero scraping latency.
 */
@Singleton
class ContinueWatchingPreScrapeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val addonRepository: AddonRepository,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val ambientCoordinatorProvider: javax.inject.Provider<com.nuvio.tv.ambient.coordinator.AmbientCoordinator>? = null,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "CWPreScrape"
        private const val DEBOUNCE_MS = 2500L
        private const val ITEM_DELAY_MS = 1000L
        private const val SCRAPE_TIMEOUT_MS = 12_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var warmupJob: Job? = null

    private val isPlaybackActive = AtomicBoolean(false)
    private val isAmbientActive = AtomicBoolean(false)
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        ambientCoordinatorProvider?.let { provider ->
            scope.launch {
                runCatching {
                    provider.get().uiState
                        .map { it.isAmbientActive }
                        .distinctUntilChanged()
                        .collect { active ->
                            setAmbientActive(active)
                        }
                }.onFailure { e ->
                    Log.w(TAG, "Failed to observe ambientCoordinator uiState", e)
                }
            }
        }
    }

    /**
     * Called whenever the Continue Watching items are updated or reconciled.
     */
    fun onContinueWatchingItemsUpdated(items: List<ContinueWatchingItem>) {
        warmupJob?.cancel()
        if (items.isEmpty()) return

        warmupJob = scope.launch {
            delay(DEBOUNCE_MS)

            if (isPlaybackActive.get() || isAmbientActive.get()) {
                Log.d(TAG, "Suppressed: playback or screensaver is currently active")
                return@launch
            }

            val playerSettings = runCatching { playerSettingsDataStore.playerSettings.first() }.getOrNull()
                ?: return@launch

            if (!playerSettings.continueWatchingPreScrapeEnabled) {
                Log.d(TAG, "Pre-scraping is disabled in user settings")
                return@launch
            }

            val count = playerSettings.continueWatchingPreScrapeCount.coerceIn(1, 10)
            val targets = items.take(count)

            Log.d(TAG, "Starting background pre-scrape for top ${targets.size} Continue Watching items")

            for (item in targets) {
                if (isPlaybackActive.get() || isAmbientActive.get()) {
                    Log.d(TAG, "Aborting pre-scrape queue: playback or ambient became active")
                    break
                }
                warmItem(item, playerSettings)
                delay(ITEM_DELAY_MS)
            }
        }
    }

    private suspend fun warmItem(item: ContinueWatchingItem, playerSettings: PlayerSettings) {
        val type = item.contentType()
        val videoId = item.videoId()
        val season = item.season()
        val episode = item.episode()
        val contentKey = "${type.lowercase()}|$videoId"

        // 1. Skip if already cached in StreamLinkCacheDataStore
        val cacheAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
        val existingLink = streamLinkCacheDataStore.getValid(contentKey, maxAgeMs = cacheAgeMs)
        if (existingLink != null) {
            Log.d(TAG, "Item $contentKey already has valid cached link; skipping")
            return
        }

        if (!inFlightKeys.add(contentKey)) {
            return
        }

        try {
            Log.d(TAG, "Pre-scraping streams for $contentKey (S${season}E${episode})")

            // 2. Fetch streams from all add-ons with timeout (populates StreamSearchSessionCache)
            var lastSuccessData: List<AddonStreams>? = null
            withTimeoutOrNull(SCRAPE_TIMEOUT_MS) {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    forceRefresh = false
                ).collect { result ->
                    if (result is NetworkResult.Success) {
                        lastSuccessData = result.data
                    }
                }
            }

            val addonStreams = lastSuccessData
            if (addonStreams.isNullOrEmpty()) {
                Log.d(TAG, "No streams found during pre-scrape for $contentKey")
                return
            }

            val installedAddons = runCatching { addonRepository.getInstalledAddons().firstOrNull() }.getOrNull().orEmpty()
            val installedAddonNames = installedAddons.map { it.name }.toSet()
            val installedAddonOrder = installedAddons.map { it.name }

            val orderedGroups = StreamAutoPlaySelector.orderAddonStreams(addonStreams, installedAddonOrder)
            val allStreams = orderedGroups.flatMap { it.streams }

            // 3. Find the best stream candidate for auto-play
            val preferredBingeGroup = item.contentId().let { bingeGroupCacheDataStore.get(it) }
            var candidate: Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
                streams = allStreams,
                mode = playerSettings.streamAutoPlayMode,
                regexPattern = playerSettings.streamAutoPlayRegex,
                source = playerSettings.streamAutoPlaySource,
                installedAddonNames = installedAddonNames,
                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
                bingeGroupOnly = false
            )

            // If manual mode or no match, fallback to the top playable cached stream
            if (candidate == null) {
                candidate = allStreams.firstOrNull { s ->
                    !s.isExternal() && (s.getStreamUrl() != null || s.isDirectDebrid() || s.isCachedLocalDebridTorrent()) &&
                            s.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED
                }
            }

            if (candidate == null) {
                Log.d(TAG, "No candidate stream eligible for pre-resolution on $contentKey")
                return
            }

            // 4. Resolve debrid stream to direct HTTPS playable link if applicable
            var directPlayableUrl: String? = candidate.getStreamUrl()
            var directStreamName = candidate.name ?: candidate.title ?: "Stream"
            var candidateFilename = candidate.behaviorHints?.filename
            var candidateVideoSize = candidate.behaviorHints?.videoSize

            if (candidate.isDirectDebrid() || candidate.isCachedLocalDebridTorrent()) {
                val debridResult = directDebridResolver.resolveToPlayableStream(candidate, season, episode)
                if (debridResult is DirectDebridPlayableResult.Success) {
                    directPlayableUrl = debridResult.stream.getStreamUrl()
                    directStreamName = debridResult.stream.name ?: directStreamName
                    candidateFilename = debridResult.stream.behaviorHints?.filename ?: candidateFilename
                    candidateVideoSize = debridResult.stream.behaviorHints?.videoSize ?: candidateVideoSize
                }
            }

            // 5. Persist to StreamLinkCacheDataStore
            if (!directPlayableUrl.isNullOrBlank()) {
                streamLinkCacheDataStore.save(
                    contentKey = contentKey,
                    url = directPlayableUrl,
                    streamName = directStreamName,
                    headers = null,
                    filename = candidateFilename,
                    videoHash = null,
                    videoSize = candidateVideoSize,
                    bingeGroup = candidate.behaviorHints?.bingeGroup ?: preferredBingeGroup,
                    contentLanguage = null,
                    year = null
                )
                Log.i(TAG, "Successfully pre-scraped and cached direct link for $contentKey ($directStreamName)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Pre-scrape error for $contentKey", t)
        } finally {
            inFlightKeys.remove(contentKey)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive.set(active)
        if (active) {
            warmupJob?.cancel()
        }
    }

    fun setAmbientActive(active: Boolean) {
        isAmbientActive.set(active)
        if (active) {
            warmupJob?.cancel()
        }
    }

    fun clear() {
        warmupJob?.cancel()
        warmupJob = null
        inFlightKeys.clear()
        isPlaybackActive.set(false)
        isAmbientActive.set(false)
    }
}

private fun Stream.isCachedLocalDebridTorrent(): Boolean =
    needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED

