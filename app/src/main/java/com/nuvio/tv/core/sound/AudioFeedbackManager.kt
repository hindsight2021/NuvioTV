package com.nuvio.tv.core.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R

enum class ClickSoundProfile(val id: String, val displayName: String, val rawRes: Int) {
    APPLE_TV("appletv", "Apple TV Tactile", R.raw.sound_click_appletv),
    MODERN_DIGITAL("modern", "Modern Digital", R.raw.sound_click_modern),
    STUDIO_SUBTLE("subtle", "Studio Subtle", R.raw.sound_click_subtle);

    companion object {
        fun fromId(id: String?): ClickSoundProfile =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: APPLE_TV
    }
}

enum class PlaybackSoundAction {
    PLAY,
    PAUSE,
    STOP
}

object AudioFeedbackManager {
    private const val TAG = "AudioFeedbackManager"
    private const val PREFS_NAME = "nuvio_plus_prefs"

    const val PREF_REMOTE_CLICK_ENABLED = "sound_remote_click_enabled"
    const val PREF_NAVIGATION_CLICK_ENABLED = "sound_navigation_click_enabled"
    const val PREF_PLAYBACK_SOUNDS_ENABLED = "sound_playback_sounds_enabled"
    const val PREF_CLICK_SOUND_PROFILE = "sound_click_profile"

    private var soundPool: SoundPool? = null
    private val soundIdMap = mutableMapOf<Int, Int>()
    private var isLoaded = false
    private var lastNavSoundTimestamp = 0L
    private const val NAV_SOUND_MIN_INTERVAL_MS = 45L

    @Synchronized
    fun init(context: Context) {
        if (soundPool != null) return

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build().apply {
                    setOnLoadCompleteListener { _, _, status ->
                        if (status == 0) {
                            isLoaded = true
                        }
                    }
                }

            val resList = listOf(
                R.raw.sound_click_appletv,
                R.raw.sound_click_modern,
                R.raw.sound_click_subtle,
                R.raw.sound_nav_tick,
                R.raw.sound_playback_play,
                R.raw.sound_playback_pause,
                R.raw.sound_playback_stop
            )

            resList.forEach { resId ->
                soundPool?.let { pool ->
                    val sId = pool.load(context.applicationContext, resId, 1)
                    soundIdMap[resId] = sId
                }
            }
            Log.d(TAG, "AudioFeedbackManager initialized with SoundPool")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize SoundPool: ${e.message}")
        }
    }

    fun playClick(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_REMOTE_CLICK_ENABLED, true)) return

        val profileId = prefs.getString(PREF_CLICK_SOUND_PROFILE, ClickSoundProfile.APPLE_TV.id)
        val profile = ClickSoundProfile.fromId(profileId)
        playSound(context, profile.rawRes, volume = 0.85f)
    }

    fun playNavigation(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_NAVIGATION_CLICK_ENABLED, true)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastNavSoundTimestamp < NAV_SOUND_MIN_INTERVAL_MS) {
            return
        }
        lastNavSoundTimestamp = now

        playSound(context, R.raw.sound_nav_tick, volume = 0.55f)
    }

    fun playPlaybackAction(context: Context, action: PlaybackSoundAction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_PLAYBACK_SOUNDS_ENABLED, true)) return

        val rawRes = when (action) {
            PlaybackSoundAction.PLAY -> R.raw.sound_playback_play
            PlaybackSoundAction.PAUSE -> R.raw.sound_playback_pause
            PlaybackSoundAction.STOP -> R.raw.sound_playback_stop
        }
        playSound(context, rawRes, volume = 0.75f)
    }

    private fun playSound(context: Context, rawRes: Int, volume: Float = 0.8f) {
        if (soundPool == null) {
            init(context)
        }
        val soundId = soundIdMap[rawRes] ?: return
        try {
            soundPool?.play(soundId, volume, volume, 1, 0, 1.0f)
        } catch (e: Throwable) {
            Log.w(TAG, "Error playing sound res $rawRes: ${e.message}")
        }
    }

    fun isRemoteClickEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_REMOTE_CLICK_ENABLED, true)

    fun setRemoteClickEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_REMOTE_CLICK_ENABLED, enabled).apply()
        if (enabled) playClick(context)
    }

    fun isNavigationClickEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_NAVIGATION_CLICK_ENABLED, true)

    fun setNavigationClickEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_NAVIGATION_CLICK_ENABLED, enabled).apply()
        if (enabled) playNavigation(context)
    }

    fun isPlaybackSoundsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_PLAYBACK_SOUNDS_ENABLED, true)

    fun setPlaybackSoundsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_PLAYBACK_SOUNDS_ENABLED, enabled).apply()
        if (enabled) playPlaybackAction(context, PlaybackSoundAction.PLAY)
    }

    fun getClickSoundProfile(context: Context): ClickSoundProfile {
        val profileId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_CLICK_SOUND_PROFILE, ClickSoundProfile.APPLE_TV.id)
        return ClickSoundProfile.fromId(profileId)
    }

    fun setClickSoundProfile(context: Context, profile: ClickSoundProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_CLICK_SOUND_PROFILE, profile.id).apply()
        previewProfileSound(context, profile)
    }

    fun previewProfileSound(context: Context, profile: ClickSoundProfile) {
        playSound(context, profile.rawRes, volume = 0.85f)
    }

    fun release() {
        try {
            soundPool?.release()
            soundPool = null
            soundIdMap.clear()
            isLoaded = false
        } catch (_: Throwable) {}
    }
}
