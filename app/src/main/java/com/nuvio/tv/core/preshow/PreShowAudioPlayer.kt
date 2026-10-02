package com.nuvio.tv.core.preshow

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RawRes
import com.nuvio.tv.R
import java.io.File
import kotlin.random.Random

/**
 * Plays cinema pre-show audio (showtime intros and looping trivia beds).
 *
 * Scans /sdcard/Download/MovieNight/ first (and /sdcard/Nuvio/MovieNight/),
 * falling back to bundled raw resources.
 * Rule: Files containing "showtime" are intro stingers.
 * All other audio files are trivia background beds.
 */
class PreShowAudioPlayer {

    private companion object {
        const val TAG = "PreShowAudioPlayer"

        val LOCAL_AUDIO_DIRS = listOf(
            "/sdcard/Download/MovieNight",
            "/sdcard/Nuvio/MovieNight"
        )

        const val TRIVIA_BED_VOLUME = 0.65f
        const val INTRO_VOLUME = 1.0f
        const val DEFAULT_FADE_OUT_MS = 1000L
        const val TRIVIA_FADE_IN_MS = 1200L
        const val FADE_STEP_MS = 40L

        val INTRO_RAW_RES: IntArray = intArrayOf(
            R.raw.preshow_showtime_0,
            R.raw.preshow_showtime_1
        )

        val TRIVIA_RAW_RES: IntArray = intArrayOf(
            R.raw.preshow_trivia_bed_0,
            R.raw.preshow_trivia_bed_1
        )

        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
    }

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fadeRunnable: Runnable? = null
    private var triviaBedActive = false

    private sealed interface AudioSource {
        data class Local(val file: File) : AudioSource
        data class Raw(@RawRes val resId: Int) : AudioSource
    }

    fun playIntro(context: Context, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        releaseCurrentPlayer()

        val source = selectIntroSource()
        if (source == null) {
            Log.w(TAG, "No showtime intro source available; skipping intro.")
            postCompletion(onComplete)
            return
        }

        val mp = createPlayer(appContext, source)
        if (mp == null) {
            Log.w(TAG, "Could not initialize player for intro source: $source")
            postCompletion(onComplete)
            return
        }

        synchronized(lock) {
            player = mp
            triviaBedActive = false
        }

        mp.setOnCompletionListener {
            Log.d(TAG, "Showtime intro completed.")
            releaseCurrentPlayer()
            postCompletion(onComplete)
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Showtime intro playback error: what=$what extra=$extra")
            releaseCurrentPlayer()
            postCompletion(onComplete)
            true
        }

        try {
            mp.setVolume(INTRO_VOLUME, INTRO_VOLUME)
            mp.start()
            Log.d(TAG, "Playing showtime intro from $source (duration=${mp.duration}ms)")
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting intro playback", t)
            releaseCurrentPlayer()
            postCompletion(onComplete)
        }
    }

    fun startTriviaBed(context: Context) {
        val appContext = context.applicationContext
        releaseCurrentPlayer()

        val source = selectTriviaSource()
        if (source == null) {
            Log.w(TAG, "No trivia bed source available.")
            return
        }

        val mp = createPlayer(appContext, source)
        if (mp == null) {
            Log.w(TAG, "Could not initialize player for trivia bed source: $source")
            return
        }

        synchronized(lock) {
            player = mp
            triviaBedActive = true
        }

        mp.isLooping = true
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Trivia bed playback error: what=$what extra=$extra")
            releaseCurrentPlayer()
            true
        }

        try {
            mp.setVolume(0f, 0f)
            mp.start()
            Log.d(TAG, "Started trivia bed from $source (duration=${mp.duration}ms)")
            fadeVolume(mp, from = 0f, to = TRIVIA_BED_VOLUME, durationMs = TRIVIA_FADE_IN_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting trivia bed playback", t)
            releaseCurrentPlayer()
        }
    }

