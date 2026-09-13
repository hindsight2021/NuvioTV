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
            val mediaPlayer = MediaPlayer.create(context, R.raw.nuvio_startup) ?: return
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
            )
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
