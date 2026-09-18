package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trailer_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")
    private val detailAudioEnabledKey = booleanPreferencesKey("detail_trailer_audio_enabled")

    val settings: Flow<TrailerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TrailerSettings(
                enabled = prefs[enabledKey] ?: true,
                delaySeconds = prefs[delaySecondsKey] ?: 7,
                detailAudioEnabled = prefs[detailAudioEnabledKey] ?: true
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds }
    }

    suspend fun setDetailAudioEnabled(enabled: Boolean) {
        store().edit { it[detailAudioEnabledKey] = enabled }
    }
}

data class TrailerSettings(
    val enabled: Boolean = true,
    val delaySeconds: Int = 7,
    val detailAudioEnabled: Boolean = true
)
