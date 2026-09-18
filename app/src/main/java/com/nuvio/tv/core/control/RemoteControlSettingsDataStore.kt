package com.nuvio.tv.core.control

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class RemoteControlSettings(
    val serverEnabled: Boolean = true,
    val serverPort: Int = 8910,
    val apiToken: String = "",
    val pairingPin: String = ""
)

private val Context.remoteControlDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_control_settings"
)

@Singleton
class RemoteControlSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.remoteControlDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val SERVER_ENABLED = booleanPreferencesKey("server_enabled")
        val SERVER_PORT = intPreferencesKey("server_port")
        val API_TOKEN = stringPreferencesKey("api_token")
        val PAIRING_PIN = stringPreferencesKey("pairing_pin")
    }

    companion object {
        const val DEFAULT_SERVER_ENABLED = true
        const val DEFAULT_SERVER_PORT = 8910
        private const val API_TOKEN_PREFIX = "nuvio_"
    }

    init {
        scope.launch {
            ensureCredentials()
        }
    }

    private suspend fun ensureCredentials() {
        dataStore.edit { prefs ->
            if (prefs[Keys.API_TOKEN].isNullOrBlank()) {
                prefs[Keys.API_TOKEN] = generateApiToken()
            }
            if (prefs[Keys.PAIRING_PIN].isNullOrBlank()) {
                prefs[Keys.PAIRING_PIN] = generatePairingPin()
            }
        }
    }

    val serverEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_ENABLED] ?: DEFAULT_SERVER_ENABLED
    }

    val serverPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SERVER_PORT] ?: DEFAULT_SERVER_PORT
    }

    val apiToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.API_TOKEN] ?: ""
    }

    val pairingPin: Flow<String> = dataStore.data.map { prefs ->
        prefs[Keys.PAIRING_PIN] ?: ""
    }

    val settings: Flow<RemoteControlSettings> = dataStore.data.map { prefs ->
        RemoteControlSettings(
            serverEnabled = prefs[Keys.SERVER_ENABLED] ?: DEFAULT_SERVER_ENABLED,
            serverPort = prefs[Keys.SERVER_PORT] ?: DEFAULT_SERVER_PORT,
            apiToken = prefs[Keys.API_TOKEN] ?: "",
            pairingPin = prefs[Keys.PAIRING_PIN] ?: ""
        )
    }

    suspend fun setServerEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SERVER_ENABLED] = enabled }
    }

    suspend fun setServerPort(port: Int) {
        dataStore.edit { prefs -> prefs[Keys.SERVER_PORT] = port }
    }

    suspend fun setApiToken(token: String) {
        dataStore.edit { prefs -> prefs[Keys.API_TOKEN] = token }
    }

    suspend fun setPairingPin(pin: String) {
        dataStore.edit { prefs -> prefs[Keys.PAIRING_PIN] = pin }
    }

    suspend fun regenerateApiToken(): String {
        val token = generateApiToken()
        dataStore.edit { prefs -> prefs[Keys.API_TOKEN] = token }
        return token
    }

    suspend fun regeneratePairingPin(): String {
        val pin = generatePairingPin()
        dataStore.edit { prefs -> prefs[Keys.PAIRING_PIN] = pin }
        return pin
    }

    suspend fun getSettings(): RemoteControlSettings {
        val prefs = dataStore.data.first()
        var token = prefs[Keys.API_TOKEN]
        var pin = prefs[Keys.PAIRING_PIN]
        if (token.isNullOrBlank() || pin.isNullOrBlank()) {
            ensureCredentials()
            val updated = dataStore.data.first()
            token = updated[Keys.API_TOKEN]
            pin = updated[Keys.PAIRING_PIN]
        }
        return RemoteControlSettings(
            serverEnabled = prefs[Keys.SERVER_ENABLED] ?: DEFAULT_SERVER_ENABLED,
            serverPort = prefs[Keys.SERVER_PORT] ?: DEFAULT_SERVER_PORT,
            apiToken = token ?: generateApiToken(),
            pairingPin = pin ?: generatePairingPin()
        )
    }

    private fun generateApiToken(): String =
        API_TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "")

    private fun generatePairingPin(): String =
        String.format(Locale.US, "%04d", Random.nextInt(10000))
}
