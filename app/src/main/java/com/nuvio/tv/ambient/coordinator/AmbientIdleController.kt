package com.nuvio.tv.ambient.coordinator

import android.util.Log
import com.nuvio.tv.ambient.settings.AmbientSettingsDataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors user inactivity and triggers the ambient screensaver when the configured
 * idle timeout elapses.
 *
 * The controller is playback-aware: while primary video or external player playback is
 * active, the idle timer is continuously reset so the screensaver never interrupts
 * content the user is actively watching.
 *
 * All state mutations are confined to a single monitoring coroutine plus synchronized
 * accessors, keeping the class safe to call from any thread.
 */
@Singleton
class AmbientIdleController @Inject constructor(
    private val settingsDataStore: AmbientSettingsDataStore
) {

    companion object {
        private const val TAG = "AmbientIdleController"

        /** How often the monitoring loop wakes up to evaluate idle state. */
        private const val POLL_INTERVAL_MS = 1_000L

        /** Milliseconds in a minute, used to convert the configured timeout. */
        private const val MILLIS_PER_MINUTE = 60_000L
    }

    /** Dedicated scope so the monitoring loop is isolated from callers' lifecycles. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [lastInteractionTimestampMs], [idleTriggered] and [monitoringJob]. */
    private val lock = Any()

    /** Timestamp (elapsedRealtime-style wall clock) of the last observed user activity. */
    private var lastInteractionTimestampMs: Long = System.currentTimeMillis()

    /** True once the idle timeout has fired and the screensaver is considered active. */
    private var idleTriggered: Boolean = false

    /** The currently running monitoring job, if any. */
    private var monitoringJob: Job? = null

    /** Callback used to determine whether content playback is currently active. */
    @Volatile
    private var playbackActiveProvider: (() -> Boolean)? = null

    private val _isIdle = MutableStateFlow(false)

    /** Observable idle status. Emits `true` once the idle timeout has been triggered. */
    val isIdle: StateFlow<Boolean> = _isIdle.asStateFlow()

    /**
     * Registers a callback used to check whether primary video or external player
     * playback is currently active. While this returns `true`, the idle timer is
     * continuously reset.
     */
    fun setPlaybackActiveProvider(provider: () -> Boolean) {
        playbackActiveProvider = provider
    }

    /**
     * Records user activity: resets the idle timer and the last interaction timestamp.
     *
     * If the ambient screensaver is currently active (idle already triggered), the idle
     * flag is cleared so callers/coordinators can dismiss the screensaver.
     */
    fun notifyUserActivity() {
        val wasIdle: Boolean
        synchronized(lock) {
            lastInteractionTimestampMs = System.currentTimeMillis()
            wasIdle = idleTriggered
            idleTriggered = false
        }

        if (wasIdle) {
            Log.d(TAG, "User activity detected while idle; clearing idle state.")
            _isIdle.value = false
        }
    }

    /**
     * Starts the idle monitoring loop. Safe to call multiple times; an existing loop is
     * cancelled before a new one is started.
     *
     * @param onIdleTimeout invoked on the monitoring dispatcher when the idle timeout
     *        elapses. Callers should ensure this is cheap or dispatch their own work.
     */
    fun start(onIdleTimeout: () -> Unit) {
        synchronized(lock) {
            // Reset the timer on (re)start so we don't immediately fire from stale state.
            lastInteractionTimestampMs = System.currentTimeMillis()
            idleTriggered = false

            monitoringJob?.cancel()
            monitoringJob = scope.launch {
                Log.d(TAG, "Idle monitoring loop started.")
                runMonitoringLoop(onIdleTimeout)
            }
        }
    }

    /** Cancels the idle monitoring loop, if running. */
    fun stop() {
        synchronized(lock) {
            monitoringJob?.cancel()
            monitoringJob = null
        }
        Log.d(TAG, "Idle monitoring loop stopped.")
    }

    /**
     * Core monitoring loop. Polls every [POLL_INTERVAL_MS] and evaluates whether the
     * configured idle timeout has elapsed.
     */
    private suspend fun runMonitoringLoop(onIdleTimeout: () -> Unit) {
        while (scope.isActive) {
            try {
                delay(POLL_INTERVAL_MS)

                // Playback takes precedence: never trigger while content is playing.
                if (isPlaybackActive()) {
                    resetIdleTimer()
                    continue
                }

                val settings = try {
                    settingsDataStore.settings.first()
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to read ambient settings; skipping tick.", t)
                    continue
                }

                // Feature disabled or invalid timeout: nothing to do.
                if (!settings.isEnabled || settings.idleTimeoutMinutes <= 0) {
                    resetIdleTimer()
                    continue
                }

                val timeoutMs = settings.idleTimeoutMinutes * MILLIS_PER_MINUTE
                val elapsedMs = System.currentTimeMillis() - currentLastInteraction()

                if (elapsedMs >= timeoutMs) {
                    if (markIdleIfNotAlready()) {
                        Log.i(TAG, "Idle timeout reached (${elapsedMs}ms >= ${timeoutMs}ms); triggering screensaver.")
                        _isIdle.value = true
                        try {
                            onIdleTimeout()
                        } catch (t: Throwable) {
                            Log.e(TAG, "onIdleTimeout callback threw an exception.", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                // Defensive: never let an unexpected error kill the monitoring loop.
                Log.e(TAG, "Unexpected error in idle monitoring loop.", t)
            }
        }
    }

    /** Returns true if a playback provider is registered and reports active playback. */
    private fun isPlaybackActive(): Boolean {
        val provider = playbackActiveProvider ?: return false
        return try {
            provider()
        } catch (t: Throwable) {
            Log.w(TAG, "playbackActiveProvider threw; assuming inactive.", t)
            false
        }
    }

    /** Resets the idle timer without clearing the idle flag (used during playback). */
    private fun resetIdleTimer() {
        synchronized(lock) {
            lastInteractionTimestampMs = System.currentTimeMillis()
        }
    }

    /** Reads the last interaction timestamp under lock. */
    private fun currentLastInteraction(): Long = synchronized(lock) { lastInteractionTimestampMs }

    /**
     * Atomically marks the controller as idle. Returns `true` only on the first
     * transition so the timeout callback fires exactly once per idle period.
     */
    private fun markIdleIfNotAlready(): Boolean = synchronized(lock) {
        if (idleTriggered) {
            false
        } else {
            idleTriggered = true
            true
        }
    }
}
