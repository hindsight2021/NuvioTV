@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.music.EclipseAudioTelemetry
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Compact focusable Now Playing bar overlaid in the bottom-right corner during browse screens.
 * Includes quick Play/Pause, Next, and Close (Stop) controls.
 */
@Composable
fun MiniNowPlayingBar(
    telemetry: EclipseAudioTelemetry,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = telemetry.currentTrack ?: return
    val colors = NuvioTheme.colors

    Row(
        modifier = modifier
            .widthIn(min = 320.dp, max = 460.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.BackgroundElevated.copy(alpha = 0.96f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Card(
            onClick = onOpenNowPlaying,
            colors = CardDefaults.colors(
                containerColor = colors.BackgroundCard,
                focusedContainerColor = colors.FocusBackground,
            ),
            border = CardDefaults.border(
                border = Border(BorderStroke(1.dp, colors.Border)),
                focusedBorder = Border(BorderStroke(2.dp, colors.BorderFocused)),
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    val artUrl = track.displayArtUrl()
                    if (artUrl.isNotBlank()) {
                        AsyncImage(
                            model = artUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = colors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (telemetry.djSessionId != null) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Umbra DJ",
                                tint = colors.Primary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        IconButton(
            onClick = onTogglePlayPause,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
                focusedContentColor = colors.TextPrimary,
            ),
        ) {
            Icon(
                imageVector = if (telemetry.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (telemetry.isPlaying) "Pause" else "Play",
            )
        }

        IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
                focusedContentColor = colors.TextPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next track",
            )
        }

        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextSecondary,
                focusedContainerColor = colors.FocusBackground,
                focusedContentColor = colors.TextPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close mini player",
            )
        }
    }
}
