package com.nuvio.tv.core.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.tv.data.repository.SkipInterval
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnhancedIntroDetector @Inject constructor() {
    private val memoryCache = ConcurrentHashMap<String, SkipInterval>()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("nuvio_learned_intros", Context.MODE_PRIVATE)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        prefs?.all?.forEach { (key, value) ->
            if (value is String) {
                val parts = value.split(",")
                if (parts.size >= 2) {
                    val start = parts[0].toDoubleOrNull()
                    val end = parts[1].toDoubleOrNull()
                    if (start != null && end != null) {
                        memoryCache[key] = SkipInterval(start, end, "intro", "smart_detector")
                    }
                }
            }
        }
    }

    fun getLearnedIntro(showKey: String, season: Int): SkipInterval? {
        val key = "$showKey:$season"
        return memoryCache[key]
    }

    fun learnIntro(showKey: String, season: Int, startSec: Double, endSec: Double) {
        if (endSec <= startSec || (endSec - startSec) < 10.0 || (endSec - startSec) > 90.0) return
        val key = "$showKey:$season"
        val interval = SkipInterval(startSec, endSec, "intro", "smart_detector")
        memoryCache[key] = interval
        prefs?.edit()?.putString(key, "$startSec,$endSec")?.apply()
        Log.d(TAG, "Learned intro for $key: ${startSec}s to ${endSec}s")
    }

    /**
     * Inspects subtitle cue text and timestamps to detect theme song segments in the first 6 minutes.
     */
    fun detectFromSubtitleCues(
        cues: List<SubtitleCueData>,
        showKey: String? = null,
        season: Int? = null
    ): SkipInterval? {
        // Look within first 360 seconds
        val introWindowCues = cues.filter { it.startSec in 0.0..360.0 }
        val musicCues = introWindowCues.filter { isThemeMusicCue(it.text) }

        if (musicCues.isEmpty()) return null

        // Find contiguous block of theme music cues
        var blockStart = musicCues.first().startSec
        var blockEnd = musicCues.first().endSec

        for (i in 1 until musicCues.size) {
            val cue = musicCues[i]
            // If gap between music cues is less than 8 seconds, keep expanding
            if (cue.startSec - blockEnd <= 8.0) {
                blockEnd = maxOf(blockEnd, cue.endSec)
            } else {
                val duration = blockEnd - blockStart
                if (duration in 12.0..65.0) {
                    val interval = SkipInterval(blockStart, blockEnd, "intro", "smart_detector")
                    if (showKey != null && season != null) {
                        learnIntro(showKey, season, blockStart, blockEnd)
                    }
                    return interval
                }
                blockStart = cue.startSec
                blockEnd = cue.endSec
            }
        }

        val finalDuration = blockEnd - blockStart
        if (finalDuration in 12.0..65.0) {
            val interval = SkipInterval(blockStart, blockEnd, "intro", "smart_detector")
            if (showKey != null && season != null) {
                learnIntro(showKey, season, blockStart, blockEnd)
            }
            return interval
        }

        return null
    }

    private fun isThemeMusicCue(text: String): Boolean {
        val lower = text.lowercase()
        val hasMusicNote = text.contains("♪") || text.contains("♫") || text.contains("♩")
        val isThemeKeyword = lower.contains("theme") ||
            lower.contains("intro") ||
            lower.contains("opening") ||
            lower.contains("title music") ||
            lower.contains("theme song")
        return (hasMusicNote && isThemeKeyword) ||
            lower.contains("[theme music") ||
            lower.contains("[theme song") ||
            lower.contains("(theme music") ||
            lower.contains("(theme song")
    }

    companion object {
        private const val TAG = "EnhancedIntroDetector"
    }
}

data class SubtitleCueData(
    val startSec: Double,
    val endSec: Double,
    val text: String
)
