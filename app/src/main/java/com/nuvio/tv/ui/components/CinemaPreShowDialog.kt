package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.nuvio.tv.core.preshow.MovieTriviaItem

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CinemaPreShowDialog(
    visible: Boolean,
    movieTitle: String,
    trivia: List<MovieTriviaItem>,
    isLoading: Boolean,
    onStartMovie: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var currentTriviaIndex by remember { mutableStateOf(0) }
    var answerRevealed by remember { mutableStateOf(false) }

    val currentItem = trivia.getOrNull(currentTriviaIndex)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF208080C))
                .padding(36.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 760.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF14121A))
                    .border(1.5.dp, Color(0x66FFD700), RoundedCornerShape(20.dp))
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "🍿", fontSize = 22.sp)
                            Text(
                                text = "Nuvio+ Cinema Pre-Show",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color(0xFFFFD700)
                            )
                        }

                        if (trivia.isNotEmpty()) {
                            Text(
                                text = "Trivia ${currentTriviaIndex + 1} of ${trivia.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0B3BC)
                            )
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
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "🎬 Dimming lights & preparing theater pre-show for $movieTitle...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFE0E0E0),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (currentItem != null) {
                        // Question
                        Text(
                            text = currentItem.question,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                lineHeight = 24.sp
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        // Multiple choice options if present
                        if (currentItem.options.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                currentItem.options.forEach { option ->
                                    val isCorrect = answerRevealed && option.equals(currentItem.answer, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCorrect) Color(0x3300E676) else Color(0x1AFFFFFF))
                                            .border(
                                                1.dp,
                                                if (isCorrect) Color(0xFF00E676) else Color(0x33FFFFFF),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = option,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isCorrect) Color(0xFFB9F6CA) else Color(0xFFE0E0E0)
                                        )
                                    }
                                }
                            }
                        }

                        // Answer & Fun Fact
                        if (answerRevealed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x2A69F0AE))
                                    .border(1.dp, Color(0x6669F0AE), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (currentItem.answer.isNotBlank()) {
                                        Text(
                                            text = "Answer: ${currentItem.answer}",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF69F0AE),
                                            fontSize = 14.sp
                                        )
                                    }
                                    Text(
                                        text = currentItem.funFact,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!answerRevealed && currentItem != null && currentItem.answer.isNotBlank()) {
                            Button(
                                onClick = { answerRevealed = true },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0x33FFD700),
                                    contentColor = Color(0xFFFFDF70)
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("💡 Reveal Answer", fontSize = 13.sp)
                            }
                        } else if (currentTriviaIndex < trivia.size - 1) {
                            Button(
                                onClick = {
                                    currentTriviaIndex++
                                    answerRevealed = false
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0x33FFFFFF),
                                    contentColor = Color.White
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                            ) {
                                Text("Next Trivia Question", fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = onStartMovie,
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFFE50914),
                                contentColor = Color.White
                            ),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp))
                        ) {
                            Text("🎬 Start Movie Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
