package com.nuvio.tv.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.MiniPlayerManager
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Overlay composable that renders the in-app mini-player when
 * [MiniPlayerManager.isMiniPlayerActive] is `true`.
 *
 * Positioned in the top right corner / hero banner area so the user can browse
 * content while video playback continues uninterrupted.
 *
 * Pressing Back while on the main menu, or clicking this overlay, expands the
 * video back to full screen.
 */
@Composable
fun MiniPlayerOverlay(
    onExpandFullScreen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive by MiniPlayerManager.isMiniPlayerActive.collectAsState()
    val controller by MiniPlayerManager.activeController.collectAsState()
    val title by MiniPlayerManager.miniPlayerTitle.collectAsState()

    val activeController = controller
    if (!isActive || activeController == null) return

    val exoPlayer = activeController.exoPlayer ?: return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(animationSpec = tween(250)) +
                scaleIn(animationSpec = tween(250), initialScale = 0.90f),
            exit = fadeOut(animationSpec = tween(200)) +
                scaleOut(animationSpec = tween(200), targetScale = 0.90f)
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 28.dp, end = 36.dp)
                    .width(384.dp)
                    .height(216.dp)
                    .shadow(16.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(
                        BorderStroke(
                            width = 2.dp,
                            color = NuvioTheme.colors.Primary.copy(alpha = 0.85f)
                        ),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        MiniPlayerManager.restoreFullScreen(onExpandFullScreen)
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            player = exoPlayer
                        }
                    },
                    update = { playerView ->
                        if (playerView.player !== exoPlayer) {
                            playerView.player = exoPlayer
                        }
                    }
                )

                // Scrim at bottom with title and hint
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.88f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = title.ifBlank { "Playing" },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Press Back on Main Menu to expand",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
