package com.nuvio.tv.core.control

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.NuvioControlServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of the [NuvioControlServer].
 *
 * Observes [RemoteControlSettingsDataStore] and starts/stops the embedded control server
 * based on the user's remote-control preference. Also reacts to port changes by restarting
 * the server when necessary.
 */
@Singleton
class NuvioControlManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appCommandBus: AppCommandBus,
    private val playerPlaybackBridge: PlayerPlaybackBridge,
    private val remoteControlSettingsDataStore: RemoteControlSettingsDataStore,
    private val thematicChannelGenerator: ThematicChannelGenerator
) {

    companion object {
        private const val TAG = "NuvioControlManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    var server: NuvioControlServer? = null
        private set

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private var settingsJob: Job? = null

    @Volatile
    private var currentPort: Int? = null

    fun start() {
        synchronized(lock) {
            settingsJob?.cancel()
            settingsJob = scope.launch {
                remoteControlSettingsDataStore.settings
                    .distinctUntilChanged()
                    .collect { settings ->
                        try {
                            if (settings.serverEnabled) {
                                if (server != null && currentPort != settings.serverPort) {
                                    Log.d(TAG, "Port changed from $currentPort to ${settings.serverPort}, restarting server")
                                    stopServerInternal()
                                }
                                if (server == null) {
                                    startServerInternal(settings.serverPort)
                                }
                            } else {
                                stopServerInternal()
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to manage control server", e)
                            stopServerInternal()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error managing control server", e)
                            stopServerInternal()
                        }
                    }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            settingsJob?.cancel()
            settingsJob = null
            stopServerInternal()
        }
    }

    fun restart() {
        scope.launch {
            try {
                val settings = remoteControlSettingsDataStore.settings.first()
                synchronized(lock) {
                    stopServerInternal()
                    if (settings.serverEnabled) {
                        startServerInternal(settings.serverPort)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to restart control server", e)
                synchronized(lock) { stopServerInternal() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error restarting control server", e)
                synchronized(lock) { stopServerInternal() }
            }
        }
    }

    private fun startServerInternal(port: Int) {
        if (server != null) return

        val newServer = NuvioControlServer(
            context = context,
            appCommandBus = appCommandBus,
            playerPlaybackBridge = playerPlaybackBridge,
            remoteControlSettingsDataStore = remoteControlSettingsDataStore,
            thematicChannelGenerator = thematicChannelGenerator,
            port = port
        )

        try {
            newServer.start()
            server = newServer
            currentPort = port
            _isRunning.value = true

            val ip = DeviceIpAddress.get(context)
            _serverUrl.value = if (ip != null) "http://$ip:$port" else "http://localhost:$port"

            Log.i(TAG, "Control server started at ${_serverUrl.value}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start control server on port $port", e)
            runCatching { newServer.stop() }
            server = null
            currentPort = null
            _isRunning.value = false
            _serverUrl.value = null
            throw e
        }
    }

    private fun stopServerInternal() {
        val existing = server ?: return
        server = null
        currentPort = null
        try {
            existing.stop()
            Log.i(TAG, "Control server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping control server", e)
        } finally {
            _isRunning.value = false
            _serverUrl.value = null
        }
    }
}
