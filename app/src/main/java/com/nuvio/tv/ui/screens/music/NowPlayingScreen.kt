@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.music.EclipseAudioTelemetry
import com.nuvio.tv.core.music.EclipseRepeatMode
import com.nuvio.tv.core.music.LrcLine
import com.nuvio.tv.core.music.parseLrcLyrics
import com.nuvio.tv.ui.theme.NuvioTheme

private enum class NowPlayingRightTab {
    LYRICS,
    QUEUE,
}

/**
 * Full-screen 10-foot TV Now Playing experience for Eclipse Music and Connect speaker playback.
 */
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenDjMixer: () -> Unit = {},
    viewModel: EclipseMusicViewModel = hiltViewModel(),
) {
    BackHandler { onBack() }

    val telemetry by viewModel.audioTelemetry.collectAsStateWithLifecycle()
    val djTelemetry by viewModel.djTelemetry.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = NuvioTheme.colors
    val playPauseFocusRequester = remember { FocusRequester() }
    var rightTab by remember { mutableStateOf(NowPlayingRightTab.LYRICS) }

    val track = telemetry.currentTrack
    val lrcLines = remember(track?.lyricsLrc) { parseLrcLyrics(track?.lyricsLrc) }

    LaunchedEffect(Unit) {
        runCatching { playPauseFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background),
    ) {
        val backdropUrl = track?.backdropUrl?.takeIf { it.isNotBlank() } ?: track?.displayArtUrl().orEmpty()
        if (backdropUrl.isNotBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.24f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.Background.copy(alpha = 0.72f),
                            colors.Background.copy(alpha = 0.94f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.colors(
                            containerColor = colors.BackgroundCard,
                            contentColor = colors.TextPrimary,
                            focusedContainerColor = colors.FocusBackground,
                        ),
                    ) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = if (telemetry.djSessionId != null) "Umbra AI DJ • On Air" else stringResource(R.string.music_now_playing),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.TextSecondary,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!uiState.error.isNullOrBlank()) {
                        Text(
                            text = uiState.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.Error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(340.dp),
                        )
                    } else if (!uiState.djBannerMessage.isNullOrBlank()) {
                        Text(
                            text = uiState.djBannerMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.Primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(340.dp),
                        )
                    }
                    Button(
                        enabled = !uiState.isDjBusy,
                        onClick = {
                            if (djTelemetry.isActive) {
                                onOpenDjMixer()
                            } else {
                                viewModel.startProDjSet(
                                    tracks = telemetry.queue.ifEmpty { null },
                                    includeSurprises = true,
                                    onStarted = onOpenDjMixer,
                                )
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = colors.BackgroundCard,
                            contentColor = colors.Primary,
                            focusedContainerColor = colors.Primary,
                            focusedContentColor = colors.OnPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                uiState.isDjBusy -> "Curating…"
                                djTelemetry.isActive -> "DJ Booth"
                                else -> "Pro AI DJ Mix"
                            },
                        )
                    }
                }
            }

            if (track == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Nothing playing right now. Pick a track, mix, or start Umbra DJ.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.TextSecondary,
                    )
                }
                return@Column
            }

            // Main 2-column TV stage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                // Left column: artwork, metadata, progress, transport controls
                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.BackgroundCard),
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
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (track.album.isNotBlank()) {
                                Text(
                                    text = track.album,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            val badges = buildList {
                                track.enrichment?.quality?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                                track.enrichment?.year?.let { add(it.toString()) }
                                track.enrichment?.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                                track.provider?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                            }
                            if (badges.isNotEmpty()) {
                                Text(
                                    text = badges.joinToString("  •  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    // Progress bar & timestamps
                    val durationMs = telemetry.durationMs.coerceAtLeast(
                        (track.durationSec * 1_000L).coerceAtLeast(1L),
                    )
                    val fraction = (telemetry.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.Surface),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(colors.Primary),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatAudioTime(telemetry.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.TextSecondary,
                        )
                        Text(
                            text = formatAudioTime(durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.TextSecondary,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // Transport controls
                    NowPlayingControlsRow(
                        telemetry = telemetry,
                        playPauseFocusRequester = playPauseFocusRequester,
                        onPrevious = { viewModel.control("previous") },
                        onRewind10 = { viewModel.control("seek", (telemetry.positionMs - 10_000L).coerceAtLeast(0L)) },
                        onPlayPause = { viewModel.control("toggle_play_pause") },
                        onForward10 = { viewModel.control("seek", (telemetry.positionMs + 10_000L).coerceAtMost(durationMs)) },
                        onNext = { viewModel.control("next") },
                        onToggleShuffle = { viewModel.control("shuffle", !telemetry.shuffle) },
                        onCycleRepeat = {
                            val nextRepeat = when (EclipseRepeatMode.fromWire(telemetry.repeat)) {
                                EclipseRepeatMode.OFF -> "all"
                                EclipseRepeatMode.ALL -> "one"
                                EclipseRepeatMode.ONE -> "off"
                            }
                            viewModel.control("repeat", nextRepeat)
                        },
                    )

                    if (telemetry.djSessionId != null) {
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.nextUmbraDj() },
                                enabled = !uiState.isDjBusy,
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.BackgroundCard,
                                    contentColor = colors.TextPrimary,
                                    focusedContainerColor = colors.FocusBackground,
                                ),
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (uiState.isDjBusy) "Loading DJ…" else "DJ Next Segment")
                            }
                            Button(
                                onClick = { viewModel.stopUmbraDj() },
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.BackgroundCard,
                                    contentColor = colors.TextSecondary,
                                    focusedContainerColor = colors.FocusBackground,
                                ),
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stop DJ")
                            }
                        }
                    }

                    if (!telemetry.error.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = telemetry.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.Error,
                        )
                    }
                }

                // Right column: Synced Lyrics / Up Next Queue
                Column(
                    modifier = Modifier
                        .weight(0.85f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.BackgroundCard.copy(alpha = 0.85f))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { rightTab = NowPlayingRightTab.LYRICS },
                            colors = ButtonDefaults.colors(
                                containerColor = if (rightTab == NowPlayingRightTab.LYRICS) colors.FocusBackground else colors.Surface,
                                contentColor = colors.TextPrimary,
                            ),
                        ) {
                            Text(stringResource(R.string.music_lyrics))
                        }
                        Button(
                            onClick = { rightTab = NowPlayingRightTab.QUEUE },
                            colors = ButtonDefaults.colors(
                                containerColor = if (rightTab == NowPlayingRightTab.QUEUE) colors.FocusBackground else colors.Surface,
                                contentColor = colors.TextPrimary,
                            ),
                        ) {
                            Text("${stringResource(R.string.music_up_next)} (${telemetry.queue.size})")
                        }
                    }

                    if (rightTab == NowPlayingRightTab.LYRICS) {
                        SyncedLyricsPanel(
                            lines = lrcLines,
                            positionMs = telemetry.positionMs,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        UpNextQueuePanel(
                            telemetry = telemetry,
                            onJumpTo = { idx -> viewModel.jumpTo(idx) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingControlsRow(
    telemetry: EclipseAudioTelemetry,
    playPauseFocusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onRewind10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val colors = NuvioTheme.colors
    val repeatMode = EclipseRepeatMode.fromWire(telemetry.repeat)

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleShuffle,
            colors = IconButtonDefaults.colors(
                containerColor = if (telemetry.shuffle) colors.FocusBackground else colors.BackgroundCard,
                contentColor = if (telemetry.shuffle) colors.Primary else colors.TextSecondary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
        }

        IconButton(
            onClick = onPrevious,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
        }

        IconButton(
            onClick = onRewind10,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(Icons.Default.FastRewind, contentDescription = "Rewind 10 seconds")
        }

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(56.dp)
                .focusRequester(playPauseFocusRequester),
            colors = IconButtonDefaults.colors(
                containerColor = colors.Primary,
                contentColor = colors.OnPrimary,
                focusedContainerColor = colors.FocusBackground,
                focusedContentColor = colors.TextPrimary,
            ),
        ) {
            Icon(
                imageVector = if (telemetry.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (telemetry.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(30.dp),
            )
        }

        IconButton(
            onClick = onForward10,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(Icons.Default.FastForward, contentDescription = "Forward 10 seconds")
        }

        IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.colors(
                containerColor = colors.BackgroundCard,
                contentColor = colors.TextPrimary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next")
        }

        IconButton(
            onClick = onCycleRepeat,
            colors = IconButtonDefaults.colors(
                containerColor = if (repeatMode != EclipseRepeatMode.OFF) colors.FocusBackground else colors.BackgroundCard,
                contentColor = if (repeatMode != EclipseRepeatMode.OFF) colors.Primary else colors.TextSecondary,
                focusedContainerColor = colors.FocusBackground,
            ),
        ) {
            Icon(
                imageVector = if (repeatMode == EclipseRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = "Repeat: ${repeatMode.wireValue}",
            )
        }
    }
}

@Composable
private fun SyncedLyricsPanel(
    lines: List<LrcLine>,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = NuvioTheme.colors
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "Lyrics unavailable for this track.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.TextSecondary,
            )
        }
        return
    }

    val activeIndex = remember(lines, positionMs) {
        if (lines.firstOrNull()?.timestampMs == -1L) {
            -1
        } else {
            lines.indexOfLast { it.timestampMs in 0..positionMs }
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            val target = (activeIndex - 3).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(lines, key = { idx, line -> "${line.timestampMs}_$idx" }) { index, line ->
            val isActive = index == activeIndex
            Text(
                text = line.text,
                style = if (isActive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) colors.TextPrimary else colors.TextSecondary.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun UpNextQueuePanel(
    telemetry: EclipseAudioTelemetry,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NuvioTheme.colors
    if (telemetry.queue.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = "Queue is empty.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.TextSecondary,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(telemetry.queue, key = { idx, t -> "${t.id}_$idx" }) { index, item ->
            val isCurrent = index == telemetry.currentIndex
            Card(
                onClick = { onJumpTo(index) },
                colors = CardDefaults.colors(
                    containerColor = if (isCurrent) colors.FocusBackground else colors.Surface,
                    focusedContainerColor = colors.FocusBackground,
                ),
                border = CardDefaults.border(
                    border = if (isCurrent) Border(BorderStroke(1.dp, colors.Primary)) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, colors.BorderFocused)),
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.BackgroundCard),
                        contentAlignment = Alignment.Center,
                    ) {
                        val art = item.displayArtUrl()
                        if (art.isNotBlank()) {
                            AsyncImage(
                                model = art,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = colors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${index + 1}. ${item.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isCurrent) {
                        Text(
                            text = "Playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.Primary,
                        )
                    }
                }
            }
        }
    }
}
