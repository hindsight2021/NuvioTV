package com.nuvio.tv.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.nuvio.tv.R

object StartupSoundPlayer {
    private const val TAG = "StartupSoundPlayer"

    @Volatile
    private var activePlayer: MediaPlayer? = null

    val isPlaying: Boolean
        get() = activePlayer?.runCatching { isPlaying }?.getOrDefault(false) ?: false

    fun play(context: Context) {
        try {
            val prefs = context.getSharedPreferences("nuvio_plus_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enable_startup_sound", true)) {
                Log.d(TAG, "Startup sound disabled in preferences")
                return
            }

            // If an instance is already playing, let it finish uninterrupted
            val existing = activePlayer
            if (existing != null && runCatching { existing.isPlaying }.getOrDefault(false)) {
                Log.d(TAG, "Startup sound is already playing; ignoring duplicate play request")
                return
            }

            stop()

            val appContext = context.applicationContext
            val afd = appContext.resources.openRawResourceFd(R.raw.nuvio_startup) ?: run {
                Log.w(TAG, "Could not open raw resource for startup chime")
                return
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { mp ->
                    Log.d(TAG, "Startup sound finished playing in full (${mp.duration}ms)")
                    releasePlayer(mp)
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "Startup sound error: what=$what, extra=$extra")
                    releasePlayer(mp)
                    true
                }
            }
            afd.close()

            player.prepare()
            activePlayer = player
            player.start()
            Log.d(TAG, "Startup sound started successfully (duration=${player.duration}ms)")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not play startup chime: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        activePlayer?.let { releasePlayer(it) }
    }

    private fun releasePlayer(player: MediaPlayer) {
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Throwable) {}
        try {
            player.release()
        } catch (_: Throwable) {}
        if (activePlayer === player) {
            activePlayer = null
        }
    }
}
