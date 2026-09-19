@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LocalNasSettingsContent(
    viewModel: LocalNasSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val toggleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching {
            (initialFocusRequester ?: toggleFocusRequester).requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Local & NAS Storage",
            subtitle = "Direct file playback from Shield-mounted network shares and local storage"
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
                    item(key = "local_nas_toggle") {
                        SettingsToggleRow(
                            title = "Enable Local & NAS Playback",
                            subtitle = "Discovered movies and TV shows play directly from file with zero buffering and no Debrid required",
                            checked = uiState.isEnabled,
                            onCheckedChange = { viewModel.toggleEnabled(it) },
                            modifier = Modifier.focusRequester(toggleFocusRequester)
                        )
                    }

                    item(key = "local_nas_scan_now") {
                        val scanSubtitle = when {
                            uiState.isScanning -> "Scanning storage directories in background..."
                            uiState.statusMessage != null -> uiState.statusMessage!!
                            uiState.lastScanTimeFormatted != null -> "Last scanned: ${uiState.lastScanTimeFormatted}"
                            else -> "Auto-detects /storage mounts including SMB network shares and USB drives"
                        }
                        SettingsActionRow(
                            title = if (uiState.isScanning) "Scanning..." else "Scan Storage Now",
                            subtitle = scanSubtitle,
                            onClick = { viewModel.scanNow() }
                        )
                    }

                    item(key = "local_nas_summary") {
                        SettingsInfoRow(
                            title = "Media Discovered",
                            subtitle = "${uiState.movieCount} Movies, ${uiState.seriesCount} TV Episodes (${uiState.totalItems} total files)"
                        )
                    }

                    if (uiState.detectedPaths.isNotEmpty()) {
                        item(key = "local_nas_paths_header") {
                            SettingsInfoRow(
                                title = "Detected Storage Paths (${uiState.detectedPaths.size})",
                                subtitle = uiState.detectedPaths.joinToString(", ")
                            )
                        }
                    }

                    item(key = "local_nas_clear_cache") {
                        SettingsActionRow(
                            title = "Clear Media Cache",
                            subtitle = "Remove discovered file indexes and rescan from scratch",
                            onClick = { viewModel.clearCache() }
                        )
                    }
                }
            }
        }
    }
}
