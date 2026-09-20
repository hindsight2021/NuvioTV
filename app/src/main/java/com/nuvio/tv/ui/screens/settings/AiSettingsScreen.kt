@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.DisposableEffect
import com.nuvio.tv.core.ai.AiPreferences
import com.nuvio.tv.core.ai.AiProvider
import com.nuvio.tv.core.ai.AiTtsPlayer
import com.nuvio.tv.core.ai.AiVoicePersona
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun AiSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val prefs = remember { AiPreferences(context) }

    var activeProvider by remember { mutableStateOf(prefs.activeProvider) }
    var currentKey by remember { mutableStateOf(prefs.getApiKey(activeProvider)) }
    var currentModel by remember { mutableStateOf(prefs.getModel(activeProvider)) }
    var isTtsEnabled by remember { mutableStateOf(prefs.isTtsEnabled) }
    var isAiSearchEnabled by remember { mutableStateOf(prefs.isAiSearchEnabled) }
    var currentVoicePersona by remember { mutableStateOf(prefs.ttsVoicePersona) }
    var customPersona by remember { mutableStateOf(prefs.customPersona) }

    var showProviderDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showVoicePersonaDialog by remember { mutableStateOf(false) }

    val refreshState = {
        activeProvider = prefs.activeProvider
        currentKey = prefs.getApiKey(activeProvider)
        currentModel = prefs.getModel(activeProvider)
        isTtsEnabled = prefs.isTtsEnabled
        isAiSearchEnabled = prefs.isAiSearchEnabled
        currentVoicePersona = prefs.ttsVoicePersona
        customPersona = prefs.customPersona
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "AI Assistant & Conversational Search",
            subtitle = "Integrate Gemini, OpenAI, Claude, Grok, and OpenRouter for real-time voice intelligence."
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "ai_provider") {
                        SettingsActionRow(
                            title = "AI Provider",
                            subtitle = null,
                            value = activeProvider.displayName,
                            onClick = { showProviderDialog = true },
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

                    item(key = "ai_api_key") {
                        val maskedKey = if (currentKey.isBlank()) {
                            "Not Configured"
                        } else {
                            "••••" + currentKey.takeLast(4)
                        }
                        SettingsActionRow(
                            title = "${activeProvider.displayName} API Key",
                            subtitle = null,
                            value = maskedKey,
                            onClick = { showApiKeyDialog = true }
                        )
                    }

                    item(key = "ai_model") {
                        SettingsActionRow(
                            title = "Model",
                            subtitle = null,
                            value = currentModel,
                            onClick = { showModelDialog = true }
                        )
                    }

                    item(key = "ai_tts") {
                        SettingsToggleRow(
                            title = "Voice Talk-Back (TTS)",
                            subtitle = "AI speaks movie recommendations and answers aloud over TV speakers.",
                            checked = isTtsEnabled,
                            onToggle = {
                                val next = !isTtsEnabled
                                isTtsEnabled = next
                                prefs.isTtsEnabled = next
                            }
                        )
                    }

                    item(key = "ai_tts_voice_persona") {
                        SettingsActionRow(
                            title = "Critic & Voice Persona",
                            subtitle = currentVoicePersona.description,
                            value = currentVoicePersona.displayName,
                            onClick = { showVoicePersonaDialog = true }
                        )
                    }

                    item(key = "ai_search_enabled") {
                        SettingsToggleRow(
                            title = "AI in Search",
                            subtitle = "Show secondary AI recommendations alongside addon search results.",
                            checked = isAiSearchEnabled,
                            onToggle = {
                                val next = !isAiSearchEnabled
                                isAiSearchEnabled = next
                                prefs.isAiSearchEnabled = next
                            }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    if (showProviderDialog) {
        AiProviderSelectionDialog(
            selected = activeProvider,
            onSelect = { provider ->
                prefs.activeProvider = provider
                showProviderDialog = false
                refreshState()
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    if (showApiKeyDialog) {
        AiApiKeyDialog(
            providerName = activeProvider.displayName,
            initialKey = currentKey,
            onSave = { newKey ->
                prefs.setApiKey(activeProvider, newKey)
                showApiKeyDialog = false
                refreshState()
                Toast.makeText(context, "${activeProvider.displayName} key saved", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showModelDialog) {
        AiModelDialog(
            provider = activeProvider,
            currentModel = currentModel,
            onSave = { newModel ->
                prefs.setModel(activeProvider, newModel)
                showModelDialog = false
                refreshState()
            },
            onDismiss = { showModelDialog = false }
        )
    }

    if (showVoicePersonaDialog) {
        AiVoicePersonaSelectionDialog(
            selected = currentVoicePersona,
            onSelect = { persona ->
                prefs.ttsVoicePersona = persona
                showVoicePersonaDialog = false
                refreshState()
            },
            onDismiss = { showVoicePersonaDialog = false }
        )
    }
}

@Composable
private fun AiProviderSelectionDialog(
    selected: AiProvider,
    onSelect: (AiProvider) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }

    NuvioDialog(
        title = "Select AI Provider",
        subtitle = "Choose which intelligence engine powers conversational voice search.",
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEachIndexed { index, provider ->
                val isSelected = provider == selected
                Button(
                    onClick = { onSelect(provider) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(text = if (isSelected) "✓  ${provider.displayName}" else provider.displayName)
                }
            }
        }
    }
}

@Composable
private fun AiApiKeyDialog(
    providerName: String,
    initialKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyText by remember { mutableStateOf(initialKey) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    NuvioDialog(
        title = "$providerName API Key",
        subtitle = "Enter your API key to enable real-time conversational search and AI curation.",
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                onClick = {},
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                ),
                shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary
                    ),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            onSave(keyText.trim())
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
                                onSave(keyText.trim())
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
                    onClick = {
                        keyboardController?.hide()
                        onSave(keyText.trim())
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.FocusBackground,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun AiModelDialog(
    provider: AiProvider,
    currentModel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = when (provider) {
        AiProvider.GEMINI -> listOf("gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash", "gemini-1.5-pro")
        AiProvider.OPENAI -> listOf("gpt-4o-mini", "gpt-4o", "o3-mini", "gpt-4.5-preview")
        AiProvider.ANTHROPIC -> listOf("claude-3-5-haiku-20241022", "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022")
        AiProvider.GROK -> listOf("grok-2-latest", "grok-beta")
        AiProvider.OPENROUTER -> listOf("google/gemini-2.0-flash-001", "deepseek/deepseek-chat", "meta-llama/llama-3.3-70b-instruct")
    }

    var isCustomMode by remember { mutableStateOf(!presets.contains(currentModel)) }
    var customModelText by remember { mutableStateOf(currentModel) }
    val initialFocus = remember { FocusRequester() }
    val customFocus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isCustomMode) {
        if (isCustomMode) customFocus.requestFocus() else initialFocus.requestFocus()
    }

    NuvioDialog(
        title = "${provider.displayName} Model",
        subtitle = "Select preset or enter custom model identifier.",
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isCustomMode) {
                presets.forEachIndexed { index, model ->
                    val isSelected = model == currentModel
                    Button(
                        onClick = { onSave(model) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text(text = if (isSelected) "✓  $model" else model)
                    }
                }

                Button(
                    onClick = { isCustomMode = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary
                    )
                ) {
                    Text(text = "✏️  Enter Custom Model...")
                }
            } else {
                Card(
                    onClick = {},
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.BackgroundCard
                    ),
                    shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicTextField(
                        value = customModelText,
                        onValueChange = { customModelText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = NuvioTheme.colors.TextPrimary
                        ),
                        cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                if (customModelText.isNotBlank()) onSave(customModelText.trim())
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .focusRequester(customFocus)
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER
                                ) {
                                    keyboardController?.hide()
                                    if (customModelText.isNotBlank()) onSave(customModelText.trim())
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
                        onClick = { isCustomMode = false },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary
                        )
                    ) {
                        Text("Presets")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            if (customModelText.isNotBlank()) onSave(customModelText.trim())
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.FocusBackground,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun AiVoicePersonaSelectionDialog(
    selected: AiVoicePersona,
    onSelect: (AiVoicePersona) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ttsPlayer = remember { AiTtsPlayer(context) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    DisposableEffect(Unit) {
        onDispose { ttsPlayer.shutdown() }
    }

    NuvioDialog(
        title = "Critic & Voice Persona",
        subtitle = "Select voice inflection and tone for Chic Reviews and conversational audio readback.",
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiVoicePersona.entries.forEachIndexed { index, persona ->
                val isSelected = persona == selected
                Button(
                    onClick = {
                        ttsPlayer.previewPersona(persona)
                        onSelect(persona)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = if (isSelected) "✓  ${persona.displayName}" else persona.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = persona.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

