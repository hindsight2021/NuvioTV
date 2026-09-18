@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ambient.AmbientChannel
import com.nuvio.tv.ambient.coordinator.AmbientCoordinator
import com.nuvio.tv.ambient.settings.AmbientSettings
import com.nuvio.tv.ambient.settings.AmbientSettingsDataStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

private val TIMEOUT_OPTIONS = listOf(
    "Off (Never)" to 0,
    "2 minutes" to 2,
    "5 minutes" to 5,
    "10 minutes" to 10,
    "15 minutes" to 15,
    "30 minutes" to 30
)

@Composable
fun AmbientSettingsContent(
    settingsDataStore: AmbientSettingsDataStore,
    coordinator: AmbientCoordinator? = null,
    initialFocusRequester: FocusRequester? = null
) {
    val settings by settingsDataStore.settings.collectAsState(initial = AmbientSettings())
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }

    val timeoutLabel = TIMEOUT_OPTIONS.firstOrNull { it.second == settings.idleTimeoutMinutes }?.first
        ?: "${settings.idleTimeoutMinutes} minutes"

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Nuvio Ambient",
            subtitle = "Cinematic idle screensaver with contextual awareness, 4K HDR visuals, and Home Assistant intelligence."
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "ambient_enabled") {
                        SettingsToggleRow(
                            title = "Enable Nuvio Ambient",
                            subtitle = "Automatically start cinematic visuals when the TV is idle.",
                            checked = settings.isEnabled,
                            onToggle = {
                                scope.launch { settingsDataStore.setEnabled(!settings.isEnabled) }
                            },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }

                    if (coordinator != null) {
                        item(key = "ambient_start_now") {
                            SettingsActionRow(
                                title = "Start Ambient Screensaver Now",
                                subtitle = "Immediately launch the ambient experience.",
                                value = "Start",
                                onClick = { coordinator.startAmbient() }
                            )
                        }
                    }

                    item(key = "ambient_timeout") {
                        SettingsActionRow(
                            title = "Idle Timeout",
                            subtitle = "How long the TV remains inactive before starting ambient visuals.",
                            value = timeoutLabel,
                            onClick = { showTimeoutDialog = true }
                        )
                    }

                    item(key = "ambient_default_channel") {
                        SettingsActionRow(
                            title = "Default Channel",
                            subtitle = settings.defaultChannel.description,
                            value = settings.defaultChannel.displayName,
                            onClick = { showChannelDialog = true }
                        )
                    }

                    item(key = "ambient_resume_last") {
                        SettingsToggleRow(
                            title = "Resume Last Viewed Channel",
                            subtitle = "Remember and reopen the most recently active ambient channel.",
                            checked = settings.resumeLastChannel,
                            onToggle = {
                                scope.launch { settingsDataStore.setResumeLastChannel(!settings.resumeLastChannel) }
                            }
                        )
                    }

                    item(key = "ambient_prefer_4k") {
                        SettingsToggleRow(
                            title = "Prefer 4K UHD Visuals",
                            subtitle = "Select Ultra High Definition streams for maximum clarity on 4K TVs.",
                            checked = settings.prefer4k,
                            onToggle = {
                                scope.launch { settingsDataStore.setPrefer4k(!settings.prefer4k) }
                            }
                        )
                    }

                    item(key = "ambient_prefer_hdr") {
                        SettingsToggleRow(
                            title = "Prefer HDR Content",
                            subtitle = "Prioritize High Dynamic Range video on compatible displays.",
                            checked = settings.preferHdr,
                            onToggle = {
                                scope.launch { settingsDataStore.setPreferHdr(!settings.preferHdr) }
                            }
                        )
                    }

                    item(key = "ambient_ha_weather") {
                        SettingsToggleRow(
                            title = "Home Assistant & Weather Awareness",
                            subtitle = "Sync visuals to real-time local weather and home environment conditions.",
                            checked = settings.weatherAwareness && settings.haAwareness,
                            onToggle = {
                                val next = !(settings.weatherAwareness && settings.haAwareness)
                                scope.launch {
                                    settingsDataStore.setWeatherAwareness(next)
                                    settingsDataStore.setHaAwareness(next)
                                }
                            }
                        )
                    }

                    item(key = "ambient_time_season") {
                        SettingsToggleRow(
                            title = "Time & Seasonal Context",
                            subtitle = "Match scenes to morning, golden hour, night, and current season.",
                            checked = settings.timeAwareness && settings.seasonAwareness,
                            onToggle = {
                                val next = !(settings.timeAwareness && settings.seasonAwareness)
                                scope.launch {
                                    settingsDataStore.setTimeAwareness(next)
                                    settingsDataStore.setSeasonAwareness(next)
                                }
                            }
                        )
                    }

                    item(key = "ambient_recent_watch") {
                        SettingsToggleRow(
                            title = "Recent Watch Context",
                            subtitle = "Gently influence ambient moods based on movies or shows recently watched.",
                            checked = settings.recentWatchAwareness,
                            onToggle = {
                                scope.launch { settingsDataStore.setRecentWatchAwareness(!settings.recentWatchAwareness) }
                            }
                        )
                    }

                    item(key = "ambient_oled_protection") {
                        SettingsToggleRow(
                            title = "OLED Burn-in Protection",
                            subtitle = "Periodically drift clock and overlay coordinates to safeguard OLED pixels.",
                            checked = settings.oledProtectionEnabled,
                            onToggle = {
                                scope.launch { settingsDataStore.setOledProtectionEnabled(!settings.oledProtectionEnabled) }
                            }
                        )
                    }

                    item(key = "ambient_dim_idle") {
                        SettingsToggleRow(
                            title = "Dim After Extended Idle",
                            subtitle = "Gradually dim display brightness after 60 and 120 minutes of continuous idle.",
                            checked = settings.dimAfterExtendedIdle,
                            onToggle = {
                                scope.launch { settingsDataStore.setDimAfterExtendedIdle(!settings.dimAfterExtendedIdle) }
                            }
                        )
                    }

                    item(key = "ambient_avoid_repeats") {
                        SettingsToggleRow(
                            title = "Avoid Repeats",
                            subtitle = "Enforce a 7-day cooldown before repeating previously played visual scenes.",
                            checked = settings.avoidRepeats,
                            onToggle = {
                                scope.launch { settingsDataStore.setAvoidRepeats(!settings.avoidRepeats) }
                            }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showTimeoutDialog) {
        val initialFocus = remember { FocusRequester() }
        NuvioDialog(
            title = "Idle Timeout",
            subtitle = "Select how long before Nuvio Ambient starts.",
            onDismiss = { showTimeoutDialog = false }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TIMEOUT_OPTIONS.forEachIndexed { index, (label, minutes) ->
                    val isSelected = minutes == settings.idleTimeoutMinutes
                    Button(
                        onClick = {
                            scope.launch { settingsDataStore.setIdleTimeoutMinutes(minutes) }
                            showTimeoutDialog = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(text = if (isSelected) "✓  $label" else label)
                    }
                }
            }
        }
    }

    if (showChannelDialog) {
        val initialFocus = remember { FocusRequester() }
        NuvioDialog(
            title = "Default Ambient Channel",
            subtitle = "Choose the initial theme when ambient mode activates.",
            onDismiss = { showChannelDialog = false }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmbientChannel.entries.forEachIndexed { index, channel ->
                    val isSelected = channel == settings.defaultChannel
                    Button(
                        onClick = {
                            scope.launch { settingsDataStore.setDefaultChannel(channel) }
                            showChannelDialog = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(text = if (isSelected) "✓  ${channel.displayName}" else channel.displayName)
                    }
                }
            }
        }
    }
}
