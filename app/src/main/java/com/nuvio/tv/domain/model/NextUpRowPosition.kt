package com.nuvio.tv.domain.model

/**
 * Represents the vertical position of the "Next Up" row relative to the
 * "Continue Watching" row on the home screen.
 */
enum class NextUpRowPosition {
    /** The "Next Up" row is displayed above the "Continue Watching" row. */
    ABOVE_CONTINUE_WATCHING,

    /** The "Next Up" row is displayed below the "Continue Watching" row. */
    BELOW_CONTINUE_WATCHING
}
