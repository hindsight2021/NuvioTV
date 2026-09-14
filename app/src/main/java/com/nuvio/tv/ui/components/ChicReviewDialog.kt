package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.ai.AiTtsPlayer
import com.nuvio.tv.core.ai.ChicCriticReview
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChicReviewDialog(
    visible: Boolean,
    title: String,
    review: ChicCriticReview?,
    isLoading: Boolean,
    errorMessage: String? = null,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val ttsPlayer = remember { AiTtsPlayer(context) }
    val isSpeaking by ttsPlayer.isSpeaking.collectAsState()

    // Speak when review arrives
    LaunchedEffect(review) {
        if (review != null) {
            val textToSpeak = "${review.headline}. ${review.reviewText}. Verdict: ${review.verdict}."
            ttsPlayer.speakChic(textToSpeak)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsPlayer.shutdown()
        }
    }

    Dialog(
        onDismissRequest = {
            ttsPlayer.stop()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 460.dp, max = 640.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF0140F22))
                    .border(
                        width = 1.5.dp,
                        color = Color(0x66FFD700),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(28.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            Text(
                                text = "🍸",
                                fontSize = 20.sp
                            )
                            Text(
                                text = "Mike & Chris Critic's Verdict",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFFFFDF70)
                            )
                        }

                        if (review != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x40FF4081))
                                    .border(1.dp, Color(0x80FF4081), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = review.campScore,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFFF80AB)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x26FFFFFF))
                    )

                    if (isLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "✨ Consulting our fabulous critic for \"$title\"...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFD1C4E9),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF8A80),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else if (review != null) {
                        // Headline
                        Text(
                            text = "\"${review.headline}\"",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                fontSize = 18.sp,
                                lineHeight = 24.sp
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        // Review Body
                        Text(
                            text = review.reviewText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            color = Color(0xFFE0E0E0),
                            textAlign = TextAlign.Center
                        )

                        // Verdict Badge
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = "Verdict: ",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFFB0B3BC)
                            )
                            Text(
                                text = review.verdict,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF69F0AE)
                            )
                        }

                        // Voice indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isSpeaking) Color(0xFF00E676) else Color(0x66FFFFFF))
                            )
                            Text(
                                text = if (isSpeaking) "Speaking review out loud..." else "Voice finished",
                                fontSize = 11.sp,
                                color = if (isSpeaking) Color(0xFFB9F6CA) else Color(0x80FFFFFF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (review != null) {
                            Button(
                                onClick = {
                                    if (isSpeaking) {
                                        ttsPlayer.stop()
                                    } else {
                                        val text = "${review.headline}. ${review.reviewText}. Verdict: ${review.verdict}."
                                        ttsPlayer.speakChic(text)
                                    }
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0x33FFD700),
                                    contentColor = Color(0xFFFFDF70)
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = if (isSpeaking) "⏹️ Stop Voice" else "🔊 Replay Voice",
                                    fontSize = 13.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                ttsPlayer.stop()
                                onDismiss()
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0x33FFFFFF),
                                contentColor = Color.White
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                text = "Close",
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
