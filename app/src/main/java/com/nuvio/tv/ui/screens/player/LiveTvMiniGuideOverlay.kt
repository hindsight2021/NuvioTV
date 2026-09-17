package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.livetv.LiveTvCategory
import com.nuvio.tv.core.livetv.LiveTvChannel
import com.nuvio.tv.core.livetv.LiveTvManager
import com.nuvio.tv.core.sound.AudioFeedbackManager
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvMiniGuideOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelectChannel: (LiveTvChannel) -> Unit,
    currentChannelId: String? = null,
    modifier: Modifier = Modifier
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

    val firstItemFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(visible) {
        if (visible) {
            val targetIdx = filteredChannels.indexOfFirst { it.id == currentChannelId }
            if (targetIdx >= 0) {
                listState.scrollToItem((targetIdx - 1).coerceAtLeast(0))
            }
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        captureKeys = false,
        dismissOnBackgroundClick = true
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            val shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)

            Box(
                modifier = modifier
                    .fillMaxHeight()
                    .width(440.dp)
                    .clip(shape)
                    .background(Color(0xF012141A))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp, bottom = 16.dp)
                ) {
                    // Header: title + Live badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live TV Guide",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Category Filter Pills
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(LiveTvCategory.entries, key = { it.name }) { category ->
                            val isSelected = category == selectedCategory
                            Card(
                                onClick = {
                                    AudioFeedbackManager.playClick(context)
                                    selectedCategory = category
                                },
                                colors = CardDefaults.colors(
                                    containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.35f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.4f),
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        border = androidx.compose.foundation.BorderStroke(1.5.dp, NuvioTheme.colors.Primary),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                ),
                                shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = category.displayName,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Channels List
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredChannels, key = { it.id }) { channel ->
                            val isFirst = filteredChannels.firstOrNull()?.id == channel.id
                            val isWatching = channel.id == currentChannelId
                            val prog = channel.currentProgram
                            val channelColor = remember(channel.accentColorHex) {
                                runCatching { Color(android.graphics.Color.parseColor(channel.accentColorHex)) }
                                    .getOrDefault(Color(0xFF0055A5))
                            }

                            Card(
                                onClick = {
                                    AudioFeedbackManager.playClick(context)
                                    onSelectChannel(channel)
                                },
                                colors = CardDefaults.colors(
                                    containerColor = if (isWatching) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.35f),
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
                                    .then(if (isFirst) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                            if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                                onDismiss()
                                                true
                                            } else false
                                        } else false
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                ) {
                                    // Channel badge
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(width = 46.dp, height = 36.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(channelColor)
                                    ) {
                                        Text(
                                            text = channel.logoText,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp
                                            ),
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Channel info + program
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "${channel.number} • ${channel.name}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = NuvioTheme.colors.TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isWatching) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(NuvioTheme.colors.Primary.copy(alpha = 0.25f))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "WATCHING",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 9.sp
                                                        ),
                                                        color = NuvioTheme.colors.Primary
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text(
                                            text = prog.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = NuvioTheme.colors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Progress Bar
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
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
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${prog.remainingMinutes}m left",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = NuvioTheme.colors.TextTertiary
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