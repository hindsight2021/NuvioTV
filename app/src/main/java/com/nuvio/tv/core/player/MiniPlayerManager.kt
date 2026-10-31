package com.nuvio.tv.core.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton manager for the in-app mini-player (PiP / hero banner) state.
 *
 * When the user presses Back once during full-screen video playback, the active
 * [PlayerRuntimeController] and [PlayerViewModel] are preserved in this manager so playback continues
 * in a compact mini-player in the top-right corner / hero banner location while
 * the user browses the app.
 *
 * Pressing Back again while on the main menu restores full-screen playback.
 * A long press on Back stops playback completely.
 */
object MiniPlayerManager {

    private val _isMiniPlayerActive = MutableStateFlow(false)

    /** Whether the mini-player is currently active. */
    val isMiniPlayerActive: StateFlow<Boolean> = _isMiniPlayerActive.asStateFlow()

    private val _activeController = MutableStateFlow<PlayerRuntimeController?>(null)

    /** The controller currently driving playback. */
    val activeController: StateFlow<PlayerRuntimeController?> = _activeController.asStateFlow()

    private val _miniPlayerTitle = MutableStateFlow("")

    /** Title displayed alongside the mini-player. */
    val miniPlayerTitle: StateFlow<String> = _miniPlayerTitle.asStateFlow()

    /**
     * Retained ViewModel reference during mini-player browsing so resources are not GC'd or cleared.
     */
    @Volatile
    var activePlayerViewModel: PlayerViewModel? = null

    /**
     * Route used to restore full-screen playback when expanding the mini-player.
     */
    @Volatile
    var fullPlayerRoute: String? = null

    /** Active ExoPlayer instance, if available. */
    val activeExoPlayer: ExoPlayer?
        get() = _activeController.value?.exoPlayer

    /** Guards mutations. */
    private val lock = Any()

    /**
     * Enters mini-player mode with the given [controller].
     */
    fun enterMiniPlayer(
        controller: PlayerRuntimeController,
        title: String,
        route: String?,
        viewModel: PlayerViewModel? = null
    ) {
        synchronized(lock) {
            fullPlayerRoute = route
            activePlayerViewModel = viewModel
            _miniPlayerTitle.value = title.ifBlank { controller.uiState.value.title }
            _activeController.value = controller
            _isMiniPlayerActive.value = true
        }
    }

    /**
     * Exits mini-player mode.
     *
     * @param stopPlayback when true, stops and releases the controller and clears all state.
     */
    fun exitMiniPlayer(stopPlayback: Boolean = false) {
        val controller: PlayerRuntimeController?
        val vm: PlayerViewModel?
        synchronized(lock) {
            controller = _activeController.value
            vm = activePlayerViewModel
            _isMiniPlayerActive.value = false
            if (stopPlayback) {
                _activeController.value = null
                activePlayerViewModel = null
                _miniPlayerTitle.value = ""
                fullPlayerRoute = null
            }
        }
        if (stopPlayback) {
            vm?.stopAndRelease()
            controller?.stopAndRelease()
        }
    }

    /**
     * Restores full-screen playback by executing [onNavigate] with [fullPlayerRoute].
     */
    fun restoreFullScreen(onNavigate: (String) -> Unit): Boolean {
        val route = fullPlayerRoute
        if (!route.isNullOrBlank()) {
            _isMiniPlayerActive.value = false
            onNavigate(route)
            return true
        }
        return false
    }

    /** Stops playback and dismisses the mini-player. Alias for [exitMiniPlayer] with stop. */
    fun stopAndDismiss() {
        exitMiniPlayer(stopPlayback = true)
    }

    /** Returns true if there is an active video playback session in either full-screen or mini-mode. */
    fun isPlaybackRunning(): Boolean {
        return _activeController.value?.exoPlayer != null
    }
}

