package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LocalMediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists local media scanning configuration and cached results, scoped to the active profile.
 */
@Singleton
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalMediaSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "local_media_settings"
        private val KEY_ENABLED = booleanPreferencesKey("local_media_enabled")
        private val KEY_SCAN_PATHS = stringPreferencesKey("local_media_scan_paths")
        private val KEY_LAST_SCAN_TIMESTAMP = longPreferencesKey("local_media_last_scan_timestamp")
        private val KEY_CACHED_ITEMS = stringPreferencesKey("local_media_cached_items")

        private val stringListType = object : TypeToken<List<String>>() {}.type
        private val itemListType = object : TypeToken<List<LocalMediaItem>>() {}.type
    }

    private val gson = Gson()

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    /** Emits the current [LocalMediaSettings] whenever any persisted value changes. */
    val settings: Flow<LocalMediaSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            LocalMediaSettings(
                enabled = prefs[KEY_ENABLED] ?: true,
                scanPaths = prefs[KEY_SCAN_PATHS]?.let(::decodeStringList) ?: emptyList(),
                lastScanTimestamp = prefs[KEY_LAST_SCAN_TIMESTAMP] ?: 0L,
                cachedItems = prefs[KEY_CACHED_ITEMS]?.let(::decodeItems) ?: emptyList()
            )
        }
    }

    /** Enables or disables local media scanning. */
    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    /** Replaces the full set of scan paths. */
    suspend fun setScanPaths(paths: List<String>) {
        store().edit { prefs -> prefs[KEY_SCAN_PATHS] = encodeStringList(paths) }
    }

    /** Adds a scan path if it is not already present. */
    suspend fun addScanPath(path: String) {
        store().edit { prefs ->
            val current = prefs[KEY_SCAN_PATHS]?.let(::decodeStringList) ?: emptyList()
            if (path !in current) {
                prefs[KEY_SCAN_PATHS] = encodeStringList(current + path)
            }
        }
    }

    /** Removes a scan path if present. */
    suspend fun removeScanPath(path: String) {
        store().edit { prefs ->
            val current = prefs[KEY_SCAN_PATHS]?.let(::decodeStringList) ?: emptyList()
            if (path in current) {
                prefs[KEY_SCAN_PATHS] = encodeStringList(current - path)
            }
        }
    }

    /**
     * Persists the result of a scan: the discovered items and the timestamp at which the scan
     * completed.
     */
    suspend fun saveScanResult(items: List<LocalMediaItem>, timestamp: Long = System.currentTimeMillis()) {
        store().edit { prefs ->
            prefs[KEY_CACHED_ITEMS] = gson.toJson(items)
            prefs[KEY_LAST_SCAN_TIMESTAMP] = timestamp
        }
    }

    /** Clears the cached scan results and resets the last scan timestamp. */
    suspend fun clearCache() {
        store().edit { prefs ->
            prefs.remove(KEY_CACHED_ITEMS)
            prefs.remove(KEY_LAST_SCAN_TIMESTAMP)
        }
    }

    private fun encodeStringList(values: List<String>): String = gson.toJson(values)

    private fun decodeStringList(json: String): List<String> =
        runCatching {
            gson.fromJson<List<String>>(json, stringListType)
        }.getOrNull() ?: emptyList()

    private fun decodeItems(json: String): List<LocalMediaItem> =
        runCatching {
            gson.fromJson<List<LocalMediaItem>>(json, itemListType)
        }.getOrNull() ?: emptyList()
}

/** Immutable snapshot of the local media settings for the active profile. */
data class LocalMediaSettings(
    val enabled: Boolean = true,
    val scanPaths: List<String> = emptyList(),
    val lastScanTimestamp: Long = 0L,
    val cachedItems: List<LocalMediaItem> = emptyList()
)
