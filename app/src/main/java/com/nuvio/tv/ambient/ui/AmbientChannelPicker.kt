package com.nuvio.tv.ambient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ambient.AmbientChannel

/**
 * Bottom modal overlay that lets the user pick an [AmbientChannel] for the idle screensaver.
 *
 * Designed for TV / D-pad navigation: the currently selected channel receives focus when the
 * picker opens, and every card is focusable and clickable.
 */
@Composable
fun AmbientChannelPicker(
    isOpen: Boolean,
    currentChannel: AmbientChannel,
    onChannelSelected: (AmbientChannel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isOpen) { onDismiss() }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(durationMillis = 300)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 250)
        ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            ChannelPickerSheet(
                currentChannel = currentChannel,
                onChannelSelected = onChannelSelected
            )
        }
    }
}

@Composable
private fun ChannelPickerSheet(
    currentChannel: AmbientChannel,
    onChannelSelected: (AmbientChannel) -> Unit
) {
    val channels = remember { AmbientChannel.entries }
    val listState = rememberLazyListState()
    val selectedFocusRequester = remember { FocusRequester() }

    LaunchedEffect(currentChannel) {
        val selectedIndex = channels.indexOf(currentChannel).coerceAtLeast(0)
        listState.scrollToItem(selectedIndex)
        runCatching { selectedFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { /* no-op */ }
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0E0E0E).copy(alpha = 0.98f),
                        Color(0xFF000000)
                    )
                ),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(vertical = 24.dp)
    ) {
        Text(
            text = "Ambient Channels",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Text(
            text = "Select a mood or theme for idle visuals",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
        )

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(channels, key = { it.name }) { channel ->
                val isSelected = channel == currentChannel
                ChannelCard(
                    channel = channel,
                    isSelected = isSelected,
                    focusRequester = if (isSelected) selectedFocusRequester else null,
                    onClick = { onChannelSelected(channel) }
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: AmbientChannel,
    isSelected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isFocused -> Color.White
        isSelected -> Color(0xFFE50914)
        else -> Color.White.copy(alpha = 0.15f)
    }
    val borderWidth = if (isFocused) 2.dp else 1.dp

    val backgroundColor = when {
        isSelected -> Color(0xFFE50914).copy(alpha = 0.2f)
        isFocused -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.04f)
    }

    Box(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = channel.displayName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start
            )
            Text(
                text = channel.description,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                textAlign = TextAlign.Start
            )
        }
    }
}
