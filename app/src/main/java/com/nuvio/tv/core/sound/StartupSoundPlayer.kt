package com.nuvio.tv.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.nuvio.tv.R

object StartupSoundPlayer {
    private const val TAG = "StartupSoundPlayer"

    fun play(context: Context) {
        try {
            val prefs = context.getSharedPreferences("nuvio_plus_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enable_startup_sound", true)) {
                return
            }
            val mediaPlayer = MediaPlayer.create(context, R.raw.nuvio_startup) ?: return
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mediaPlayer.setVolume(0.95f, 0.95f)
            mediaPlayer.setOnCompletionListener { player ->
                try {
                    player.release()
                } catch (_: Throwable) {}
            }
            mediaPlayer.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not play startup chime: ${e.message}")
        }
    }
}
