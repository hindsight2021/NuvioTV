package com.nuvio.tv.ambient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ambient Quick Actions overlay for the Nuvio TV Ambient Screensaver.
 *
 * Displays a frosted, bottom-centered floating pill containing a horizontal row of
 * TV-remote-friendly action buttons.
 */
@Composable
fun AmbientQuickActions(
    isOpen: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenChannels: () -> Unit,
    onDismiss: () -> Unit,
    onExitAmbient: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isOpen) { onDismiss() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 72.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                scaleIn(
                    initialScale = 0.85f,
                    animationSpec = tween(durationMillis = 220)
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)) +
                scaleOut(
                    targetScale = 0.85f,
                    animationSpec = tween(durationMillis = 180)
                )
        ) {
            QuickActionsPill(
                onLike = onLike,
                onDislike = onDislike,
                onSkipNext = onSkipNext,
                onOpenChannels = onOpenChannels,
                onExitAmbient = onExitAmbient
            )
        }
    }
}

@Composable
private fun QuickActionsPill(
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenChannels: () -> Unit,
    onExitAmbient: () -> Unit
) {
    val defaultFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { defaultFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE61A1A1A),
                        Color(0xE60D0D0D)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color(0x33FFFFFF),
                shape = RoundedCornerShape(percent = 50)
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickActionButton(
            icon = Icons.Default.ThumbUp,
            label = "Like",
            onClick = onLike
        )
        QuickActionButton(
            icon = Icons.Default.ThumbDown,
            label = "Dislike",
            onClick = onDislike
        )
        QuickActionButton(
            icon = Icons.Default.SkipNext,
            label = "Skip",
            onClick = onSkipNext
        )
        QuickActionButton(
            icon = Icons.Default.GridOn,
            label = "Channels",
            onClick = onOpenChannels,
            focusRequester = defaultFocusRequester
        )
        QuickActionButton(
            icon = Icons.Default.Close,
            label = "Exit",
            onClick = onExitAmbient
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val iconTint = if (isFocused) Color.White else Color(0xCCFFFFFF)
    val backgroundColor = if (isFocused) Color(0x33FFFFFF) else Color(0x1AFFFFFF)
    val borderColor = if (isFocused) Color.White else Color(0x33FFFFFF)

    Column(
        modifier = Modifier
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (isFocused) 60.dp else 56.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            color = if (isFocused) Color.White else Color(0x99FFFFFF),
            fontSize = 11.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
