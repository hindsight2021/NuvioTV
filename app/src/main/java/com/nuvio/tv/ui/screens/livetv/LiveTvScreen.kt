@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.livetv.LiveProgram
import com.nuvio.tv.core.livetv.LiveTvCategory
import com.nuvio.tv.core.livetv.LiveTvChannel
import com.nuvio.tv.core.livetv.LiveTvManager
import com.nuvio.tv.core.sound.AudioFeedbackManager
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LiveTvScreen(
    onNavigateBack: () -> Unit = {},
    onPlayChannelStream: ((LiveTvChannel) -> Unit)? = null
) {
    val context = LocalContext.current
    val allChannels by LiveTvManager.channels.collectAsState()
    var selectedCategory by remember { mutableStateOf(LiveTvCategory.ALL) }

    val filteredChannels = remember(allChannels, selectedCategory) {
        if (selectedCategory == LiveTvCategory.ALL) {
            allChannels
        } else {
            allChannels.filter { it.category == selectedCategory }
        }
    }

    var focusedChannel by remember(filteredChannels) {
        mutableStateOf(filteredChannels.firstOrNull())
    }

    BackHandler {
        onNavigateBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        // Ambient Backdrop from focused channel program
        val backdropUrl = focusedChannel?.currentProgram?.backdropUrl
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                NuvioTheme.colors.Background.copy(alpha = 0.96f),
                                NuvioTheme.colors.Background.copy(alpha = 0.88f),
                                NuvioTheme.colors.Background.copy(alpha = 0.70f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = NuvioTheme.spacing.xxl, top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.lg)
        ) {
            // Header Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
            ) {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "LIVE TV GUIDE",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0055A5).copy(alpha = 0.25f))
                        .border(1.dp, Color(0xFF0055A5), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "BELL FIBE TV TUNER",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF4DA6FF)
                    )
                }
            }

            // Category Filter Pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.md)
            ) {
                items(LiveTvCategory.entries) { category ->
                    val isSelected = category == selectedCategory
                    Card(
                        onClick = {
                            AudioFeedbackManager.playClick(context)
                            selectedCategory = category
                        },
                        colors = CardDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.6f),
                            focusedContainerColor = NuvioTheme.colors.Primary.copy(alpha = 0.85f)
                        ),
                        shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = category.displayName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) Color.White else NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }
            }

            // Main 2-Pane Content: Channels List (Left) & Detailed Preview (Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = NuvioTheme.spacing.xs)
            ) {
                // Left Column: Channel EPG List
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    modifier = Modifier
                        .width(460.dp)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl)
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        val isCurrentFocused = focusedChannel?.id == channel.id
                        val prog = channel.currentProgram
                        val channelColor = remember(channel.accentColorHex) {
                            try {
                                Color(android.graphics.Color.parseColor(channel.accentColorHex))
                            } catch (_: Exception) {
                                Color(0xFF0055A5)
                            }
                        }

                        Card(
                            onClick = {
                                AudioFeedbackManager.playClick(context)
                                if (!channel.streamUrl.isNullOrBlank() && onPlayChannelStream != null) {
                                    onPlayChannelStream(channel)
                                } else {
                                    LiveTvManager.tuneToChannel(context, channel)
                                }
                            },
                            colors = CardDefaults.colors(
                                containerColor = if (isCurrentFocused) NuvioTheme.colors.Surface.copy(alpha = 0.9f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.45f),
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = androidx.compose.foundation.BorderStroke(2.dp, NuvioTheme.colors.Primary),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    if (state.hasFocus) {
                                        focusedChannel = channel
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(NuvioTheme.spacing.md)
                            ) {
                                // Channel Badge
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(54.dp, 40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(channelColor)
                                ) {
                                    Text(
                                        text = channel.logoText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                }

                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "${channel.number}  ${channel.name}",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = NuvioTheme.colors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${prog.remainingMinutes}m left",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuvioTheme.colors.TextTertiary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = prog.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioTheme.colors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Progress Bar
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(prog.progressFraction)
                                                .fillMaxHeight()
                                                .background(channelColor)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xl))

                // Right Column: Focused Channel Detail View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.lg)
                ) {
                    focusedChannel?.let { channel ->
                        val prog = channel.currentProgram
                        Column(
                            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Channel metadata row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFE50914))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "LIVE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }

                                Text(
                                    text = "Channel ${channel.number} • ${channel.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NuvioTheme.colors.TextSecondary
                                )

                                prog.genre?.let { genre ->
                                    Text(text = "•", color = NuvioTheme.colors.TextTertiary)
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NuvioTheme.colors.Primary
                                    )
                                }
                            }

                            // Program Title
                            Text(
                                text = prog.title,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 32.sp
                                ),
                                color = NuvioTheme.colors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Episode Title
                            prog.episodeTitle?.let { epTitle ->
                                Text(
                                    text = epTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = NuvioTheme.colors.TextSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                            // Synopsis
                            Text(
                                text = prog.description ?: "No description available for this broadcast.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = NuvioTheme.colors.TextSecondary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

                            // Action Button: Tune to Bell Fibe
                            // Action Button: Watch Live or Tune to Bell Fibe
                            Button(
                                onClick = {
                                    AudioFeedbackManager.playClick(context)
                                    if (!channel.streamUrl.isNullOrBlank() && onPlayChannelStream != null) {
                                        onPlayChannelStream(channel)
                                    } else {
                                        LiveTvManager.tuneToChannel(context, channel)
                                    }
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.Primary,
                                    focusedContainerColor = Color.White
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black
                                    )
                                    Text(
                                        text = if (!channel.streamUrl.isNullOrBlank()) "Watch Live in Nuvio" else "Tune Channel ${channel.number} on Bell Fibe",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.Black
                                    )
                                }
                            }

                            // Next Program Preview Card
                            channel.nextProgram?.let { nextProg ->
                                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f))
                                        .padding(NuvioTheme.spacing.md)
                                ) {
                                    Column {
                                        Text(
                                            text = "UP NEXT",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = NuvioTheme.colors.TextTertiary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = nextProg.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = NuvioTheme.colors.TextPrimary
                                        )
                                        nextProg.description?.let { d ->
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = d,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NuvioTheme.colors.TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
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
}
