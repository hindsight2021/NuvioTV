package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.autopilot.AutopilotCandidate
import com.nuvio.tv.core.autopilot.AutopilotDecision
import com.nuvio.tv.core.autopilot.AutopilotMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Toggles the autopilot feature on or off, persisting the new setting and updating the UI.
 *
 * When enabling autopilot, an immediate evaluation is triggered (if applicable) and a banner is
 * shown reflecting the active mode. When disabling, any pending autopilot decision is dismissed.
 */
fun PlayerRuntimeController.onToggleAutopilot() {
    autopilotEnabledSetting = !autopilotEnabledSetting

    scope.launch {
        playerSettingsDataStore.setAutopilotEnabled(autopilotEnabledSetting)
    }

    _uiState.update { it.copy(autopilotEnabled = autopilotEnabledSetting) }

    val bannerText = if (autopilotEnabledSetting) {
        if (autopilotModeSetting == AutopilotMode.AI_CURATED) {
            "Autopilot ON • AI Curated"
        } else {
            "Autopilot ON • Default"
        }
    } else {
        "Autopilot OFF"
    }
    showAutopilotBanner(bannerText)

    if (autopilotEnabledSetting) {
        checkAndTriggerAutopilotIfNeeded()
    } else {
        dismissAutopilot()
    }
}

/**
 * Updates the autopilot selection mode, persists it, and refreshes any pending decision.
 *
 * @param mode The new [AutopilotMode] to apply.
 */
fun PlayerRuntimeController.onSetAutopilotMode(mode: AutopilotMode) {
    autopilotModeSetting = mode

    scope.launch {
        playerSettingsDataStore.setAutopilotMode(mode)
    }

    _uiState.update { it.copy(autopilotMode = mode) }

    if (autopilotEnabledSetting) {
        checkAndTriggerAutopilotIfNeeded(forceRefresh = true)
    }
}

/**
 * Evaluates whether autopilot should present a next-episode suggestion for the current content.
 *
 * The evaluation is skipped when autopilot is disabled, the current episode has already been
 * dismissed, no decision engine is available, the current show still has an unplayed aired
 * episode (binge priority), or an evaluation is already in flight (unless [forceRefresh]).
 *
 * On a successful decision, the UI is updated with the candidate and a countdown is started.
 *
 * @param forceRefresh When true, bypasses the "already showing autopilot" and "job active" guards.
 */
fun PlayerRuntimeController.checkAndTriggerAutopilotIfNeeded(forceRefresh: Boolean = false) {
    if (!autopilotEnabledSetting) return
    if (_uiState.value.postPlayDismissedForCurrentEpisode) return
    if (autopilotDecisionEngine == null) return

    // Binging the current show takes priority until caught up to the latest aired episode.
    val nextEp = _uiState.value.nextEpisode
    if (nextEp != null && nextEp.hasAired) {
        return
    }

    if (!forceRefresh && _uiState.value.postPlayMode is PostPlayMode.Autopilot) return
    if (autopilotJob?.isActive == true && !forceRefresh) return

    autopilotJob?.cancel()
    autopilotJob = scope.launch {
        try {
            val currentContentId = contentId
            val currentTitle = _uiState.value.title.ifBlank { null }
            val currentSeason = currentSeason
            val currentEpisode = currentEpisode

            val decision: AutopilotDecision? = autopilotDecisionEngine.selectNextEpisode(
                currentContentId = currentContentId,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                currentTitle = currentTitle,
                currentGenres = emptyList(),
                mode = autopilotModeSetting
            )

            if (decision == null) {
                Log.d("Autopilot", "No suitable CW candidate found")
                return@launch
            }

            val candidate = decision.candidate
            val syntheticNextInfo = NextEpisodeInfo(
                videoId = candidate.videoId,
                season = candidate.season ?: 1,
                episode = candidate.episode ?: 1,
                title = candidate.episodeTitle ?: candidate.title,
                thumbnail = candidate.thumbnail ?: candidate.poster,
                overview = candidate.episodeDescription,
                released = candidate.releaseDateLabel,
                hasAired = true,
                unairedMessage = null
            )

            _uiState.update {
                it.copy(
                    autopilotCandidate = candidate,
                    autopilotAiRationale = decision.aiRationale,
                    postPlayMode = PostPlayMode.Autopilot(
                        nextEpisode = syntheticNextInfo,
                        candidate = candidate,
                        autopilotMode = decision.mode,
                        aiRationale = decision.aiRationale,
                        countdownSec = 5,
                        searching = false
                    ),
                    playbackEnded = false
                )
            }

            startAutopilotCountdown(candidate)
        } catch (e: Exception) {
            Log.w("Autopilot", "Autopilot evaluation failed", e)
        }
    }
}

/**
 * Runs a 5-second countdown for the given autopilot [candidate], updating the UI each second.
 *
 * When the countdown reaches zero, playback of the candidate is triggered automatically.
 *
 * @param candidate The candidate episode to play once the countdown completes.
 */
fun PlayerRuntimeController.startAutopilotCountdown(candidate: AutopilotCandidate) {
    autopilotCountdownJob?.cancel()
    autopilotCountdownJob = scope.launch {
        for (sec in 5 downTo 1) {
            _uiState.update { current ->
                val active = current.postPlayMode as? PostPlayMode.Autopilot
                    ?: return@update current
                current.copy(postPlayMode = active.copy(countdownSec = sec))
            }
            delay(1000)
        }
        // Auto-trigger playback when countdown reaches 0.
        triggerAutopilotPlayback(candidate, userInitiated = false)
    }
}

/**
 * Commits to playing the autopilot [candidate], clearing any pending jobs and post-play state.
 *
 * @param candidate The candidate episode to play.
 * @param userInitiated Whether the playback was explicitly requested by the user.
 */
fun PlayerRuntimeController.triggerAutopilotPlayback(
    candidate: AutopilotCandidate,
    userInitiated: Boolean = false
) {
    autopilotCountdownJob?.cancel()
    autopilotCountdownJob = null
    autopilotJob?.cancel()
    autopilotJob = null

    _uiState.update {
        it.copy(
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true
        )
    }

    onAutopilotPlayCallback?.invoke(candidate)
}

/**
 * Dismisses any pending autopilot decision, cancelling active jobs and clearing the post-play UI.
 *
 * Marks the current episode as dismissed so autopilot does not re-trigger for it.
 */
fun PlayerRuntimeController.dismissAutopilot() {
    autopilotCountdownJob?.cancel()
    autopilotCountdownJob = null
    autopilotJob?.cancel()
    autopilotJob = null

    _uiState.update {
        if (it.postPlayMode is PostPlayMode.Autopilot) {
            it.copy(
                postPlayMode = null,
                postPlayDismissedForCurrentEpisode = true
            )
        } else {
            it
        }
    }
}

/**
 * Shows a transient autopilot indicator banner with the given [text].
 *
 * The banner is automatically hidden after a short delay.
 *
 * @param text The message to display in the banner.
 */
fun PlayerRuntimeController.showAutopilotBanner(text: String) {
    _uiState.update {
        it.copy(
            showAutopilotIndicator = true,
            autopilotIndicatorText = text
        )
    }

    hideAutopilotIndicatorJob?.cancel()
    hideAutopilotIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showAutopilotIndicator = false) }
    }
}
