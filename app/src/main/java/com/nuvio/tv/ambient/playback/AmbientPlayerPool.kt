package com.nuvio.tv.ambient.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Identifies one of the two reusable ambient [ExoPlayer] instances.
 */
enum class PlayerSlot { SLOT_A, SLOT_B }

/**
 * Pool that owns two reusable [ExoPlayer] instances used for ambient/screensaver playback.
 *
 * Provides:
 *  - Lazy creation of players per slot (Slot A & Slot B) for seamless crossfading.
 *  - Strict zero-audio enforcement (audio track type disabled in track selector + volume 0f).
 *  - Aggressive buffering tuned for continuous 4K streaming.
 *  - [yield] / [reclaim] to release and re-acquire hardware decoders when the main
 *    video player needs them.
 */
@OptIn(UnstableApi::class)
@Singleton
class AmbientPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) {

    companion object {
        private const val TAG = "AmbientPlayerPool"

        // Buffer tuning for 4K ambient playback
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 90_000
        private const val BUFFER_FOR_PLAYBACK_MS = 1_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

        private const val ALLOCATION_SIZE = 65_536
        private const val INITIAL_BITRATE_ESTIMATE = 50_000_000L // 50 Mbps
    }

    private val lock = Any()

    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null

    @Volatile
    private var cachedForceNative: Boolean = false

    private val yielded = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    init {
        Thread {
            try {
                cachedForceNative = runBlocking(Dispatchers.IO) {
                    playerSettingsDataStore.nuvioPerformanceModeEnabled.first()
                }
            } catch (_: Exception) {
                cachedForceNative = false
            }
        }.start()
    }

    /**
     * Returns the player for [slot], creating it lazily if necessary.
     * Returns `null` if the pool has been [yield]ed or permanently [release]d.
     */
    fun getPlayer(slot: PlayerSlot): ExoPlayer? {
        if (released.get()) {
            Log.w(TAG, "getPlayer called after release(); returning null")
            return null
        }
        if (yielded.get()) {
            reclaim()
        }

        synchronized(lock) {
            if (released.get()) return null

            val existing = when (slot) {
                PlayerSlot.SLOT_A -> playerA
                PlayerSlot.SLOT_B -> playerB
            }
            if (existing != null) return existing

            val created = try {
                createPlayer()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create ExoPlayer for $slot", t)
                null
            } ?: return null

            when (slot) {
                PlayerSlot.SLOT_A -> playerA = created
                PlayerSlot.SLOT_B -> playerB = created
            }
            return created
        }
    }

    /**
     * Stops playback on [slot] and clears its media items without releasing the player.
     */
    fun stop(slot: PlayerSlot) {
        synchronized(lock) {
            val player = when (slot) {
                PlayerSlot.SLOT_A -> playerA
                PlayerSlot.SLOT_B -> playerB
            } ?: return

            try {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop player for $slot", t)
            }
        }
    }

    /**
     * Stops playback on both slots and clears their media items.
     */
    fun stopAll() {
        synchronized(lock) {
            forEachPlayer { player ->
                try {
                    player.playWhenReady = false
                    player.stop()
                    player.clearMediaItems()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to stop player", t)
                }
            }
        }
    }

    /**
     * Releases both [ExoPlayer] instances to free hardware decoders for the main
     * video player, and marks the pool as yielded.
     */
    fun yield() {
        if (yielded.compareAndSet(false, true)) {
            Log.d(TAG, "Yielding ambient players for main playback")
            synchronized(lock) {
                forEachPlayer { player ->
                    try {
                        player.playWhenReady = false
                        player.stop()
                        player.clearMediaItems()
                        player.release()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to release player during yield", t)
                    }
                }
                playerA = null
                playerB = null
            }
        }
    }

    /**
     * Re-enables the pool after a [yield]. Players will be recreated lazily on the
     * next [getPlayer] call.
     */
    fun reclaim() {
        if (released.get()) return
        if (yielded.compareAndSet(true, false)) {
            Log.d(TAG, "Reclaimed ambient players")
        }
    }

    /**
     * Permanently releases both players and marks the pool unusable.
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            synchronized(lock) {
                forEachPlayer { player ->
                    try {
                        player.playWhenReady = false
                        player.stop()
                        player.clearMediaItems()
                        player.release()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to release player", t)
                    }
                }
                playerA = null
                playerB = null
                yielded.set(false)
                Log.d(TAG, "Released ambient player pool")
            }
        }
    }

    private inline fun forEachPlayer(action: (ExoPlayer) -> Unit) {
        playerA?.let(action)
        playerB?.let(action)
    }

    /**
     * Creates a fully configured [ExoPlayer] with zero-audio enforcement and
     * 4K-friendly buffering.
     */
    private fun createPlayer(): ExoPlayer {
        // CRITICAL ZERO-AUDIO ENFORCEMENT:
        // 1. Audio track completely disabled at the TrackSelector level
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
            )
        }

        val allocator = DefaultAllocator(
            /* trimOnReset = */ true,
            /* individualAllocationSize = */ ALLOCATION_SIZE,
            /* initialAllocationCount = */ 0,
            /* forceNativeAllocation = */ cachedForceNative
        )

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE)
            .build()

        val player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()

        // CRITICAL ZERO-AUDIO ENFORCEMENT:
        // 2. Volume explicitly locked to 0f
        player.volume = 0f

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING

        return player
    }
}
