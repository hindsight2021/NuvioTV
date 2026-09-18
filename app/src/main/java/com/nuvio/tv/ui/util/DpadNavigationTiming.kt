package com.nuvio.tv.ui.util

/**
 * Centralized timing constants and gating logic for D-pad driven navigation.
 *
 * The values here are tuned so that horizontal movement feels snappy while
 * vertical movement (typically list scrolling) is throttled to avoid
 * overshooting content. All durations are expressed in milliseconds.
 */
internal object DpadNavigationTiming {

    /** Repeat interval for standard horizontal D-pad navigation. */
    const val STANDARD_HORIZONTAL_REPEAT_MS: Long = 64L

    /** Faster repeat interval used when horizontal navigation should feel accelerated. */
    const val FAST_HORIZONTAL_REPEAT_MS: Long = 44L

    /** Repeat interval for vertical D-pad navigation (e.g. list scrolling). */
    const val VERTICAL_REPEAT_MS: Long = 88L

    /**
     * Time window after the last accepted event during which a held scroll is
     * still considered active. Used to distinguish a held key from a fresh press.
     */
    const val HELD_SCROLL_END_TIMEOUT_MS: Long = 120L
}

/**
 * Rate-limits directional input events independently per direction.
 *
 * Each direction maintains its own timestamp of the last accepted event. A new
 * event is accepted only if at least [minimumIntervalMs] has elapsed since the
 * previous accepted event for that same direction. This prevents a single held
 * key (or repeated key-down events) from firing faster than the configured
 * cadence while still allowing different directions to be processed
 * independently.
 *
 * @param directionCount number of distinct directions to track. Defaults to 4
 *   (up, down, left, right).
 */
internal class DirectionalRepeatGate(directionCount: Int = 4) {

    init {
        require(directionCount > 0) { "directionCount must be positive, was $directionCount" }
    }

    /** Timestamp (ms) of the last accepted event per direction, or [UNSET]. */
    private val lastAcceptedAt: LongArray = LongArray(directionCount) { UNSET }

    /**
     * Clears the recorded timestamp for [direction], allowing the next event to
     * be accepted immediately regardless of the elapsed interval.
     *
     * @param direction index of the direction to reset.
     */
    fun reset(direction: Int) {
        if (direction in lastAcceptedAt.indices) {
            lastAcceptedAt[direction] = UNSET
        }
    }

    /**
     * Attempts to acquire the gate for [direction] at time [nowMs].
     *
     * @param direction index of the direction requesting the gate.
     * @param nowMs current time in milliseconds (monotonic source recommended).
     * @param minimumIntervalMs minimum elapsed time required since the last
     *   accepted event for this direction.
     * @return `true` if the event is accepted (and the timestamp is updated),
     *   `false` if it was throttled or [direction] is out of range.
     */
    fun tryAcquire(direction: Int, nowMs: Long, minimumIntervalMs: Long): Boolean {
        if (direction !in lastAcceptedAt.indices) return false

        val last = lastAcceptedAt[direction]
        if (last != UNSET && nowMs - last < minimumIntervalMs) {
            return false
        }

        lastAcceptedAt[direction] = nowMs
        return true
    }

    private companion object {
        /** Sentinel indicating that no event has been accepted yet for a direction. */
        const val UNSET: Long = Long.MIN_VALUE
    }
}
