package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.control.NuvioControlManager
import com.nuvio.tv.core.control.RemoteControlSettingsDataStore
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RemoteControlSettingsUiState(
    val isEnabled: Boolean = true,
    val serverPort: Int = 8910,
    val pairingPin: String = "",
    val apiToken: String = "",
    val serverHost: String = "",
    val isServerRunning: Boolean = false,
    val serverStatusText: String = "",
    val webRemoteUrl: String = "",
    val pairingUrl: String = "",
    val pairingQrCode: Bitmap? = null,
    val isGeneratingQrCode: Boolean = false,
    val errorMessage: String? = null
)

sealed interface RemoteControlSettingsEvent {
    data class ToggleServerEnabled(val enabled: Boolean) : RemoteControlSettingsEvent
    data class SetPort(val port: Int) : RemoteControlSettingsEvent
    data object RegeneratePin : RemoteControlSettingsEvent
    data object RegenerateToken : RemoteControlSettingsEvent
    data object RestartServer : RemoteControlSettingsEvent
}

@HiltViewModel
class RemoteControlSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: RemoteControlSettingsDataStore,
    private val controlManager: NuvioControlManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteControlSettingsUiState())
    val uiState: StateFlow<RemoteControlSettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        observeServerState()
        refreshIpAddress()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                val ip = _uiState.value.serverHost.ifBlank {
                    DeviceIpAddress.get(context) ?: "127.0.0.1"
                }
                val webUrl = "http://$ip:${settings.serverPort}"
                val pairUrl = "nuvio://pair?host=$ip&port=${settings.serverPort}&pin=${settings.pairingPin}&token=${settings.apiToken}"
                val status = if (_uiState.value.isServerRunning) "Running on $webUrl" else "Stopped"

                _uiState.update { current ->
                    current.copy(
                        isEnabled = settings.serverEnabled,
                        serverPort = settings.serverPort,
                        pairingPin = settings.pairingPin,
                        apiToken = settings.apiToken,
                        serverHost = ip,
                        serverStatusText = status,
                        webRemoteUrl = webUrl,
                        pairingUrl = pairUrl
                    )
                }
                generatePairingQrCode()
            }
        }
    }

    private fun observeServerState() {
        viewModelScope.launch {
            controlManager.isRunning.collect { running ->
                val ip = _uiState.value.serverHost
                val port = _uiState.value.serverPort
                val status = if (running) "Running on http://$ip:$port" else "Stopped"
                _uiState.update { it.copy(isServerRunning = running, serverStatusText = status) }
            }
        }
    }

    private fun refreshIpAddress() {
        viewModelScope.launch {
            val ip = withContext(Dispatchers.IO) {
                DeviceIpAddress.get(context) ?: "127.0.0.1"
            }
            val port = _uiState.value.serverPort
            val pin = _uiState.value.pairingPin
            val token = _uiState.value.apiToken
            val webUrl = "http://$ip:$port"
            val pairUrl = "nuvio://pair?host=$ip&port=$port&pin=$pin&token=$token"
            val status = if (_uiState.value.isServerRunning) "Running on $webUrl" else "Stopped"

            _uiState.update {
                it.copy(
                    serverHost = ip,
                    webRemoteUrl = webUrl,
                    pairingUrl = pairUrl,
                    serverStatusText = status
                )
            }
            generatePairingQrCode()
        }
    }

    fun onEvent(event: RemoteControlSettingsEvent) {
        when (event) {
            is RemoteControlSettingsEvent.ToggleServerEnabled -> setEnabled(event.enabled)
            is RemoteControlSettingsEvent.SetPort -> setPort(event.port)
            RemoteControlSettingsEvent.RegeneratePin -> regeneratePin()
            RemoteControlSettingsEvent.RegenerateToken -> regenerateToken()
            RemoteControlSettingsEvent.RestartServer -> restartServer()
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setServerEnabled(enabled)
            if (enabled) {
                controlManager.start()
            } else {
                controlManager.stop()
            }
        }
    }

    fun setPort(port: Int) {
        viewModelScope.launch {
            settingsDataStore.setServerPort(port)
            if (_uiState.value.isEnabled) {
                controlManager.restart()
            }
        }
    }

    fun regeneratePin() {
        viewModelScope.launch {
            settingsDataStore.regeneratePairingPin()
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            settingsDataStore.regenerateApiToken()
        }
    }

    fun restartServer() {
        viewModelScope.launch {
            controlManager.restart()
        }
    }

    private fun generatePairingQrCode() {
        val state = _uiState.value
        if (state.serverHost.isBlank() || state.pairingPin.isBlank() || state.apiToken.isBlank()) {
            return
        }

        val payload = "nuvio://pair?host=${state.serverHost}&port=${state.serverPort}&pin=${state.pairingPin}&token=${state.apiToken}"

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingQrCode = true) }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { QrCodeGenerator.generate(payload, 420) }.getOrNull()
            }
            _uiState.update {
                it.copy(
                    pairingQrCode = bitmap,
                    isGeneratingQrCode = false
                )
            }
        }
    }
}
