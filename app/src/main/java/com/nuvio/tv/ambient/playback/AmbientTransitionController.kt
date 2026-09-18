package com.nuvio.tv.ambient.playback

import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Represents the current crossfade state between two player slots.
 */
data class TransitionState(
    val activeSlot: PlayerSlot = PlayerSlot.SLOT_A,
    val slotAAlpha: Float = 1f,
    val slotBAlpha: Float = 0f,
    val isTransitioning: Boolean = false
)

/**
 * Controls smooth 3-second crossfade transitions between PlayerSlot.SLOT_A and PlayerSlot.SLOT_B.
 * Guarantees zero audio during the crossfade.
 */
@Singleton
class AmbientTransitionController @Inject constructor() {

    private val _state = MutableStateFlow(TransitionState())
    val state: StateFlow<TransitionState> = _state.asStateFlow()

    private var transitionJob: Job? = null

    /**
     * Crossfades from the currently active slot to [targetSlot].
     * Target player is set to playWhenReady = true with volume = 0f.
     * Over [durationMs], alpha is smoothly crossfaded.
     * When completed, source player is stopped and cleared.
     */
    suspend fun transitionTo(
        targetSlot: PlayerSlot,
        targetPlayer: ExoPlayer,
        sourcePlayer: ExoPlayer?,
        durationMs: Long = 3_000L
    ): Boolean = withContext(Dispatchers.Main) {
        val current = _state.value
        if (current.activeSlot == targetSlot && !current.isTransitioning) {
            return@withContext true
        }

        cancelCurrentTransition()

        // Ensure zero audio and start target playback
        targetPlayer.volume = 0f
        targetPlayer.playWhenReady = true

        val startA = current.slotAAlpha
        val startB = current.slotBAlpha
        val endA = if (targetSlot == PlayerSlot.SLOT_A) 1f else 0f
        val endB = if (targetSlot == PlayerSlot.SLOT_B) 1f else 0f

        _state.value = current.copy(isTransitioning = true)

        val startTime = System.currentTimeMillis()
        val totalMs = durationMs.coerceAtLeast(100L)

        try {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

                val curA = startA + (endA - startA) * progress
                val curB = startB + (endB - startB) * progress

                _state.value = TransitionState(
                    activeSlot = if (progress >= 0.5f) targetSlot else current.activeSlot,
                    slotAAlpha = curA,
                    slotBAlpha = curB,
                    isTransitioning = true
                )

                if (progress >= 1f) break
                delay(16L) // ~60 fps
            }

            // Cleanup source player
            sourcePlayer?.playWhenReady = false
            sourcePlayer?.stop()
            sourcePlayer?.clearMediaItems()

            _state.value = TransitionState(
                activeSlot = targetSlot,
                slotAAlpha = endA,
                slotBAlpha = endB,
                isTransitioning = false
            )
            true
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(isTransitioning = false)
            false
        }
    }

    fun reset(initialSlot: PlayerSlot = PlayerSlot.SLOT_A) {
        cancelCurrentTransition()
        val (a, b) = if (initialSlot == PlayerSlot.SLOT_A) 1f to 0f else 0f to 1f
        _state.value = TransitionState(
            activeSlot = initialSlot,
            slotAAlpha = a,
            slotBAlpha = b,
            isTransitioning = false
        )
    }

    fun cancelCurrentTransition() {
        transitionJob?.cancel()
        transitionJob = null
        if (_state.value.isTransitioning) {
            _state.value = _state.value.copy(isTransitioning = false)
        }
    }
}
