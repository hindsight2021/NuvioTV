package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class EclipseSettings(
    val enabled: Boolean = true,
    val bridgeBaseUrl: String = EclipseSettingsDataStore.DEFAULT_BRIDGE_URL,
    val mcpBearerToken: String = "",
    val showMusicInSidebar: Boolean = true,
)

private val Context.eclipseSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "eclipse_music_settings"
)

@Singleton
class EclipseSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.eclipseSettingsDataStore

    private object Keys {
        val ENABLED = booleanPreferencesKey("eclipse_enabled")
        val BRIDGE_BASE_URL = stringPreferencesKey("eclipse_bridge_base_url")
        val MCP_BEARER_TOKEN = stringPreferencesKey("eclipse_mcp_bearer_token")
        val SHOW_MUSIC_IN_SIDEBAR = booleanPreferencesKey("eclipse_show_music_in_sidebar")
    }

    companion object {
        const val DEFAULT_BRIDGE_URL = "http://192.168.1.250:8771"
    }

    val settings: Flow<EclipseSettings> = dataStore.data.map { prefs ->
        EclipseSettings(
            enabled = prefs[Keys.ENABLED] ?: true,
            bridgeBaseUrl = (prefs[Keys.BRIDGE_BASE_URL]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BRIDGE_URL).trimEnd('/'),
            mcpBearerToken = prefs[Keys.MCP_BEARER_TOKEN]?.trim().orEmpty(),
            showMusicInSidebar = prefs[Keys.SHOW_MUSIC_IN_SIDEBAR] ?: true,
        )
    }

    suspend fun getSettings(): EclipseSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.ENABLED] = enabled }
    }

    suspend fun setBridgeBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/').ifBlank { DEFAULT_BRIDGE_URL }
        dataStore.edit { prefs -> prefs[Keys.BRIDGE_BASE_URL] = normalized }
    }

    suspend fun setMcpBearerToken(token: String) {
        dataStore.edit { prefs -> prefs[Keys.MCP_BEARER_TOKEN] = token.trim() }
    }

    suspend fun setShowMusicInSidebar(show: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SHOW_MUSIC_IN_SIDEBAR] = show }
    }
}
