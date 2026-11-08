@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.BlurTransformation

@Composable
fun PostPlayOverlay(
    mode: PostPlayMode?,
    controlsVisible: Boolean,
    blurUnwatchedEpisodes: Boolean,
    nextEpisodeFocusRequester: FocusRequester,
    progressBarFocusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester?,
    onPlayNext: () -> Unit,
    onContinueStillWatching: () -> Unit,
    onDismissStillWatching: () -> Unit,
    onDismissAutopilot: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isAutoPlay = mode is PostPlayMode.AutoPlay
    val isAutoPlayPlayable = (mode as? PostPlayMode.AutoPlay)?.nextEpisode?.hasAired == true

    LaunchedEffect(mode, controlsVisible) {
        if (mode != null && !controlsVisible) {
            kotlinx.coroutines.delay(150)
            runCatching { nextEpisodeFocusRequester.requestFocus() }
        }
    }

    val transition = updateTransition(targetState = mode, label = "PostPlayMode")

    transition.AnimatedVisibility(
        visible = { it != null },
        enter = slideInHorizontally(animationSpec = tween(260), initialOffsetX = { it / 2 }) +
            fadeIn(animationSpec = tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(200), targetOffsetX = { it / 2 }) +
            fadeOut(animationSpec = tween(160)),
        modifier = modifier,
    ) {
        if (isAutoPlay) {
            Card(
                onClick = { if (isAutoPlayPlayable) onPlayNext() },
                shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
                colors = CardDefaults.colors(
                    containerColor = Color(0xE3191919),
                    focusedContainerColor = Color(0xE3191919),
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.16f)),
                        shape = RoundedCornerShape(14.dp),
                    ),
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = RoundedCornerShape(14.dp),
                    ),
                ),
                scale = CardDefaults.scale(focusedScale = 1f),
                modifier = Modifier
                    .width(420.dp)
                    .focusRequester(nextEpisodeFocusRequester)
                    .then(
                        if (progressBarFocusRequester != null || leftFocusRequester != null) {
                            Modifier.focusProperties {
                                progressBarFocusRequester?.let { down = it }
                                leftFocusRequester?.let { left = it }
                            }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                val autoPlayMode = mode as? PostPlayMode.AutoPlay
                if (autoPlayMode != null) {
                    AutoPlayBody(
                        mode = autoPlayMode,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .width(if (mode is PostPlayMode.Autopilot) 500.dp else 420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE3191919))
                    .border(
                        BorderStroke(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.16f)),
                        RoundedCornerShape(14.dp)
                    )
                    .focusGroup()
            ) {
                when (mode) {
                    is PostPlayMode.StillWatching -> StillWatchingBody(
                        mode = mode,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        primaryFocusRequester = nextEpisodeFocusRequester,
                        leftFocusRequester = leftFocusRequester,
                        downFocusRequester = progressBarFocusRequester,
                        onContinue = onContinueStillWatching,
                        onDismiss = onDismissStillWatching,
                    )
                    is PostPlayMode.Autopilot -> AutopilotBody(
                        mode = mode,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        primaryFocusRequester = nextEpisodeFocusRequester,
                        leftFocusRequester = leftFocusRequester,
                        downFocusRequester = progressBarFocusRequester,
                        onDismiss = onDismissAutopilot,
                        onPlay = onPlayNext,
                    )
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun AutoPlayBody(
    mode: PostPlayMode.AutoPlay,
    blurUnwatchedEpisodes: Boolean,
) {
    val nextEpisode = mode.nextEpisode
    val isPlayable = nextEpisode.hasAired
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NextEpisodeThumbnail(
            thumbnail = nextEpisode.thumbnail,
            contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
            blurred = blurUnwatchedEpisodes,
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.next_episode_label),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = nextEpisodeDisplayLabel(nextEpisode),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            val statusText = when {
                !isPlayable && !nextEpisode.unairedMessage.isNullOrBlank() -> nextEpisode.unairedMessage
                mode.searching -> stringResource(R.string.next_episode_finding_source)
                !mode.sourceName.isNullOrBlank() && mode.countdownSec != null ->
                    stringResource(R.string.next_episode_playing_via, mode.sourceName, mode.countdownSec)
                else -> null
            }
            if (statusText != null) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                NextEpisodeStatusLine(text = statusText)
            }
        }

        Row(
            modifier = Modifier
                .padding(start = NuvioTheme.spacing.sm)
                .clip(CircleShape)
                .border(
                    BorderStroke(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.2f)),
                    CircleShape,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (isPlayable) Color.White else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (isPlayable) {
                    stringResource(R.string.next_episode_play)
                } else {
                    stringResource(R.string.next_episode_unaired)
                },
                color = if (isPlayable) Color.White else Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun StillWatchingBody(
    mode: PostPlayMode.StillWatching,
    blurUnwatchedEpisodes: Boolean,
    primaryFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val exitFocusRequester = remember { FocusRequester() }

    val nextEpisode = mode.nextEpisode
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nextEpisode.thumbnail != null) {
            NextEpisodeThumbnail(
                thumbnail = nextEpisode.thumbnail,
                contentDescription = stringResource(R.string.cd_next_episode_thumbnail),
                blurred = blurUnwatchedEpisodes,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.still_watching_title),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
            Text(
                text = nextEpisodeDisplayLabel(nextEpisode),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            if (mode.countdownSec != null) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                NextEpisodeStatusLine(
                    text = stringResource(R.string.still_watching_countdown, mode.countdownSec),
                )
            }
        }
        Row(
            modifier = Modifier.padding(start = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PostPlayPillButton(
                icon = Icons.Default.PlayArrow,
                iconTint = Color.White,
                label = stringResource(R.string.still_watching_continue),
                textColor = Color.White,
                onClick = onContinue,
                focusRequester = primaryFocusRequester,
                modifier = Modifier.focusProperties {
                    right = exitFocusRequester
                    leftFocusRequester?.let { left = it }
                    downFocusRequester?.let { down = it }
                },
            )
            PostPlayPillButton(
                icon = Icons.Default.Close,
                iconTint = Color.White.copy(alpha = 0.65f),
                label = stringResource(R.string.still_watching_exit),
                textColor = Color.White.copy(alpha = 0.72f),
                onClick = onDismiss,
                focusRequester = exitFocusRequester,
                modifier = Modifier.focusProperties {
                    left = primaryFocusRequester
                    downFocusRequester?.let { down = it }
                },
            )
        }
    }
}

@Composable
private fun PostPlayPillButton(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    textColor: Color,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = CircleShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.15f),
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, Color.White.copy(alpha = 0.2f)),
                shape = CircleShape,
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.focusRequester(focusRequester),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun NextEpisodeThumbnail(
    thumbnail: String?,
    contentDescription: String?,
    blurred: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRequest = remember(context, thumbnail, blurred) {
        ImageRequest.Builder(context)
            .data(thumbnail)
            .crossfade(true)
            .apply {
                if (blurred) transformations(BlurTransformation())
            }
            .build()
    }
    Box(
        modifier = modifier
            .size(width = 112.dp, height = 64.dp)
            .clip(RoundedCornerShape(9.dp)),
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)),
                    ),
                ),
        )
    }
}

