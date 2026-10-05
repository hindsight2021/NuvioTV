package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/**
 * Determines whether scrobbling (Trakt / Simkl) should occur for the current playback.
 *
 * Random episodes are intentionally excluded from all scrobbling to avoid polluting
 * real-time status and watch history with non-deterministic picks.
 */
internal fun shouldTrackOrScrobble(isRandomEpisode: Boolean): Boolean = !isRandomEpisode

/**
 * Determines whether playback progress should be tracked for the current playback
 * in Continue Watching / Nuvio local watch progress.
 *
 * Random episodes are always excluded from progress tracking. When the user is in channel-shuffle
 * mode, progress tracking is only performed if the user has opted in via [trackChannelShuffleInCw].
 */
internal fun shouldTrackProgress(
    isRandomEpisode: Boolean,
    isChannelShuffle: Boolean,
    trackChannelShuffleInCw: Boolean
): Boolean {
    if (isRandomEpisode) return false
    return if (isChannelShuffle) trackChannelShuffleInCw else true
}

