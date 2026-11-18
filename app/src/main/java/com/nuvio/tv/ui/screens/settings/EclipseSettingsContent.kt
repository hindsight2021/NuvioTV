@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Eclipse Music integration settings panel under Settings → Integration.
 */
@Composable
fun EclipseSettingsContent(
    viewModel: EclipseSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val firstFocusRequester = remember { FocusRequester() }

    var showUrlDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            (initialFocusRequester ?: firstFocusRequester).requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_eclipse_music_title),
            subtitle = stringResource(R.string.settings_eclipse_music_subtitle)
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
                    item(key = "eclipse_enable") {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_eclipse_music_enable_title),
                            subtitle = stringResource(R.string.settings_eclipse_music_enable_subtitle),
                            checked = uiState.enabled,
                            onToggle = { viewModel.setEnabled(!uiState.enabled) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    Modifier.focusRequester(initialFocusRequester ?: firstFocusRequester)
                                )
                        )
                    }

                    item(key = "eclipse_sidebar") {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_eclipse_music_sidebar_title),
                            subtitle = stringResource(R.string.settings_eclipse_music_sidebar_subtitle),
                            checked = uiState.showMusicInSidebar,
                            onToggle = { viewModel.setShowMusicInSidebar(!uiState.showMusicInSidebar) }
                        )
                    }

                    item(key = "eclipse_bridge_url") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_eclipse_music_url_title),
                            subtitle = "LAN HTTP URL of your EclipseMCP container (default :8771)",
                            value = uiState.bridgeBaseUrl.ifBlank { "Not configured" },
                            leadingIcon = Icons.Default.Link,
                            onClick = { showUrlDialog = true }
                        )
                    }

                    item(key = "eclipse_bearer_token") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_eclipse_music_token_title),
                            subtitle = if (uiState.mcpBearerToken.isBlank()) {
                                stringResource(R.string.settings_eclipse_music_token_subtitle_empty)
                            } else {
                                stringResource(R.string.settings_eclipse_music_token_subtitle_set)
                            },
                            value = maskEclipseToken(uiState.mcpBearerToken).ifBlank { "Empty" },
                            leadingIcon = Icons.Default.VpnKey,
                            onClick = { showTokenDialog = true }
                        )
                    }

                    item(key = "eclipse_test_connection") {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_eclipse_music_test_title),
                            subtitle = when {
                                uiState.isTesting -> "Testing EclipseMCP connection…"
                                uiState.error != null -> "Error: ${uiState.error}"
                                uiState.testResult != null -> uiState.testResult.orEmpty()
                                else -> stringResource(R.string.settings_eclipse_music_test_subtitle)
                            },
                            value = when {
                                uiState.isTesting -> "…"
                                uiState.testResult != null -> "OK"
                                uiState.error != null -> "Failed"
                                else -> "Run"
                            },
                            leadingIcon = Icons.Default.NetworkCheck,
                            onClick = { viewModel.testConnection() }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showUrlDialog) {
        EclipseTextEditDialog(
            title = stringResource(R.string.settings_eclipse_music_url_title),
            subtitle = "Example: http://192.168.1.250:8771",
            initialValue = uiState.bridgeBaseUrl,
            keyboardType = KeyboardType.Uri,
            masked = false,
            onDismiss = { showUrlDialog = false },
            onSave = { value ->
                viewModel.setBridgeBaseUrl(value)
                showUrlDialog = false
            }
        )
    }

    if (showTokenDialog) {
        EclipseTextEditDialog(
            title = stringResource(R.string.settings_eclipse_music_token_title),
            subtitle = "Bearer token configured as ECLIPSE_MCP_TOKEN on EclipseMCP.",
            initialValue = uiState.mcpBearerToken,
            keyboardType = KeyboardType.Password,
            masked = true,
            onDismiss = { showTokenDialog = false },
            onSave = { value ->
                viewModel.setMcpBearerToken(value)
                showTokenDialog = false
            }
        )
    }
}

@Composable
private fun EclipseTextEditDialog(
    title: String,
    subtitle: String,
    initialValue: String,
    keyboardType: KeyboardType,
    masked: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var textValue by remember(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    NuvioDialog(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                onClick = {},
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary
                    ),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            onSave(textValue.trim())
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                            ) {
                                keyboardController?.hide()
                                onSave(textValue.trim())
                                true
                            } else {
                                false
                            }
                        }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary
                    )
                ) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = { onSave(textValue.trim()) },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Primary,
                        contentColor = NuvioTheme.colors.OnPrimary
                    )
                ) {
                    Text("Save")
                }
            }
        }
    }
}

internal fun maskEclipseToken(token: String): String {
    if (token.isBlank()) return ""
    return "••••" + token.takeLast(4)
}
