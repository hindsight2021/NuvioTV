package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.ai.CuratedThematicMood
import com.nuvio.tv.core.ai.ThematicChannelGenerator
import com.nuvio.tv.core.ai.ThematicChannelResult
import com.nuvio.tv.core.ai.ThematicTrack
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ThematicChannelDialog(
    visible: Boolean,
    generator: ThematicChannelGenerator,
    onStartChannel: (ThematicChannelResult) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var channelResult by remember { mutableStateOf<ThematicChannelResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activePrompt by remember { mutableStateOf<String?>(null) }

    fun generate(prompt: String) {
        activePrompt = prompt
        isLoading = true
        errorMessage = null
        scope.launch {
            val res = generator.generateChannel(context, prompt)
            isLoading = false
            res.fold(
                onSuccess = { channelResult = it },
                onFailure = { errorMessage = it.message ?: "Failed to generate channel" }
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE608080C))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 580.dp, max = 800.dp)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF14121E))
                    .border(1.5.dp, Color(0x66AB47BC), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "✨", fontSize = 22.sp)
                            Text(
                                text = "AI Infinite Thematic Channels",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFFE1BEE7)
                            )
                        }

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0x26FFFFFF),
                                contentColor = Color.White
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp))
                        ) {
                            Text("✕", fontSize = 12.sp)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x33FFFFFF))
                    )

                    if (isLoading) {
                        Column(
                            modifier = Modifier.padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "✨ Gemini is assembling your custom channel lineup...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFFCE93D8),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Selecting peak episodes, matching vibes, and building seamless queue...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0B3BC),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (channelResult != null) {
                        val result = channelResult!!
                        // Channel Details Header
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = result.channelName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFF3E5F5)
                            )
                            Text(
                                text = result.tagline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFBA68C8)
                            )
                        }

                        // Tracklist takes remaining flexible space
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(result.tracks) { track ->
                                ThematicTrackRow(track)
                            }
                        }

                        // Buttons pinned at bottom
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onStartChannel(result) },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFF9C27B0),
                                    contentColor = Color.White
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("▶️ Launch Channel Queue", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            activePrompt?.let { prompt ->
                                Button(
                                    onClick = { generate(prompt) },
                                    colors = ButtonDefaults.colors(
                                        containerColor = Color(0x33FFFFFF),
                                        contentColor = Color.White
                                    ),
                                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                                ) {
                                    Text("🔄 Regenerate", fontSize = 13.sp)
                                }
                            }

                            Button(
                                onClick = { channelResult = null },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0x1AFFFFFF),
                                    contentColor = Color(0xFFB0B3BC)
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("Back to Moods", fontSize = 13.sp)
                            }
                        }
                    } else {
                        // Mood Picker State
                        Text(
                            text = "Select a vibe or theme to generate an instant, curated TV channel:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0B3BC)
                        )

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!,
                                color = Color(0xFFFF8A80),
                                fontSize = 13.sp
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            generator.curatedMoods.chunked(2).forEach { rowMoods ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    rowMoods.forEach { mood ->
                                        CuratedMoodCard(
                                            mood = mood,
                                            onClick = { generate(mood.prompt) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CuratedMoodCard(
    mood: CuratedThematicMood,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.colors(
            containerColor = Color(0xFF1E1B2C),
            focusedContainerColor = Color(0xFF38234A)
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = mood.icon, fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mood.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mood.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B3BC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThematicTrackRow(track: ThematicTrack) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x2AFFFFFF))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = track.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    if (track.season != null && track.episode != null) {
                        Text(
                            text = "S${track.season}E${track.episode}${track.episodeTitle?.let { ": $it" } ?: ""}",
                            color = Color(0xFFBA68C8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (track.type.equals("movie", ignoreCase = true)) {
                        Text(
                            text = "Movie",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    text = track.reason,
                    color = Color(0xFFD1C4E9),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
