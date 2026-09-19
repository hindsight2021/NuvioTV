package com.nuvio.tv.ambient.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.ambient.AmbientCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Convenience extension to get the opposite player slot.
 */
fun PlayerSlot.other(): PlayerSlot = if (this == PlayerSlot.SLOT_A) PlayerSlot.SLOT_B else PlayerSlot.SLOT_A

/**
 * Current visual and playback state of the ambient playback system.
 */
data class AmbientPlaybackState(
    val activeSlot: PlayerSlot = PlayerSlot.SLOT_A,
    val currentCandidate: AmbientCandidate? = null,
    val nextCandidate: AmbientCandidate? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isTransitioning: Boolean = false,
    val slotAAlpha: Float = 1f,
    val slotBAlpha: Float = 0f,
    val error: String? = null
)

/**
 * High-level coordinator for ambient visual playback.
 * Enforces zero audio at all times and drives dual-player seamless preloading and transitions.
 */
@OptIn(UnstableApi::class)
@Singleton
class AmbientPlaybackController @Inject constructor(
    val playerPool: AmbientPlayerPool,
    val preloader: AmbientPreloader,
    val transitionController: AmbientTransitionController
) {

    companion object {
        private const val TAG = "AmbientPlaybackCtrl"
        private const val BUFFERING_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(AmbientPlaybackState())
    val playbackState: StateFlow<AmbientPlaybackState> = _playbackState.asStateFlow()

    private val attachedListeners = mutableMapOf<PlayerSlot, Player.Listener>()
    private var bufferingWatchdog: Job? = null
    private var yielded: Boolean = false

    init {
        // Collect transition state to sync slot alphas
        scope.launch {
            transitionController.state.collect { transition ->
                _playbackState.update { current ->
                    current.copy(
                        slotAAlpha = transition.slotAAlpha,
                        slotBAlpha = transition.slotBAlpha,
                        isTransitioning = transition.isTransitioning,
                        activeSlot = transition.activeSlot
                    )
                }
            }
        }
    }

    /**
     * Preloads and plays [candidate].
     * If immediate or no current playback, starts directly on activeSlot.
     * Otherwise preloads onto alternate slot and executes a 3-second crossfade.
     */
    suspend fun playCandidate(candidate: AmbientCandidate, immediate: Boolean = false): Boolean {
        val state = _playbackState.value
        val hasActivePlayback = state.currentCandidate != null && state.isPlaying

        return if (immediate || !hasActivePlayback) {
            playImmediate(candidate)
        } else {
            val alternateSlot = state.activeSlot.other()
            val alternatePlayer = playerPool.getPlayer(alternateSlot)
            if (alternatePlayer == null) {
                Log.w(TAG, "Cannot get player for alternate slot $alternateSlot")
                return playImmediate(candidate)
            }

            attachListenerIfNeeded(alternateSlot, alternatePlayer)
            val result = preloader.preload(candidate, alternatePlayer, autoPlay = false)
            if (result is PreloadResult.Failure) {
                Log.w(TAG, "Preload failed for ${candidate.id}: ${result.reason}; falling back to immediate play")
                return playImmediate(candidate)
            }

            _playbackState.update { it.copy(nextCandidate = candidate) }
            transitionToNext { candidate }
        }
    }

    /**
     * Preloads [candidate] onto the inactive slot in advance.
     */
    suspend fun prepareNext(candidate: AmbientCandidate): Boolean {
        val inactiveSlot = _playbackState.value.activeSlot.other()
        val inactivePlayer = playerPool.getPlayer(inactiveSlot) ?: return false
        attachListenerIfNeeded(inactiveSlot, inactivePlayer)

        val result = preloader.preload(candidate, inactivePlayer)
        return if (result is PreloadResult.Success) {
            _playbackState.update { it.copy(nextCandidate = candidate) }
            true
        } else {
            Log.w(TAG, "prepareNext failed for ${candidate.id}")
            false
        }
    }

    /**
     * Executes crossfade transition to the next candidate.
     */
    suspend fun transitionToNext(candidateProvider: suspend () -> AmbientCandidate?): Boolean {
        val state = _playbackState.value
        val targetCandidate = state.nextCandidate ?: candidateProvider() ?: run {
            Log.w(TAG, "transitionToNext: no candidate available")
            return false
        }

        val fromSlot = state.activeSlot
        val toSlot = fromSlot.other()
        val targetPlayer = playerPool.getPlayer(toSlot) ?: return false
        val sourcePlayer = playerPool.getPlayer(fromSlot)

        attachListenerIfNeeded(toSlot, targetPlayer)

        // If target was not already preloaded, preload now
        if (state.nextCandidate == null) {
            val result = preloader.preload(targetCandidate, targetPlayer)
            if (result is PreloadResult.Failure) {
                Log.w(TAG, "transitionToNext: preload failed for ${targetCandidate.id}: ${result.reason}")
                return false
            }
        }

        _playbackState.update { it.copy(isTransitioning = true, error = null) }

        // Start crossfade: targetPlayer starts playing immediately while alpha rises
        targetPlayer.volume = 0f
        targetPlayer.playWhenReady = true

        val completed = transitionController.transitionTo(
            targetSlot = toSlot,
            targetPlayer = targetPlayer,
            sourcePlayer = sourcePlayer,
            durationMs = 3_000L
        )

        if (completed) {
            _playbackState.update {
                it.copy(
                    activeSlot = toSlot,
                    currentCandidate = targetCandidate,
                    nextCandidate = null,
                    isTransitioning = false,
                    isPlaying = true
                )
            }
        } else {
            _playbackState.update { it.copy(isTransitioning = false) }
        }

        return completed
    }

    fun pause() {
        scope.launch {
            val slot = _playbackState.value.activeSlot
            playerPool.getPlayer(slot)?.pause()
            _playbackState.update { it.copy(isPlaying = false) }
        }
    }

    fun resume() {
        scope.launch {
            if (yielded) return@launch
            val slot = _playbackState.value.activeSlot
            val player = playerPool.getPlayer(slot) ?: return@launch
            enforceMute(player)
            player.play()
            _playbackState.update { it.copy(isPlaying = true) }
        }
    }

    fun stop() {
        scope.launch {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
            playerPool.stopAll()
            transitionController.reset(PlayerSlot.SLOT_A)
            _playbackState.value = AmbientPlaybackState()
        }
    }

    fun yield() {
        scope.launch {
            yielded = true
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
            playerPool.yield()
            _playbackState.update { it.copy(isPlaying = false, isBuffering = false) }
        }
    }

    fun reclaim() {
        scope.launch {
            yielded = false
            playerPool.reclaim()
            val state = _playbackState.value
            state.currentCandidate?.let { candidate ->
                playImmediate(candidate)
            }
        }
    }

    private suspend fun playImmediate(candidate: AmbientCandidate): Boolean {
        val slot = _playbackState.value.activeSlot
        val player = playerPool.getPlayer(slot)
        if (player == null) {
            Log.w(TAG, "playImmediate: player for $slot is null")
            _playbackState.update { it.copy(error = "Player unavailable") }
            return false
        }

        attachListenerIfNeeded(slot, player)

        // Reset transition state and make the target slot visible immediately
        // so the player layer is shown while buffering begins.
        transitionController.reset(slot)
        _playbackState.update {
            it.copy(
                activeSlot = slot,
                slotAAlpha = if (slot == PlayerSlot.SLOT_A) 1f else 0f,
                slotBAlpha = if (slot == PlayerSlot.SLOT_B) 1f else 0f,
                isTransitioning = false,
                error = null
            )
        }

        val result = preloader.preload(candidate, player, autoPlay = true)
        if (result is PreloadResult.Failure) {
            Log.w(TAG, "playImmediate: preload failed for ${candidate.id}: ${result.reason}")
            _playbackState.update { it.copy(error = "Failed to load candidate: ${result.reason}") }
            return false
        }

        withContext(Dispatchers.Main) {
            enforceMute(player)
            player.playWhenReady = true
            player.play()
        }

        _playbackState.update {
            it.copy(
                activeSlot = slot,
                currentCandidate = candidate,
                nextCandidate = null,
                isPlaying = true,
                isTransitioning = false,
                slotAAlpha = if (slot == PlayerSlot.SLOT_A) 1f else 0f,
                slotBAlpha = if (slot == PlayerSlot.SLOT_B) 1f else 0f,
                error = null
            )
        }

        return true
    }

    private fun enforceMute(player: ExoPlayer) {
        if (player.volume != 0f) {
            player.volume = 0f
        }
    }

    private fun attachListenerIfNeeded(slot: PlayerSlot, player: ExoPlayer) {
        if (attachedListeners.containsKey(slot)) return

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_playbackState.value.activeSlot != slot) return

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(isBuffering = true) }
                        startBufferingWatchdog(slot)
                    }
                    Player.STATE_READY -> {
                        bufferingWatchdog?.cancel()
                        bufferingWatchdog = null
                        enforceMute(player)
                        _playbackState.update { it.copy(isBuffering = false) }
                    }
                    Player.STATE_ENDED -> {
                        bufferingWatchdog?.cancel()
                        bufferingWatchdog = null
                        _playbackState.update { it.copy(isPlaying = false, isBuffering = false) }
                    }
                    Player.STATE_IDLE -> {
                        bufferingWatchdog?.cancel()
                        bufferingWatchdog = null
                        _playbackState.update { it.copy(isBuffering = false) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (_playbackState.value.activeSlot != slot) return
                Log.e(TAG, "Player error on $slot: ${error.errorCodeName}", error)
                bufferingWatchdog?.cancel()
                bufferingWatchdog = null
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        error = error.message ?: error.errorCodeName
                    )
                }
            }

            override fun onVolumeChanged(volume: Float) {
                // Defensive zero-audio safety lock
                if (volume != 0f) {
                    enforceMute(player)
                }
            }
        }

        player.addListener(listener)
        attachedListeners[slot] = listener
    }

    private fun startBufferingWatchdog(slot: PlayerSlot) {
        bufferingWatchdog?.cancel()
        bufferingWatchdog = scope.launch {
            delay(BUFFERING_TIMEOUT_MS)
            if (_playbackState.value.activeSlot == slot && _playbackState.value.isBuffering) {
                Log.w(TAG, "Buffering watchdog expired on $slot")
                _playbackState.update {
                    it.copy(isBuffering = false, error = "Buffering timeout")
                }
            }
        }
    }
}
