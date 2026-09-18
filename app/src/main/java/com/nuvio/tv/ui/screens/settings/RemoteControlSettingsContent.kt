@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun RemoteControlSettingsContent(
    viewModel: RemoteControlSettingsViewModel = hiltViewModel(),
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
            title = stringResource(R.string.settings_remote_control_title),
            subtitle = stringResource(R.string.settings_remote_control_subtitle)
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
                    item(key = "remote_control_toggle") {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_remote_control_enable_title),
                            subtitle = stringResource(R.string.settings_remote_control_enable_subtitle),
                            checked = uiState.isEnabled,
                            onToggle = { viewModel.setEnabled(!uiState.isEnabled) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier.focusRequester(toggleFocusRequester)
                                    }
                                )
                        )
                    }

                    if (uiState.isEnabled) {
                        item(key = "remote_control_status") {
                            SettingsActionRow(
                                title = stringResource(R.string.settings_remote_control_status_title),
                                subtitle = stringResource(R.string.settings_remote_control_status_subtitle),
                                value = uiState.serverStatusText,
                                onClick = { viewModel.restartServer() },
                                leadingIcon = Icons.Default.Wifi
                            )
                        }

                        item(key = "remote_control_pin") {
                            SettingsActionRow(
                                title = stringResource(R.string.settings_remote_control_pin_title),
                                subtitle = stringResource(R.string.settings_remote_control_pin_subtitle),
                                value = if (uiState.pairingPin.isNotBlank()) "PIN: ${uiState.pairingPin}" else stringResource(R.string.settings_remote_control_value_unknown),
                                onClick = { viewModel.regeneratePin() },
                                leadingIcon = Icons.Default.VpnKey
                            )
                        }

                        item(key = "remote_control_token") {
                            SettingsActionRow(
                                title = stringResource(R.string.settings_remote_control_token_title),
                                subtitle = stringResource(R.string.settings_remote_control_token_subtitle),
                                value = if (uiState.apiToken.isNotBlank()) maskToken(uiState.apiToken) else stringResource(R.string.settings_remote_control_value_unknown),
                                onClick = { viewModel.regenerateToken() },
                                leadingIcon = Icons.Default.Refresh
                            )
                        }

                        item(key = "remote_control_ha_card") {
                            HomeAssistantSetupCard(
                                host = uiState.serverHost,
                                port = uiState.serverPort,
                                pin = uiState.pairingPin
                            )
                        }

                        if (uiState.pairingQrCode != null) {
                            item(key = "remote_control_qr") {
                                QrCodePairingSection(
                                    qrCode = uiState.pairingQrCode!!,
                                    webRemoteUrl = uiState.webRemoteUrl
                                )
                            }
                        }
                    }
                }

                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}

@Composable
fun RemoteControlSettingsScreen(
    viewModel: RemoteControlSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_remote_control_title),
        subtitle = stringResource(R.string.settings_remote_control_subtitle)
    ) {
        RemoteControlSettingsContent(viewModel = viewModel)
    }
}

@Composable
private fun HomeAssistantSetupCard(
    host: String,
    port: Int,
    pin: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_remote_control_home_assistant_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.settings_remote_control_home_assistant_body),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        SetupField(
            label = stringResource(R.string.settings_remote_control_home_assistant_host),
            value = host.ifBlank { stringResource(R.string.settings_remote_control_value_unknown) }
        )
        SetupField(
            label = stringResource(R.string.settings_remote_control_home_assistant_port),
            value = port.toString()
        )
        SetupField(
            label = stringResource(R.string.settings_remote_control_home_assistant_pin),
            value = pin.ifBlank { stringResource(R.string.settings_remote_control_value_unknown) }
        )
    }
}

@Composable
private fun SetupField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun QrCodePairingSection(
    qrCode: Bitmap,
    webRemoteUrl: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QrCode,
                contentDescription = null,
                tint = NuvioTheme.colors.Primary
            )
            Text(
                text = stringResource(R.string.settings_remote_control_qr_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Image(
            bitmap = qrCode.asImageBitmap(),
            contentDescription = stringResource(R.string.settings_remote_control_qr_content_description),
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard)
                .padding(8.dp)
        )

        Text(
            text = stringResource(R.string.settings_remote_control_qr_caption),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary
        )

        if (webRemoteUrl.isNotBlank()) {
            Text(
                text = webRemoteUrl,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Primary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun maskToken(token: String): String {
    if (token.length <= 10) return token
    val prefix = token.take(8)
    val suffix = token.takeLast(4)
    return "$prefix...$suffix"
}
