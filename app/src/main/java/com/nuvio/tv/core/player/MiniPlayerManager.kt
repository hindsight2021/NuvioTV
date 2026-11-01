package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MiniPlayerManager is currently disabled per user preference.
 */
object MiniPlayerManager {
    private val _isMiniPlayerActive = MutableStateFlow(false)
    val isMiniPlayerActive: StateFlow<Boolean> = _isMiniPlayerActive.asStateFlow()

    var activeController: Any? = null
    var activePlayerViewModel: Any? = null
    var fullPlayerRoute: String = ""

    fun enterMiniPlayer(
        controller: Any? = null,
        title: String = "",
        route: String = "",
        viewModel: Any? = null
    ) {
        // Disabled
    }

    fun exitMiniPlayer(stopPlayback: Boolean = true) {
        _isMiniPlayerActive.value = false
        activeController = null
        activePlayerViewModel = null
    }

    fun restoreFullScreen(onNavigate: (String) -> Unit) {
        // Disabled
    }

    fun stopAndDismiss() {
        exitMiniPlayer(stopPlayback = true)
    }

    fun isPlaybackRunning(): Boolean = false
}
