@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.livetv

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.ai.AiTtsPlayer
import com.nuvio.tv.core.ai.AiVoicePersona
import com.nuvio.tv.core.livetv.ActiveAiBroadcastStation
import com.nuvio.tv.core.livetv.AiBroadcastChannelConfig
import com.nuvio.tv.core.livetv.AiBroadcastEpgSlot
import com.nuvio.tv.core.livetv.AiBroadcastNetwork
import com.nuvio.tv.core.livetv.AiBroadcastPreferences
import com.nuvio.tv.core.livetv.LiveTvManager
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Horizontal multi-slot EPG timeline, DJ host badge, and action controls for an AI Broadcast channel.
 */
@Composable
fun AiBroadcastEpgTimelinePanel(
    config: AiBroadcastChannelConfig,
    slots: List<AiBroadcastEpgSlot>,
    isRegenerating: Boolean,
    onTuneLive: () -> Unit,
    onWatchBeginning: () -> Unit,
    onQueueBlock: () -> Unit,
    onRegenerateLineup: () -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.US) }
    val persona = remember(config.voicePersonaId) { AiVoicePersona.fromId(config.voicePersonaId) }
    val accentColor = remember(config.accentColorHex) {
        runCatching { Color(android.graphics.Color.parseColor(config.accentColorHex)) }
            .getOrDefault(Color(0xFF7C3AED))
    }
    val onAirSlot = slots.firstOrNull { it.isOnAirNow }
    val elapsedMinutes = ((onAirSlot?.elapsedMs ?: 0L) / 60_000L).toInt()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // DJ Host + Persona pill row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accentColor.copy(alpha = 0.25f))
                    .border(1.dp, accentColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "AI DJ · ${config.djHostName.uppercase()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
            Text(
                text = "${persona.displayName} · \"${config.tagline}\"",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Primary Broadcast Action Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onTuneLive,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    focusedContainerColor = Color.White
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = if (elapsedMinutes > 0) "Tune Live (${elapsedMinutes}m in)" else "Tune Live",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.Black
                )
            }

            Button(
                onClick = onWatchBeginning,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.SurfaceVariant,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = "Watch from Beginning",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }

            Button(
                onClick = onQueueBlock,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.SurfaceVariant,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = "Queue Block",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }

            Button(
                onClick = onRegenerateLineup,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.SurfaceVariant,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = if (isRegenerating) "Refreshing..." else "AI Lineup",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }

            Button(
                onClick = onCustomize,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.SurfaceVariant,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = "Customize",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
        }

        // Horizontal EPG Schedule Strip
        Text(
            text = "PROGRAM SCHEDULE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextTertiary
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(
                items = slots,
                key = { "${it.program.contentId}_${it.startTimestampMs}" }
            ) { slot ->
                val windowText = if (slot.isOnAirNow) {
                    "ON AIR · ${slot.remainingMinutes}m left"
                } else {
                    "${timeFormat.format(Date(slot.startTimestampMs))} · ${slot.program.durationMinutes}m"
                }
                val epBadge = if (slot.program.season != null && slot.program.episode != null) {
                    "S${slot.program.season}E${slot.program.episode}"
                } else {
                    slot.program.genre.ifBlank { slot.program.mediaType.uppercase() }
                }
                val durationMs = (slot.endTimestampMs - slot.startTimestampMs).coerceAtLeast(1L)
                val progress = (slot.elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

                Card(
                    onClick = if (slot.isOnAirNow) onTuneLive else onQueueBlock,
                    colors = CardDefaults.colors(
                        containerColor = if (slot.isOnAirNow) {
                            accentColor.copy(alpha = 0.22f)
                        } else {
                            NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.55f)
                        },
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, NuvioTheme.colors.Primary),
                            shape = RoundedCornerShape(10.dp)
                        )
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    modifier = Modifier.width(225.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = windowText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (slot.isOnAirNow) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary
                            )
                            Text(
                                text = epBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }

                        Text(
                            text = slot.program.title,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (slot.program.djIntroLine.isNotBlank()) {
                            Text(
                                text = "\"${slot.program.djIntroLine}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (slot.isOnAirNow) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progress)
                                        .fillMaxHeight()
                                        .background(accentColor)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * TV D-pad dialog for creating a new AI Broadcast channel or customizing an existing channel's
 * DJ host, voice persona, theme prompt, and schedule.
 */
@Composable
fun AiBroadcastCreateOrEditDialog(
    existingConfig: AiBroadcastChannelConfig?,
    onSaved: (AiBroadcastChannelConfig) -> Unit,
    onDeleted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AiBroadcastPreferences(context) }
    val ttsPlayer = remember { AiTtsPlayer(context) }
    val initialFocus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onDispose { ttsPlayer.shutdown() }
    }
    LaunchedEffect(Unit) {
        runCatching { initialFocus.requestFocus() }
    }

    var themePrompt by remember { mutableStateOf(existingConfig?.themePrompt ?: "") }
    var channelName by remember { mutableStateOf(existingConfig?.name ?: "") }
    var djHostName by remember { mutableStateOf(existingConfig?.djHostName ?: "") }
    var selectedPersona by remember {
        mutableStateOf(
            existingConfig?.let { AiVoicePersona.fromId(it.voicePersonaId) }
                ?: AiVoicePersona.CHIC_CRITIC_BRITISH
        )
    }
    var isBusy by remember { mutableStateOf(false) }

    NuvioDialog(
        title = if (existingConfig == null) "Create 24/7 AI Channel" else "Customize ${existingConfig.name}",
        subtitle = "Design a themed live channel with an AI DJ host and voice persona.",
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTextFieldCard(
                label = "Channel Theme / Vibe",
                value = themePrompt,
                placeholder = "e.g. 90s Cozy Detective Mysteries",
                onValueChange = { themePrompt = it },
                modifier = Modifier.focusRequester(initialFocus)
            )

            DialogTextFieldCard(
                label = "Channel Name (optional for new)",
                value = channelName,
                placeholder = "e.g. COZY NOIR TV",
                onValueChange = { channelName = it }
            )

            DialogTextFieldCard(
                label = "DJ Host Name",
                value = djHostName,
                placeholder = "e.g. Reginald",
                onValueChange = { djHostName = it }
            )

            Text(
                text = "DJ Voice Persona (click to preview)",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiVoicePersona.entries.forEach { persona ->
                    val isSelected = persona == selectedPersona
                    Button(
                        onClick = {
                            selectedPersona = persona
                            ttsPlayer.previewPersona(persona)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (isSelected) Color.Black else NuvioTheme.colors.TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = persona.displayName.substringBefore(" "),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (existingConfig == null) {
                    Button(
                        onClick = {
                            if (isBusy) return@Button
                            val theme = themePrompt.trim().ifBlank { channelName.trim() }
                            if (theme.isBlank()) {
                                Toast.makeText(context, "Enter a channel theme first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isBusy = true
                            scope.launch {
                                val res = AiBroadcastNetwork.createCustomChannelWithAi(
                                    context = context,
                                    themePrompt = theme,
                                    channelName = channelName.trim().ifBlank { null },
                                    djHostName = djHostName.trim().ifBlank { null },
                                    voicePersonaId = selectedPersona.id
                                )
                                isBusy = false
                                res.onSuccess { created ->
                                    LiveTvManager.refreshAiChannels(context)
                                    onSaved(created)
                                }.onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        "AI channel creation failed: ${err.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isBusy) "Generating..." else "Generate with AI")
                    }
                } else {
                    Button(
                        onClick = {
                            if (isBusy) return@Button
                            val updated = existingConfig.copy(
                                themePrompt = themePrompt.trim().ifBlank { existingConfig.themePrompt },
                                name = channelName.trim().ifBlank { existingConfig.name },
                                djHostName = djHostName.trim().ifBlank { existingConfig.djHostName },
                                voicePersonaId = selectedPersona.id,
                                djStyle = selectedPersona.description
                            )
                            prefs.saveOrUpdateChannel(updated)
                            LiveTvManager.refreshAiChannels(context)
                            onSaved(updated)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Changes")
                    }

                    Button(
                        onClick = {
                            prefs.deleteOrResetChannel(existingConfig.id)
                            LiveTvManager.refreshAiChannels(context)
                            onDeleted(existingConfig.id)
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary
                        )
                    ) {
                        Text(if (existingConfig.isBuiltIn) "Reset Default" else "Delete")
                    }
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextSecondary
                    )
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun DialogTextFieldCard(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary
        )
        Card(
            onClick = {},
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.SurfaceVariant
            ),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            modifier = modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Brief top-right "ON AIR" station bug overlay shown inside the player when tuning an AI Broadcast channel.
 * Auto-hides after 8 seconds.
 */
@Composable
fun AiOnAirStationBug(
    station: ActiveAiBroadcastStation?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(station?.tunedAtMs) {
        if (station != null) {
            visible = true
            delay(8_000L)
            visible = false
        } else {
            visible = false
        }
    }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = visible && station != null,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it }
        ) {
            station?.let { s ->
                val accent = remember(s.accentColorHex) {
                    runCatching { Color(android.graphics.Color.parseColor(s.accentColorHex)) }
                        .getOrDefault(Color(0xFF7C3AED))
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD910131A))
                        .border(1.dp, accent, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ON AIR · CH ${s.channelNumber} · ${s.channelName} · Host: ${s.djHostName}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
