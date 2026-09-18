package com.nuvio.tv.ambient.settings

import com.nuvio.tv.ambient.AmbientChannel

/**
 * Immutable data class representing all user-configurable settings for the
 * Nuvio TV ambient mode feature.
 *
 * Defaults are chosen to provide a pleasant out-of-the-box experience while
 * keeping resource usage conservative.
 */
data class AmbientSettings(
    val isEnabled: Boolean = true,
    /** Idle timeout in minutes. 0 means "Off" (never auto-start). */
    val idleTimeoutMinutes: Int = 5,
    val defaultChannel: AmbientChannel = AmbientChannel.AUTO,
    val resumeLastChannel: Boolean = true,
    val prefer4k: Boolean = true,
    val preferHdr: Boolean = true,
    val allowDolbyVision: Boolean = false,
    val maxResolution: String = "4K",
    val minVideoLengthMinutes: Int = 20,
    val preloadNextVisual: Boolean = true,
    val crossfadeDurationMs: Long = 3000L,
    /** Always true; ambient video audio is permanently muted and cannot be changed. */
    val videoMuteLockedOn: Boolean = true,
    val weatherAwareness: Boolean = true,
    val timeAwareness: Boolean = true,
    val seasonAwareness: Boolean = true,
    val haAwareness: Boolean = true,
    val recentWatchAwareness: Boolean = true,
    val aiAmbientEnabled: Boolean = true,
    val holidayEventsEnabled: Boolean = true,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showLocation: Boolean = true,
    val showMediaSuggestions: Boolean = true,
    val overlayIntervalSeconds: Int = 180,
    val oledProtectionEnabled: Boolean = true,
    val dimAfterExtendedIdle: Boolean = true,
    val avoidRepeats: Boolean = true,
    val repeatCooldownDays: Int = 7,
    /** Home Assistant lighting sync mode: "off", "subtle", or "dynamic". */
    val haLightingSync: String = "off",
    val lastChannelName: String = "auto"
)
