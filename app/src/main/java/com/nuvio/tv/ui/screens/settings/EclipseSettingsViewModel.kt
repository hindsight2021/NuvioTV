package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.music.EclipseHealthStatus
import com.nuvio.tv.core.music.EclipseMusicClient
import com.nuvio.tv.data.local.EclipseSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Eclipse Music settings screen.
 */
data class EclipseSettingsUiState(
    val enabled: Boolean = true,
    val bridgeBaseUrl: String = EclipseSettingsDataStore.DEFAULT_BRIDGE_URL,
    val mcpBearerToken: String = "",
    val showMusicInSidebar: Boolean = true,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val error: String? = null
)

/**
 * ViewModel backing [EclipseSettingsContent].
 * Persists changes via [EclipseSettingsDataStore] and tests bridge health via [EclipseMusicClient].
 */
@HiltViewModel
class EclipseSettingsViewModel @Inject constructor(
    private val eclipseSettingsDataStore: EclipseSettingsDataStore,
    private val eclipseMusicClient: EclipseMusicClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(EclipseSettingsUiState())
    val uiState: StateFlow<EclipseSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            eclipseSettingsDataStore.settings.collect { settings ->
                _uiState.update { current ->
                    current.copy(
                        enabled = settings.enabled,
                        bridgeBaseUrl = settings.bridgeBaseUrl,
                        mcpBearerToken = settings.mcpBearerToken,
                        showMusicInSidebar = settings.showMusicInSidebar
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { eclipseSettingsDataStore.setEnabled(enabled) }
                .onFailure { t -> _uiState.update { it.copy(error = t.message) } }
        }
    }

    fun setShowMusicInSidebar(show: Boolean) {
        viewModelScope.launch {
            runCatching { eclipseSettingsDataStore.setShowMusicInSidebar(show) }
                .onFailure { t -> _uiState.update { it.copy(error = t.message) } }
        }
    }

    fun setBridgeBaseUrl(url: String) {
        viewModelScope.launch {
            runCatching { eclipseSettingsDataStore.setBridgeBaseUrl(url.trim()) }
                .onFailure { t -> _uiState.update { it.copy(error = t.message, testResult = null) } }
        }
    }

    fun setMcpBearerToken(token: String) {
        viewModelScope.launch {
            runCatching { eclipseSettingsDataStore.setMcpBearerToken(token.trim()) }
                .onFailure { t -> _uiState.update { it.copy(error = t.message, testResult = null) } }
        }
    }

    fun testConnection() {
        if (_uiState.value.isTesting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, error = null) }
            eclipseMusicClient.health().fold(
                onSuccess = { status ->
                    _uiState.update {
                        it.copy(isTesting = false, testResult = formatHealthStatus(status), error = null)
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = null,
                            error = t.message ?: "Connection test failed"
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun formatHealthStatus(status: EclipseHealthStatus): String = buildString {
        append("Status: ").append(status.status)
        append(" • Eclipse Auth: ").append(if (status.eclipseAuthenticated) "OK" else "Missing")
        append(" • Connect WS: ").append(if (status.connectWs) "Connected" else "Offline")
        append(" • Devices: ").append(status.devices)
        append(" • Speaker: ").append(
            when {
                status.nuvioSpeakerConnected -> "Connected"
                status.nuvioSpeakerConfigured -> "Configured (Connecting)"
                else -> "Unconfigured"
            }
        )
    }
}