    fun fadeOut(durationMs: Long = DEFAULT_FADE_OUT_MS, onComplete: (() -> Unit)? = null) {
        val current: MediaPlayer?
        val currentVolume: Float
        synchronized(lock) {
            current = player
            currentVolume = if (triviaBedActive) TRIVIA_BED_VOLUME else INTRO_VOLUME
        }

        if (current == null) {
            postCompletion(onComplete)
            return
        }

        fadeVolume(
            target = current,
            from = currentVolume,
            to = 0f,
            durationMs = durationMs,
            onFinished = {
                releaseCurrentPlayer()
                postCompletion(onComplete)
            }
        )
    }

    fun stop() {
        releaseCurrentPlayer()
    }

    fun release() {
        releaseCurrentPlayer()
        handler.removeCallbacksAndMessages(null)
    }

    private fun selectIntroSource(): AudioSource? {
        val allLocal = listAllLocalAudioFiles()
        val intros = allLocal.filter { it.name.contains("showtime", ignoreCase = true) }
        if (intros.isNotEmpty()) {
            return AudioSource.Local(intros.random())
        }
        if (INTRO_RAW_RES.isNotEmpty()) {
            return AudioSource.Raw(INTRO_RAW_RES.random())
        }
        return null
    }

    private fun selectTriviaSource(): AudioSource? {
        val allLocal = listAllLocalAudioFiles()
        val triviaFiles = allLocal.filter { !it.name.contains("showtime", ignoreCase = true) }
        if (triviaFiles.isNotEmpty()) {
            return AudioSource.Local(triviaFiles.random())
        }
        if (TRIVIA_RAW_RES.isNotEmpty()) {
            return AudioSource.Raw(TRIVIA_RAW_RES.random())
        }
        return null
    }

    private fun listAllLocalAudioFiles(): List<File> {
        val results = mutableListOf<File>()
        for (path in LOCAL_AUDIO_DIRS) {
            try {
                val dir = File(path)
                if (dir.isDirectory && dir.canRead()) {
                    val files = dir.listFiles { f ->
                        f.isFile && f.canRead() && f.extension.lowercase() in SUPPORTED_EXTENSIONS
                    }
                    if (files != null) results.addAll(files)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error listing files in $path", t)
            }
        }
        return results
    }

    private fun createPlayer(context: Context, source: AudioSource): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            when (source) {
                is AudioSource.Local -> mp.setDataSource(source.file.absolutePath)
                is AudioSource.Raw -> {
                    context.resources.openRawResourceFd(source.resId).use { afd ->
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
            }
            mp.prepare()
            mp
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create MediaPlayer for $source", t)
            null
        }
    }

    private fun releaseCurrentPlayer() {
        val toRelease: MediaPlayer?
        synchronized(lock) {
            cancelFadeLocked()
            toRelease = player
            player = null
            triviaBedActive = false
        }
        if (toRelease != null) {
            try {
                if (toRelease.isPlaying) toRelease.stop()
            } catch (_: Throwable) {}
            try {
                toRelease.reset()
                toRelease.release()
            } catch (_: Throwable) {}
        }
    }

    private fun fadeVolume(
        target: MediaPlayer,
        from: Float,
        to: Float,
        durationMs: Long,
        onFinished: (() -> Unit)? = null
    ) {
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1L)
        val delta = (to - from) / steps
        var step = 0L

        val runnable = object : Runnable {
            override fun run() {
                val stillActive = synchronized(lock) { player === target }
                if (!stillActive) return

                step++
                val nextVolume = if (step >= steps) to else from + delta * step
                try {
                    target.setVolume(nextVolume, nextVolume)
                } catch (t: Throwable) {
                    return
                }

                if (step >= steps) {
                    synchronized(lock) {
                        if (fadeRunnable === this) fadeRunnable = null
                    }
                    onFinished?.invoke()
                } else {
                    handler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }

        synchronized(lock) {
            cancelFadeLocked()
            fadeRunnable = runnable
        }
        handler.post(runnable)
    }

    private fun cancelFadeLocked() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
    }

    private fun postCompletion(callback: (() -> Unit)?) {
        if (callback == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callback()
        } else {
            handler.post(callback)
        }
    }
}
