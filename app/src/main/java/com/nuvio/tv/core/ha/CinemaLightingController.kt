package com.nuvio.tv.core.ha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CinemaLightingController @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isMovie = false
    private var isMovieLightingEnabled = true
    private var isFourDCinemaEnabled = true
    private var lastFourDCinemaTriggerMs = 0L

    fun configure(isMovie: Boolean, movieLightingEnabled: Boolean = true, fourDEnabled: Boolean = true) {
        this.isMovie = isMovie
        this.isMovieLightingEnabled = movieLightingEnabled
        this.isFourDCinemaEnabled = fourDEnabled
    }

    fun onPlaybackStarted() {
        if (!isMovie || !isMovieLightingEnabled) return
        scope.launch {
            callHaLightService(
                entityIds = listOf("light.family_room"),
                brightnessPct = 30,
                transition = 3
            )
        }
    }

    fun onPlaybackPaused() {
        if (!isMovie || !isMovieLightingEnabled) return
        scope.launch {
            callHaLightService(
                entityIds = listOf("light.family_room"),
                brightnessPct = 70,
                transition = 2
            )
        }
    }

    fun onPlaybackStopped() {
        if (!isMovie || !isMovieLightingEnabled) return
        scope.launch {
            callHaLightService(
                entityIds = listOf("light.family_room"),
                brightnessPct = 100,
                transition = 2
            )
        }
    }

    /**
     * 4D Cinema mood shift with fatigue guard (min 3 minutes between shifts)
     */
    fun triggerMood(mood: CinemaMood) {
        if (!isFourDCinemaEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastFourDCinemaTriggerMs < 180_000L) {
            // Guard against frequent changes to avoid distraction
            return
        }
        lastFourDCinemaTriggerMs = now

        scope.launch {
            val entities = listOf(
                "light.family_room",
                "light.tv_light2",
                "light.fire_light",
                "light.left_lamp",
                "light.right_lamp"
            )
            callHaLightService(
                entityIds = entities,
                brightnessPct = mood.brightnessPct,
                rgbColor = mood.rgbColor,
                transition = 3
            )
        }
    }

    private fun callHaLightService(
        entityIds: List<String>,
        brightnessPct: Int? = null,
        rgbColor: List<Int>? = null,
        transition: Int = 2
    ) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/services/light/turn_on")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $TOKEN")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
            }

            val body = JSONObject().apply {
                val array = JSONArray()
                entityIds.forEach { array.put(it) }
                put("entity_id", array)
                if (brightnessPct != null) {
                    put("brightness_pct", brightnessPct)
                }
                if (rgbColor != null && rgbColor.size == 3) {
                    val rgbArray = JSONArray()
                    rgbColor.forEach { rgbArray.put(it) }
                    put("rgb_color", rgbArray)
                }
                put("transition", transition)
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            Log.d(TAG, "HA light call returned code: $code for entities: $entityIds")
        } catch (e: Exception) {
            Log.w(TAG, "HA light call failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "CinemaLightingController"
        private const val BASE_URL = "http://192.168.1.105:8123"
        private const val TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiI2NTVkMzZmM2IzYmY0MmY1OGNjM2EyMzBjMmY3NWY0NiIsImlhdCI6MTc3NzY1MDM1NiwiZXhwIjoyMDkzMDEwMzU2fQ.s4JVciY2njEk6PSM2cCYv2m8k9z3nZ-GZmE9VPx8vI0"
    }
}

enum class CinemaMood(
    val displayName: String,
    val rgbColor: List<Int>,
    val brightnessPct: Int
) {
    STORM("Storm / Rain", listOf(45, 80, 150), 20),
    FIRE("Fire / Explosion", listOf(255, 95, 15), 35),
    SUSPENSE("Suspense / Red Alert", listOf(180, 20, 25), 20),
    SUNRISE("Dawn / Sunrise", listOf(255, 185, 90), 40),
    PARTY("Club / Vibrant", listOf(180, 40, 220), 30),
    AMBIENT_CINEMA("Cinema Standard", listOf(255, 215, 160), 25)
}
