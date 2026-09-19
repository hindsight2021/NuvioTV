package com.nuvio.tv.ambient.coordinator

import android.util.Log
import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientUiState
import com.nuvio.tv.ambient.engine.AmbientFeedResolver
import com.nuvio.tv.ambient.history.AmbientHistoryRepository
import com.nuvio.tv.ambient.history.AmbientPreferencesDataStore
import com.nuvio.tv.ambient.playback.AmbientPlaybackController
import com.nuvio.tv.ambient.playback.AmbientPlayerPool
import com.nuvio.tv.ambient.playback.PlayerSlot
import com.nuvio.tv.ambient.settings.AmbientSettings
import com.nuvio.tv.ambient.settings.AmbientSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Central coordinator for the Nuvio TV Ambient Screensaver.
 *
 * Owns the lifecycle of ambient playback, scene rotation timing, channel switching,
 * user feedback, and UI state exposure. All public entry points are safe to call
 * from the main thread; internal work is dispatched on [Dispatchers.Default] and
 * guarded by a [Mutex] to prevent overlapping transitions.
 */
@Singleton
class AmbientCoordinator @Inject constructor(
    private val playbackController: AmbientPlaybackController,
    private val feedResolver: AmbientFeedResolver,
    private val settingsDataStore: AmbientSettingsDataStore,
    private val historyRepository: AmbientHistoryRepository,
    private val preferencesDataStore: AmbientPreferencesDataStore,
    private val playerPool: AmbientPlayerPool
) {

    companion object {
        private const val TAG = "AmbientCoordinator"
        private const val DEFAULT_INTERVAL_SECONDS = 180L
        private const val PRELOAD_LEAD_SECONDS = 20L
        private const val TIMER_TICK_MS = 1_000L

        private const val SLOT_INDEX_A = 0
        private const val SLOT_INDEX_B = 1
        private const val MAX_START_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _uiState = MutableStateFlow(AmbientUiState())
    val uiState: StateFlow<AmbientUiState> = _uiState.asStateFlow()

    private var rotationJob: Job? = null
    private var currentCandidate: AmbientCandidate? = null
    private var nextCandidate: AmbientCandidate? = null
    private var previousCandidate: AmbientCandidate? = null
    private var currentSceneStartedAtMs: Long = 0L

    init {
        scope.launch {
            playbackController.playbackState.collect { playback ->
                _uiState.update { state ->
                    state.copy(
                        playerAAlpha = playback.slotAAlpha,
                        playerBAlpha = playback.slotBAlpha,
                        activePlayerSlot = when (playback.activeSlot) {
                            PlayerSlot.SLOT_A -> SLOT_INDEX_A
                            PlayerSlot.SLOT_B -> SLOT_INDEX_B
                        },
                        currentCandidate = playback.currentCandidate ?: state.currentCandidate,
                        nextCandidate = playback.nextCandidate ?: state.nextCandidate,
                        errorMessage = playback.error
                    )
                }
                playback.currentCandidate?.let { currentCandidate = it }
                playback.nextCandidate?.let { nextCandidate = it }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts ambient playback. If [forcedChannel] is provided it takes precedence over settings.
     */
    fun startAmbient(forcedChannel: AmbientChannel? = null) {
        scope.launch {
            mutex.withLock {
                try {
                    runCatching { playerPool.reclaim() }
                        .onFailure { Log.w(TAG, "playerPool.reclaim failed", it) }

                    val settings = loadSettings()
                    val channel = forcedChannel ?: if (settings.resumeLastChannel) {
                        runCatching { AmbientChannel.valueOf(settings.lastChannelName.uppercase()) }
                            .getOrNull() ?: settings.defaultChannel
                    } else {
                        settings.defaultChannel
                    }

                    // Mount the ambient screen immediately so player surfaces attach before playback.
                    _uiState.update {
                        it.copy(
                            isAmbientActive = true,
                            currentChannel = channel,
                            isOverlayVisible = true,
                            errorMessage = null
                        )
                    }

                    var playedCandidate: AmbientCandidate? = null
                    var lastCandidate: AmbientCandidate? = null

                    for (attempt in 0 until MAX_START_ATTEMPTS) {
                        val candidate = feedResolver.resolveNextCandidate(channel, lastCandidate)
                        if (candidate == null) {
                            Log.w(TAG, "No candidate available for channel=$channel (attempt=${attempt + 1})")
                            break
                        }

                        lastCandidate = candidate

                        val started = runCatching {
                            playbackController.playCandidate(candidate, immediate = true)
                        }.getOrElse { t ->
                            Log.w(TAG, "playCandidate threw for candidate=${candidate.id}", t)
                            false
                        }

                        if (started) {
                            playedCandidate = candidate
                            break
                        }

                        Log.w(TAG, "playCandidate returned false for candidate=${candidate.id}, trying next")
                        runCatching {
                            historyRepository.recordPlayback(
                                candidateId = candidate.id,
                                playedAtEpochMs = System.currentTimeMillis(),
                                durationSeconds = 0,
                                wasInterrupted = false,
                                playbackFailed = true
                            )
                        }.onFailure { Log.w(TAG, "recordPlayback(failed start) failed", it) }
                    }

                    val candidate = playedCandidate
                    if (candidate == null) {
                        Log.w(TAG, "No playable candidate after $MAX_START_ATTEMPTS attempts for channel=$channel")
                        _uiState.update {
                            it.copy(
                                isAmbientActive = false,
                                errorMessage = "Failed to load ambient content"
                            )
                        }
                        return@withLock
                    }

                    currentCandidate = candidate
                    nextCandidate = null
                    currentSceneStartedAtMs = System.currentTimeMillis()

                    _uiState.update {
                        it.copy(
                            currentCandidate = candidate,
                            nextCandidate = null,
                            errorMessage = null
                        )
                    }

                    startRotationTimer(channel, settings)
                } catch (t: Throwable) {
                    Log.e(TAG, "startAmbient failed", t)
                    _uiState.update { it.copy(errorMessage = t.message ?: "Failed to start ambient") }
                }
            }
        }
    }

    /** Stops ambient playback and releases hardware decoders. */
    fun stopAmbient() {
        scope.launch {
            mutex.withLock {
                try {
                    stopRotationTimer()
                    recordCurrentScenePlayed(completed = false)

                    runCatching { playbackController.stop() }
                        .onFailure { Log.w(TAG, "playbackController.stop failed", it) }

                    runCatching { playerPool.yield() }
                        .onFailure { Log.w(TAG, "playerPool.yield failed", it) }

                    currentCandidate = null
                    nextCandidate = null

                    _uiState.update {
                        it.copy(
                            isAmbientActive = false,
                            isChannelPickerOpen = false,
                            isQuickActionsOpen = false,
                            currentCandidate = null,
                            nextCandidate = null
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "stopAmbient failed", t)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Channel & Navigation
    // ---------------------------------------------------------------------------------------------

    /** Switches to [channel], persisting the preference and crossfading to a new candidate. */
    fun setChannel(channel: AmbientChannel) {
        scope.launch {
            mutex.withLock {
                try {
                    runCatching { settingsDataStore.setLastChannelName(channel.name.lowercase()) }
                        .onFailure { Log.w(TAG, "Failed to persist last channel preference", it) }

                    val candidate = feedResolver.resolveNextCandidate(channel)
                    if (candidate == null) {
                        Log.w(TAG, "No candidate for channel=$channel")
                        _uiState.update {
                            it.copy(
                                currentChannel = channel,
                                isChannelPickerOpen = false,
                                errorMessage = "No ambient content available"
                            )
                        }
                        return@withLock
                    }

                    recordCurrentScenePlayed(completed = false)
                    previousCandidate = currentCandidate
                    currentCandidate = candidate
                    nextCandidate = null
                    currentSceneStartedAtMs = System.currentTimeMillis()

                    playbackController.playCandidate(candidate, immediate = false)

                    _uiState.update {
                        it.copy(
                            currentChannel = channel,
                            isChannelPickerOpen = false,
                            currentCandidate = candidate,
                            nextCandidate = null,
                            errorMessage = null
                        )
                    }

                    stopRotationTimer()
                    startRotationTimer(channel, loadSettings())
                } catch (t: Throwable) {
                    Log.e(TAG, "setChannel failed", t)
                    _uiState.update { it.copy(errorMessage = t.message ?: "Failed to switch channel") }
                }
            }
        }
    }

    /** Skips to the next scene, using the preloaded candidate when available. */
    fun skipNext() {
        scope.launch {
            mutex.withLock {
                try {
                    val channel = _uiState.value.currentChannel
                    val target = nextCandidate ?: feedResolver.resolveNextCandidate(channel, currentCandidate)
                    if (target == null) {
                        Log.w(TAG, "skipNext: no candidate available")
                        return@withLock
                    }

                    recordCurrentScenePlayed(completed = true)
                    previousCandidate = currentCandidate
                    currentCandidate = target
                    nextCandidate = null
                    currentSceneStartedAtMs = System.currentTimeMillis()

                    playbackController.transitionToNext { target }

                    _uiState.update {
                        it.copy(
                            currentCandidate = target,
                            nextCandidate = null,
                            errorMessage = null
                        )
                    }

                    stopRotationTimer()
                    startRotationTimer(channel, loadSettings())
                } catch (t: Throwable) {
                    Log.e(TAG, "skipNext failed", t)
                    _uiState.update { it.copy(errorMessage = t.message ?: "Failed to skip scene") }
                }
            }
        }
    }

    /** Replays the previous candidate if available, otherwise falls back to next. */
    fun previous() {
        scope.launch {
            mutex.withLock {
                try {
                    val prev = previousCandidate
                    if (prev == null) {
                        skipNextInternal()
                        return@withLock
                    }

                    recordCurrentScenePlayed(completed = false)
                    val swap = currentCandidate
                    currentCandidate = prev
                    previousCandidate = swap
                    nextCandidate = null
                    currentSceneStartedAtMs = System.currentTimeMillis()

                    playbackController.playCandidate(prev, immediate = false)

                    _uiState.update {
                        it.copy(
                            currentCandidate = prev,
                            nextCandidate = null,
                            errorMessage = null
                        )
                    }

                    val channel = _uiState.value.currentChannel
                    stopRotationTimer()
                    startRotationTimer(channel, loadSettings())
                } catch (t: Throwable) {
                    Log.e(TAG, "previous failed", t)
                    _uiState.update { it.copy(errorMessage = t.message ?: "Failed to replay previous") }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Feedback
    // ---------------------------------------------------------------------------------------------

    /** Records a positive signal for the current candidate. */
    fun likeCurrent() {
        val candidate = currentCandidate ?: return
        scope.launch {
            runCatching {
                historyRepository.recordFeedback(
                    candidateId = candidate.id,
                    isLiked = true,
                    isDisliked = false
                )
                preferencesDataStore.adjustCategoryWeight(candidate.category.lowercase(), 1.0)
            }.onFailure { Log.w(TAG, "recordFeedback(like) failed", it) }
        }
    }

    /** Records a negative signal and immediately advances past the disliked scene. */
    fun dislikeCurrent() {
        val candidate = currentCandidate ?: return
        scope.launch {
            runCatching {
                historyRepository.recordFeedback(
                    candidateId = candidate.id,
                    isLiked = false,
                    isDisliked = true
                )
                preferencesDataStore.adjustCategoryWeight(candidate.category.lowercase(), -1.0)
            }.onFailure { Log.w(TAG, "recordFeedback(dislike) failed", it) }

            skipNext()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // UI Toggles & Controls
    // ---------------------------------------------------------------------------------------------

    fun toggleChannelPicker() {
        _uiState.update { it.copy(isChannelPickerOpen = !it.isChannelPickerOpen) }
    }

    fun closeChannelPicker() {
        _uiState.update { it.copy(isChannelPickerOpen = false) }
    }

    fun toggleQuickActions() {
        _uiState.update { it.copy(isQuickActionsOpen = !it.isQuickActionsOpen) }
    }

    fun closeQuickActions() {
        _uiState.update { it.copy(isQuickActionsOpen = false) }
    }

    fun toggleLocationOverlay() {
        _uiState.update { it.copy(isOverlayVisible = !it.isOverlayVisible) }
    }

    fun setOverlayVisible(visible: Boolean) {
        _uiState.update { it.copy(isOverlayVisible = visible) }
    }

    fun updateOverlayPosition(positionIndex: Int) {
        _uiState.update { it.copy(overlayPositionIndex = positionIndex) }
    }

    fun updateDimPercent(dimPercent: Int) {
        _uiState.update { it.copy(dimPercent = dimPercent) }
    }

    // ---------------------------------------------------------------------------------------------
    // Scene Rotation Timer
    // ---------------------------------------------------------------------------------------------

    private fun startRotationTimer(channel: AmbientChannel, settings: AmbientSettings) {
        stopRotationTimer()

        val intervalSeconds = settings.overlayIntervalSeconds
            .takeIf { it > 0 }
            ?.toLong()
            ?: DEFAULT_INTERVAL_SECONDS

        rotationJob = scope.launch {
            try {
                var elapsedMs = 0L
                val intervalMs = intervalSeconds * 1_000L
                val preloadAtMs = (intervalMs - PRELOAD_LEAD_SECONDS * 1_000L).coerceAtLeast(0L)
                var preloaded = false

                while (isActive) {
                    delay(TIMER_TICK_MS)
                    elapsedMs += TIMER_TICK_MS

                    if (!preloaded && elapsedMs >= preloadAtMs) {
                        preloaded = true
                        preloadNextCandidate(channel)
                    }

                    if (elapsedMs >= intervalMs) {
                        executeTransition(channel)
                        elapsedMs = 0L
                        preloaded = false
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Rotation timer crashed", t)
                }
            }
        }
    }

    private fun stopRotationTimer() {
        rotationJob?.cancel()
        rotationJob = null
    }

    private suspend fun preloadNextCandidate(channel: AmbientChannel) {
        try {
            val candidate = feedResolver.resolveNextCandidate(channel, currentCandidate) ?: return
            nextCandidate = candidate
            playbackController.prepareNext(candidate)
            _uiState.update { it.copy(nextCandidate = candidate) }
        } catch (t: Throwable) {
            Log.w(TAG, "preloadNextCandidate failed", t)
        }
    }

    private suspend fun executeTransition(channel: AmbientChannel) {
        try {
            val target = nextCandidate ?: feedResolver.resolveNextCandidate(channel, currentCandidate)
            if (target == null) {
                Log.w(TAG, "executeTransition: no candidate available")
                return
            }

            recordCurrentScenePlayed(completed = true)
            previousCandidate = currentCandidate
            currentCandidate = target
            nextCandidate = null
            currentSceneStartedAtMs = System.currentTimeMillis()

            playbackController.transitionToNext { target }

            _uiState.update {
                it.copy(
                    currentCandidate = target,
                    nextCandidate = null,
                    errorMessage = null
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "executeTransition failed", t)
            _uiState.update { it.copy(errorMessage = t.message ?: "Transition failed") }
        }
    }

    private suspend fun skipNextInternal() {
        val channel = _uiState.value.currentChannel
        val target = nextCandidate ?: feedResolver.resolveNextCandidate(channel, currentCandidate) ?: return

        recordCurrentScenePlayed(completed = true)
        previousCandidate = currentCandidate
        currentCandidate = target
        nextCandidate = null
        currentSceneStartedAtMs = System.currentTimeMillis()

        playbackController.transitionToNext { target }

        _uiState.update {
            it.copy(currentCandidate = target, nextCandidate = null, errorMessage = null)
        }

        stopRotationTimer()
        startRotationTimer(channel, loadSettings())
    }

    private suspend fun recordCurrentScenePlayed(completed: Boolean) {
        val candidate = currentCandidate ?: return
        val startedAt = currentSceneStartedAtMs
        if (startedAt <= 0L) return

        val durationSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        try {
            historyRepository.recordPlayed(
                candidate = candidate,
                durationSeconds = durationSec,
                completed = completed
            )
        } catch (t: Throwable) {
            Log.w(TAG, "recordPlayed failed", t)
        }
    }

    private suspend fun loadSettings(): AmbientSettings {
        return try {
            settingsDataStore.settings.first()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load settings, using defaults", t)
            AmbientSettings()
        }
    }
}
