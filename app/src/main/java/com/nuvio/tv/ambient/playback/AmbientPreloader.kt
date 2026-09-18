package com.nuvio.tv.ambient.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of an ambient preload attempt.
 */
sealed interface PreloadResult {
    data class Success(
        val candidate: AmbientCandidate,
        val resolvedVideoUrl: String
    ) : PreloadResult

    data class Failure(
        val candidate: AmbientCandidate,
        val reason: String,
        val cause: Throwable? = null
    ) : PreloadResult
}

/**
 * Preloads an ambient candidate's video stream into an [ExoPlayer] instance so that
 * playback can start instantly when the ambient transition occurs.
 *
 * Only the video track is used (audio is intentionally ignored for zero-audio ambience).
 */
@Singleton
class AmbientPreloader @Inject constructor(
    private val youTubeExtractor: InAppYouTubeExtractor
) {

    /**
     * Resolves the candidate's stream URL and prepares it on [player], waiting until the
     * player reaches [Player.STATE_READY] (or renders its first frame) within [timeoutMs].
     *
     * The player is left paused ([Player.playWhenReady] = false) so the caller can trigger
     * playback at the exact transition moment.
     */
    @OptIn(UnstableApi::class)
    suspend fun preload(
        candidate: AmbientCandidate,
        player: ExoPlayer,
        timeoutMs: Long = 15_000L
    ): PreloadResult {
        // 1. Resolve the stream URL (direct or via YouTube extraction).
        val resolvedVideoUrl = try {
            resolveVideoUrl(candidate)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve stream URL for candidate=${candidate.id}", t)
            return PreloadResult.Failure(candidate, "Stream URL resolution failed", t)
        }

        if (resolvedVideoUrl.isNullOrBlank()) {
            Log.w(TAG, "No video URL resolved for candidate=${candidate.id}")
            return PreloadResult.Failure(candidate, "No video URL available")
        }

        // 2. Attach the media source and prepare on the main thread.
        val readyDeferred = CompletableDeferred<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    readyDeferred.complete(true)
                }
            }

            override fun onRenderedFirstFrame() {
                readyDeferred.complete(true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Player error while preloading candidate=${candidate.id}", error)
                readyDeferred.complete(false)
            }
        }

        try {
            withContext(Dispatchers.Main) {
                player.addListener(listener)
                player.stop()
                player.clearMediaItems()
                player.volume = 0f

                val dataSourceFactory = YoutubeChunkedDataSourceFactory()
                val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
                val mediaSource = mediaSourceFactory.createMediaSource(
                    MediaItem.fromUri(Uri.parse(resolvedVideoUrl))
                )

                player.setMediaSource(mediaSource)
                player.prepare()
                // Remain paused until the transition triggers playback.
                player.playWhenReady = false
            }

            // 3. Wait for readiness within the timeout.
            val ready = withTimeoutOrNull(timeoutMs) { readyDeferred.await() }

            return when (ready) {
                true -> PreloadResult.Success(candidate, resolvedVideoUrl)
                false -> PreloadResult.Failure(candidate, "Player reported an error during preload")
                null -> PreloadResult.Failure(candidate, "Preload timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unexpected error while preloading candidate=${candidate.id}", t)
            return PreloadResult.Failure(candidate, "Unexpected preload error", t)
        } finally {
            // Always detach the temporary listener to avoid leaks.
            withContext(Dispatchers.Main) {
                runCatching { player.removeListener(listener) }
            }
        }
    }

    /**
     * Resolves the video URL for the candidate.
     * - Uses [AmbientCandidate.streamUrl] directly when present.
     * - Otherwise falls back to YouTube extraction using [AmbientCandidate.youtubeId]
     *   or an 11-character [AmbientCandidate.id].
     */
    private suspend fun resolveVideoUrl(candidate: AmbientCandidate): String? {
        val direct = candidate.playbackUri
        if (!direct.isNullOrBlank()) return direct

        val youtubeId = candidate.youtubeVideoId?.takeIf { it.isNotBlank() }
            ?: candidate.id.removePrefix("yt_").takeIf { it.length == YOUTUBE_ID_LENGTH }

        if (youtubeId.isNullOrBlank()) {
            Log.w(TAG, "Candidate=${candidate.id} has no playbackUri and no valid YouTube id")
            return null
        }

        val watchUrl = "https://www.youtube.com/watch?v=$youtubeId"
        val source = youTubeExtractor.extractPlaybackSource(watchUrl)
        // Intentionally ignore audioUrl — ambient playback is video-only.
        return source?.videoUrl
    }

    private companion object {
        private const val TAG = "AmbientPreloader"
        private const val YOUTUBE_ID_LENGTH = 11
    }
}