@Composable
private fun NextEpisodeStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.78f),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun nextEpisodeDisplayLabel(nextEpisode: NextEpisodeInfo): String {
    if (nextEpisode.isOtherType) return nextEpisode.title
    val context = LocalContext.current
    val code = stringResource(
        R.string.season_episode_format,
        nextEpisode.season,
        nextEpisode.episode,
    )
    val localizedTitle = nextEpisode.title.localizeEpisodeTitle(context)
    return "$code • $localizedTitle"
}


@Composable
private fun AutopilotBody(
    mode: PostPlayMode.Autopilot,
    blurUnwatchedEpisodes: Boolean,
    primaryFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit
) {
    val candidate = mode.candidate
    val isAi = mode.autopilotMode == com.nuvio.tv.core.autopilot.AutopilotMode.AI_CURATED

    // Formatted episode code, e.g. "S01E02", when both season and episode are available.
    val code: String? = if (candidate.season != null && candidate.episode != null) {
        "S%02dE%02d".format(candidate.season, candidate.episode)
    } else {
        null
    }
    val displayTitle = if (code != null) "${candidate.title} • $code" else candidate.title

    val cancelFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Top section: thumbnail + show info
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            NextEpisodeThumbnail(
                thumbnail = candidate.thumbnail ?: candidate.poster,
                contentDescription = stringResource(R.string.autopilot_badge),
                blurred = blurUnwatchedEpisodes
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Badges row: autopilot pill + mode pill.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF00B4D8).copy(alpha = 0.25f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "✈ " + stringResource(R.string.autopilot_badge).uppercase(),
                            color = Color(0xFF90E0EF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isAi) Color(0xFFFFD166).copy(alpha = 0.22f)
                                else Color.White.copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isAi) {
                                "✨ " + stringResource(R.string.autopilot_ai_badge).uppercase()
                            } else {
                                stringResource(R.string.autopilot_default_badge).uppercase()
                            },
                            color = if (isAi) Color(0xFFFFD166) else Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = displayTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )

                if (!candidate.episodeTitle.isNullOrBlank()) {
                    Text(
                        text = candidate.episodeTitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!mode.aiRationale.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "✨ " + mode.aiRationale,
                        color = Color(0xFFE0E1DD),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Status line: source search or countdown.
                val statusText = when {
                    mode.searching -> stringResource(R.string.next_episode_finding_source)
                    mode.countdownSec != null -> "Auto-playing in ${mode.countdownSec}s..."
                    else -> null
                }
                if (statusText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    NextEpisodeStatusLine(text = statusText)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom action buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PostPlayPillButton(
                icon = Icons.Default.PlayArrow,
                iconTint = Color.White,
                label = stringResource(R.string.autopilot_play_now),
                textColor = Color.White,
                onClick = onPlay,
                focusRequester = primaryFocusRequester,
                modifier = Modifier.focusProperties {
                    right = cancelFocusRequester
                    leftFocusRequester?.let { left = it }
                    downFocusRequester?.let { down = it }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            PostPlayPillButton(
                icon = Icons.Default.Close,
                iconTint = Color.White.copy(alpha = 0.65f),
                label = stringResource(R.string.action_cancel),
                textColor = Color.White.copy(alpha = 0.72f),
                onClick = onDismiss,
                focusRequester = cancelFocusRequester,
                modifier = Modifier.focusProperties {
                    left = primaryFocusRequester
                    downFocusRequester?.let { down = it }
                }
            )
        }
    }
}
