package com.nuvio.tv.ambient.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import com.nuvio.tv.R
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ambient.playback.AmbientPlayerPool
import com.nuvio.tv.ambient.playback.PlayerSlot

/**
 * Full-screen ambient screensaver surface.
 *
 * Renders two cross-fading [PlayerView] layers (slot A / slot B) driven by
 * [AmbientPlayerPool], a dimming overlay, and ambient UI overlays.
 */
@OptIn(UnstableApi::class)
@Composable
fun AmbientScreen(
    playerPool: AmbientPlayerPool,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AmbientViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val weather by viewModel.weatherState.collectAsState()
    val burnInX by viewModel.burnInOffsetDpX.collectAsState()
    val burnInY by viewModel.burnInOffsetDpY.collectAsState()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.isAmbientActive) {
        if (uiState.isAmbientActive) {
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = true) {
        when {
            uiState.isChannelPickerOpen -> viewModel.closeChannelPicker()
            uiState.isQuickActionsOpen -> viewModel.closeQuickActions()
            else -> {
                viewModel.stopAmbient()
                onDismiss()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                if (uiState.isChannelPickerOpen || uiState.isQuickActionsOpen) {
                    return@onKeyEvent false
                }

                when (event.key) {
                    Key.DirectionUp -> {
                        viewModel.toggleChannelPicker()
                        true
                    }

                    Key.DirectionDown -> {
                        viewModel.showOverlayTemporarily()
                        true
                    }

                    Key.DirectionLeft -> {
                        viewModel.previous()
                        true
                    }

                    Key.DirectionRight -> {
                        viewModel.skipNext()
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        viewModel.toggleQuickActions()
                        true
                    }

                    Key.Back, Key.Escape -> {
                        viewModel.stopAmbient()
                        onDismiss()
                        true
                    }

                    else -> {
                        viewModel.showOverlayTemporarily()
                        false
                    }
                }
            }
    ) {
        // --- Video layers ---
        AmbientPlayerLayer(
            playerPool = playerPool,
            slot = PlayerSlot.SLOT_A,
            alpha = uiState.playerAAlpha
        )

        AmbientPlayerLayer(
            playerPool = playerPool,
            slot = PlayerSlot.SLOT_B,
            alpha = uiState.playerBAlpha
        )

        // --- Dimming overlay ---
        if (uiState.dimPercent > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (uiState.dimPercent / 100f).coerceIn(0f, 1f)))
            )
        }

        // --- UI overlays ---
        AmbientOverlay(
            candidate = uiState.currentCandidate,
            settings = settings,
            weather = weather,
            isVisible = uiState.isOverlayVisible,
            offsetX = burnInX,
            offsetY = burnInY
        )

        AmbientChannelPicker(
            isOpen = uiState.isChannelPickerOpen,
            currentChannel = uiState.currentChannel,
            onChannelSelected = { channel ->
                viewModel.selectChannel(channel)
                viewModel.closeChannelPicker()
            },
            onDismiss = { viewModel.closeChannelPicker() }
        )

        AmbientQuickActions(
            isOpen = uiState.isQuickActionsOpen,
            onLike = { viewModel.likeCurrent() },
            onDislike = { viewModel.dislikeCurrent() },
            onSkipNext = { viewModel.skipNext() },
            onOpenChannels = {
                viewModel.closeQuickActions()
                viewModel.toggleChannelPicker()
            },
            onDismiss = { viewModel.closeQuickActions() },
            onExitAmbient = {
                viewModel.stopAmbient()
                onDismiss()
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopAmbient()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun AmbientPlayerLayer(
    playerPool: AmbientPlayerPool,
    slot: PlayerSlot,
    alpha: Float
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) },
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.ambient_player_view, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                player = playerPool.getPlayer(slot)
            }
        },
        update = { view ->
            val currentPlayer = playerPool.getPlayer(slot)
            if (view.player !== currentPlayer) {
                view.player = currentPlayer
            }
        },
        onRelease = { view ->
            view.player = null
        }
    )
}
