@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.ai.AiResponse
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun AiConversationalCard(
    isThinking: Boolean,
    response: AiResponse?,
    error: String?,
    providerName: String,
    onQuestionClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)

    // Vibrant AI border gradient
    val borderGradient = Brush.horizontalGradient(
        listOf(
            Color(0xFF7C4DFF),
            Color(0xFF00E5FF),
            Color(0xFFFF4081)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 52.dp, vertical = 8.dp)
            .clip(cardShape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0xFF1A1A2E).copy(alpha = 0.95f),
                        Color(0xFF16213E).copy(alpha = 0.95f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = borderGradient,
                shape = cardShape
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header: AI Provider Badge & Dismiss button
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
                        text = "\u2728",
                        fontSize = 20.sp
                    )
                    Text(
                        text = "AI Assistant \u00b7 $providerName",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = Color.White.copy(alpha = 0.25f),
                        focusedContentColor = Color.White
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body: Thinking, Error, or Spoken Response
            when {
                isThinking -> {
                    AiThinkingIndicator()
                }

                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF5252),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                response != null -> {
                    Text(
                        text = response.spokenResponse,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Suggested Follow-up Questions
                    if (response.suggestedQuestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Suggestions:",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextTertiary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(response.suggestedQuestions) { question ->
                                SuggestionChipButton(
                                    question = question,
                                    onClick = { onQuestionClick(question) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChipButton(
    question: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            ),
        colors = ButtonDefaults.colors(
            containerColor = if (isFocused) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (isFocused) Color.White else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.35f),
            focusedContentColor = Color.White
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = "\uD83D\uDCAC $question",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun AiThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "aiThinking")
    val alpha1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha1"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha2"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Thinking and personalizing recommendations",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF00E5FF)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha1)
                .background(Color(0xFF00E5FF), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha2)
                .background(Color(0xFF7C4DFF), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha3)
                .background(Color(0xFFFF4081), CircleShape)
        )
    }
}
