package com.nuvio.tv.ambient.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.ambient.AmbientChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Single DataStore instance scoped to the application context. */
private val Context.ambientSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ambient_settings"
)

/**
 * Persistent store for [AmbientSettings] backed by Jetpack DataStore.
 *
 * Exposes a reactive [settings] flow and granular update methods for every
 * configurable field. All writes are transactional and safe to call from any
 * coroutine scope.
 */
@Singleton
class AmbientSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val IS_ENABLED = booleanPreferencesKey("is_enabled")
        val IDLE_TIMEOUT_MINUTES = intPreferencesKey("idle_timeout_minutes")
        val DEFAULT_CHANNEL = stringPreferencesKey("default_channel")
        val RESUME_LAST_CHANNEL = booleanPreferencesKey("resume_last_channel")
        val PREFER_4K = booleanPreferencesKey("prefer_4k")
        val PREFER_HDR = booleanPreferencesKey("prefer_hdr")
        val ALLOW_DOLBY_VISION = booleanPreferencesKey("allow_dolby_vision")
        val MAX_RESOLUTION = stringPreferencesKey("max_resolution")
        val MIN_VIDEO_LENGTH_MINUTES = intPreferencesKey("min_video_length_minutes")
        val PRELOAD_NEXT_VISUAL = booleanPreferencesKey("preload_next_visual")
        val CROSSFADE_DURATION_MS = longPreferencesKey("crossfade_duration_ms")
        val VIDEO_MUTE_LOCKED_ON = booleanPreferencesKey("video_mute_locked_on")
        val WEATHER_AWARENESS = booleanPreferencesKey("weather_awareness")
        val TIME_AWARENESS = booleanPreferencesKey("time_awareness")
        val SEASON_AWARENESS = booleanPreferencesKey("season_awareness")
        val HA_AWARENESS = booleanPreferencesKey("ha_awareness")
        val RECENT_WATCH_AWARENESS = booleanPreferencesKey("recent_watch_awareness")
        val AI_AMBIENT_ENABLED = booleanPreferencesKey("ai_ambient_enabled")
        val HOLIDAY_EVENTS_ENABLED = booleanPreferencesKey("holiday_events_enabled")
        val SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val SHOW_DATE = booleanPreferencesKey("show_date")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val SHOW_LOCATION = booleanPreferencesKey("show_location")
        val SHOW_MEDIA_SUGGESTIONS = booleanPreferencesKey("show_media_suggestions")
        val OVERLAY_INTERVAL_SECONDS = intPreferencesKey("overlay_interval_seconds")
        val OLED_PROTECTION_ENABLED = booleanPreferencesKey("oled_protection_enabled")
        val DIM_AFTER_EXTENDED_IDLE = booleanPreferencesKey("dim_after_extended_idle")
        val AVOID_REPEATS = booleanPreferencesKey("avoid_repeats")
        val REPEAT_COOLDOWN_DAYS = intPreferencesKey("repeat_cooldown_days")
        val HA_LIGHTING_SYNC = stringPreferencesKey("ha_lighting_sync")
        val LAST_CHANNEL_NAME = stringPreferencesKey("last_channel_name")
    }

    /** Reactive stream of the current settings, falling back to defaults when unset. */
    val settings: Flow<AmbientSettings> = context.ambientSettingsDataStore.data.map { prefs ->
        val defaults = AmbientSettings()
        AmbientSettings(
            isEnabled = prefs[Keys.IS_ENABLED] ?: defaults.isEnabled,
            idleTimeoutMinutes = prefs[Keys.IDLE_TIMEOUT_MINUTES] ?: defaults.idleTimeoutMinutes,
            defaultChannel = prefs[Keys.DEFAULT_CHANNEL]
                ?.let { runCatching { AmbientChannel.valueOf(it) }.getOrNull() }
                ?: defaults.defaultChannel,
            resumeLastChannel = prefs[Keys.RESUME_LAST_CHANNEL] ?: defaults.resumeLastChannel,
            prefer4k = prefs[Keys.PREFER_4K] ?: defaults.prefer4k,
            preferHdr = prefs[Keys.PREFER_HDR] ?: defaults.preferHdr,
            allowDolbyVision = prefs[Keys.ALLOW_DOLBY_VISION] ?: defaults.allowDolbyVision,
            maxResolution = prefs[Keys.MAX_RESOLUTION] ?: defaults.maxResolution,
            minVideoLengthMinutes = prefs[Keys.MIN_VIDEO_LENGTH_MINUTES] ?: defaults.minVideoLengthMinutes,
            preloadNextVisual = prefs[Keys.PRELOAD_NEXT_VISUAL] ?: defaults.preloadNextVisual,
            crossfadeDurationMs = prefs[Keys.CROSSFADE_DURATION_MS] ?: defaults.crossfadeDurationMs,
            // Always locked on regardless of persisted value.
            videoMuteLockedOn = true,
            weatherAwareness = prefs[Keys.WEATHER_AWARENESS] ?: defaults.weatherAwareness,
            timeAwareness = prefs[Keys.TIME_AWARENESS] ?: defaults.timeAwareness,
            seasonAwareness = prefs[Keys.SEASON_AWARENESS] ?: defaults.seasonAwareness,
            haAwareness = prefs[Keys.HA_AWARENESS] ?: defaults.haAwareness,
            recentWatchAwareness = prefs[Keys.RECENT_WATCH_AWARENESS] ?: defaults.recentWatchAwareness,
            aiAmbientEnabled = prefs[Keys.AI_AMBIENT_ENABLED] ?: defaults.aiAmbientEnabled,
            holidayEventsEnabled = prefs[Keys.HOLIDAY_EVENTS_ENABLED] ?: defaults.holidayEventsEnabled,
            showClock = prefs[Keys.SHOW_CLOCK] ?: defaults.showClock,
            showDate = prefs[Keys.SHOW_DATE] ?: defaults.showDate,
            showWeather = prefs[Keys.SHOW_WEATHER] ?: defaults.showWeather,
            showLocation = prefs[Keys.SHOW_LOCATION] ?: defaults.showLocation,
            showMediaSuggestions = prefs[Keys.SHOW_MEDIA_SUGGESTIONS] ?: defaults.showMediaSuggestions,
            overlayIntervalSeconds = prefs[Keys.OVERLAY_INTERVAL_SECONDS] ?: defaults.overlayIntervalSeconds,
            oledProtectionEnabled = prefs[Keys.OLED_PROTECTION_ENABLED] ?: defaults.oledProtectionEnabled,
            dimAfterExtendedIdle = prefs[Keys.DIM_AFTER_EXTENDED_IDLE] ?: defaults.dimAfterExtendedIdle,
            avoidRepeats = prefs[Keys.AVOID_REPEATS] ?: defaults.avoidRepeats,
            repeatCooldownDays = prefs[Keys.REPEAT_COOLDOWN_DAYS] ?: defaults.repeatCooldownDays,
            haLightingSync = prefs[Keys.HA_LIGHTING_SYNC] ?: defaults.haLightingSync,
            lastChannelName = prefs[Keys.LAST_CHANNEL_NAME] ?: defaults.lastChannelName
        )
    }

    suspend fun setEnabled(value: Boolean) = edit { it[Keys.IS_ENABLED] = value }

    suspend fun setIdleTimeoutMinutes(value: Int) = edit { it[Keys.IDLE_TIMEOUT_MINUTES] = value }

    suspend fun setDefaultChannel(channel: AmbientChannel) =
        edit { it[Keys.DEFAULT_CHANNEL] = channel.name }

    suspend fun setLastChannelName(name: String) = edit { it[Keys.LAST_CHANNEL_NAME] = name }

    suspend fun setResumeLastChannel(value: Boolean) = edit { it[Keys.RESUME_LAST_CHANNEL] = value }

    suspend fun setPrefer4k(value: Boolean) = edit { it[Keys.PREFER_4K] = value }

    suspend fun setPreferHdr(value: Boolean) = edit { it[Keys.PREFER_HDR] = value }

    suspend fun setAllowDolbyVision(value: Boolean) = edit { it[Keys.ALLOW_DOLBY_VISION] = value }

    suspend fun setMaxResolution(value: String) = edit { it[Keys.MAX_RESOLUTION] = value }

    suspend fun setMinVideoLengthMinutes(value: Int) = edit { it[Keys.MIN_VIDEO_LENGTH_MINUTES] = value }

    suspend fun setPreloadNextVisual(value: Boolean) = edit { it[Keys.PRELOAD_NEXT_VISUAL] = value }

    suspend fun setCrossfadeDurationMs(value: Long) = edit { it[Keys.CROSSFADE_DURATION_MS] = value }

    suspend fun setWeatherAwareness(value: Boolean) = edit { it[Keys.WEATHER_AWARENESS] = value }

    suspend fun setTimeAwareness(value: Boolean) = edit { it[Keys.TIME_AWARENESS] = value }

    suspend fun setSeasonAwareness(value: Boolean) = edit { it[Keys.SEASON_AWARENESS] = value }

    suspend fun setHaAwareness(value: Boolean) = edit { it[Keys.HA_AWARENESS] = value }

    suspend fun setRecentWatchAwareness(value: Boolean) = edit { it[Keys.RECENT_WATCH_AWARENESS] = value }

    suspend fun setAiAmbientEnabled(value: Boolean) = edit { it[Keys.AI_AMBIENT_ENABLED] = value }

    suspend fun setHolidayEventsEnabled(value: Boolean) = edit { it[Keys.HOLIDAY_EVENTS_ENABLED] = value }

    suspend fun setShowClock(value: Boolean) = edit { it[Keys.SHOW_CLOCK] = value }

    suspend fun setShowDate(value: Boolean) = edit { it[Keys.SHOW_DATE] = value }

    suspend fun setShowWeather(value: Boolean) = edit { it[Keys.SHOW_WEATHER] = value }

    suspend fun setShowLocation(value: Boolean) = edit { it[Keys.SHOW_LOCATION] = value }

    suspend fun setShowMediaSuggestions(value: Boolean) = edit { it[Keys.SHOW_MEDIA_SUGGESTIONS] = value }

    suspend fun setOverlayIntervalSeconds(value: Int) = edit { it[Keys.OVERLAY_INTERVAL_SECONDS] = value }

    suspend fun setOledProtectionEnabled(value: Boolean) = edit { it[Keys.OLED_PROTECTION_ENABLED] = value }

    suspend fun setDimAfterExtendedIdle(value: Boolean) = edit { it[Keys.DIM_AFTER_EXTENDED_IDLE] = value }

    suspend fun setAvoidRepeats(value: Boolean) = edit { it[Keys.AVOID_REPEATS] = value }

    suspend fun setRepeatCooldownDays(value: Int) = edit { it[Keys.REPEAT_COOLDOWN_DAYS] = value }

    suspend fun setHaLightingSync(value: String) = edit { it[Keys.HA_LIGHTING_SYNC] = value }

    /** Clears all persisted values, restoring [AmbientSettings] defaults. */
    suspend fun resetToDefaults() {
        context.ambientSettingsDataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.ambientSettingsDataStore.edit(block)
    }
}
