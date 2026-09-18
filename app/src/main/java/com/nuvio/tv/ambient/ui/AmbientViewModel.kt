package com.nuvio.tv.ambient.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.AmbientUiState
import com.nuvio.tv.ambient.coordinator.AmbientCoordinator
import com.nuvio.tv.ambient.settings.AmbientSettings
import com.nuvio.tv.ambient.settings.AmbientSettingsDataStore
import com.nuvio.tv.core.ha.HomeAssistantWeather
import com.nuvio.tv.core.ha.HomeAssistantWeatherService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel backing the Nuvio TV Ambient Screensaver UI.
 *
 * Responsibilities:
 *  - Expose coordinator state, settings and weather as observable [StateFlow]s.
 *  - Drive OLED burn-in protection by periodically shifting rendered overlay positions.
 *  - Apply progressive dimming based on continuous ambient uptime.
 *  - Auto-show/hide the location overlay when the current candidate changes.
 *  - Delegate user actions to [AmbientCoordinator].
 */
@HiltViewModel
class AmbientViewModel @Inject constructor(
    val coordinator: AmbientCoordinator,
    val settingsDataStore: AmbientSettingsDataStore
) : ViewModel() {

    /** Primary UI state emitted by the coordinator. */
    val uiState: StateFlow<AmbientUiState> = coordinator.uiState

    /** Persisted ambient settings, eagerly collected for the lifetime of the VM. */
    val settings: StateFlow<AmbientSettings> = settingsDataStore.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AmbientSettings()
        )

    /** Latest weather snapshot from Home Assistant. */
    val weatherState: StateFlow<HomeAssistantWeather> = HomeAssistantWeatherService.weatherState

    private val _burnInOffsetDpX = MutableStateFlow(0f)
    /** Horizontal burn-in shift in dp, clamped to [-10f, 10f]. */
    val burnInOffsetDpX: StateFlow<Float> = _burnInOffsetDpX.asStateFlow()

    private val _burnInOffsetDpY = MutableStateFlow(0f)
    /** Vertical burn-in shift in dp, clamped to [-10f, 10f]. */
    val burnInOffsetDpY: StateFlow<Float> = _burnInOffsetDpY.asStateFlow()

    private var burnInJob: Job? = null
    private var dimmingJob: Job? = null
    private var overlayTemporaryHideJob: Job? = null
    private var lastCandidate: AmbientCandidate? = null

    init {
        observeAmbientActive()
        observeCandidateChanges()
    }

    private fun observeAmbientActive() {
        viewModelScope.launch {
            uiState.collect { state ->
                if (state.isAmbientActive) {
                    startBurnInLoop()
                    startDimmingLoop()
                } else {
                    stopBurnInLoop()
                    stopDimmingLoop()
                    _burnInOffsetDpX.value = 0f
                    _burnInOffsetDpY.value = 0f
                    coordinator.updateDimPercent(0)
                }
            }
        }
    }

    private fun observeCandidateChanges() {
        viewModelScope.launch {
            uiState.collect { state ->
                val candidate = state.currentCandidate
                if (candidate != lastCandidate) {
                    lastCandidate = candidate
                    if (candidate != null && state.isAmbientActive) {
                        if (!state.isChannelPickerOpen && !state.isQuickActionsOpen) {
                            showOverlayTemporarily()
                        }
                    }
                }
            }
        }
    }

    private fun startBurnInLoop() {
        if (burnInJob?.isActive == true) return
        burnInJob = viewModelScope.launch {
            while (isActive) {
                delay(BURN_IN_INTERVAL_MS)
                _burnInOffsetDpX.value = randomOffset()
                _burnInOffsetDpY.value = randomOffset()
                val nextPos = ((uiState.value.overlayPositionIndex + 1) % 4)
                coordinator.updateOverlayPosition(nextPos)
            }
        }
    }

    private fun stopBurnInLoop() {
        burnInJob?.cancel()
        burnInJob = null
    }

    private fun startDimmingLoop() {
        if (dimmingJob?.isActive == true) return
        dimmingJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val elapsedMinutes = (System.currentTimeMillis() - startTime) / MILLIS_PER_MINUTE
                val dimPercent = if (settings.value.dimAfterExtendedIdle) {
                    when {
                        elapsedMinutes >= 120 -> 30
                        elapsedMinutes >= 60 -> 15
                        else -> 0
                    }
                } else {
                    0
                }
                coordinator.updateDimPercent(dimPercent)
                delay(DIM_TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopDimmingLoop() {
        dimmingJob?.cancel()
        dimmingJob = null
    }

    private fun randomOffset(): Float = (Math.random() * 16.0 - 8.0).toFloat()

    // ---------------------------------------------------------------------
    // Delegated actions
    // ---------------------------------------------------------------------

    fun startAmbient(channel: AmbientChannel? = null) = coordinator.startAmbient(channel)

    fun stopAmbient() = coordinator.stopAmbient()

    fun skipNext() = coordinator.skipNext()

    fun previous() = coordinator.previous()

    fun likeCurrent() = coordinator.likeCurrent()

    fun dislikeCurrent() = coordinator.dislikeCurrent()

    fun selectChannel(channel: AmbientChannel) = coordinator.setChannel(channel)

    fun toggleChannelPicker() = coordinator.toggleChannelPicker()

    fun closeChannelPicker() = coordinator.closeChannelPicker()

    fun toggleQuickActions() = coordinator.toggleQuickActions()

    fun closeQuickActions() = coordinator.closeQuickActions()

    fun showOverlayTemporarily(durationMs: Long = DEFAULT_OVERLAY_DURATION_MS) {
        coordinator.setOverlayVisible(true)
        overlayTemporaryHideJob?.cancel()
        overlayTemporaryHideJob = viewModelScope.launch {
            delay(durationMs)
            val state = uiState.value
            if (!state.isChannelPickerOpen && !state.isQuickActionsOpen) {
                coordinator.setOverlayVisible(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        burnInJob?.cancel()
        dimmingJob?.cancel()
        overlayTemporaryHideJob?.cancel()
    }

    private companion object {
        const val BURN_IN_INTERVAL_MS = 180_000L
        const val DIM_TICK_INTERVAL_MS = 30_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val DEFAULT_OVERLAY_DURATION_MS = 8_000L
    }
}
