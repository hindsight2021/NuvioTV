package com.nuvio.tv.core.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HomeAssistantWeather(
    val condition: String = "",
    val conditionIcon: String = "",
    val outdoorTemp: String = "",
    val indoorTemp: String = "",
    val isAvailable: Boolean = false
)

object HomeAssistantWeatherService {
    private const val BASE_URL = "http://192.168.1.105:8123"
    private const val TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiI2NTVkMzZmM2IzYmY0MmY1OGNjM2EyMzBjMmY3NWY0NiIsImlhdCI6MTc3NzY1MDM1NiwiZXhwIjoyMDkzMDEwMzU2fQ.s4JVciY2njEk6PSM2cCYv2m8k9z3nZ-GZmE9VPx8vI0"

    private val _weatherState = MutableStateFlow(HomeAssistantWeather())
    val weatherState: StateFlow<HomeAssistantWeather> = _weatherState.asStateFlow()

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            try {
                // 1. Fetch outdoor weather: try weather.ihome, then weather.home_weather_station
                val weatherJson = fetchEntity("weather.ihome")
                    ?: fetchEntity("weather.home_weather_station")

                var condition = ""
                var conditionIcon = ""
                var outdoorTemp = ""

                if (weatherJson != null) {
                    val rawState = weatherJson.optString("state", "")
                    val (cText, cIcon) = mapCondition(rawState)
                    condition = cText
                    conditionIcon = cIcon

                    val attributes = weatherJson.optJSONObject("attributes")
                    val temp = attributes?.optDouble("temperature", Double.NaN)
                    val unit = attributes?.optString("temperature_unit", "°C")?.takeIf { it.isNotBlank() } ?: "°C"
                    if (temp != null && !temp.isNaN()) {
                        outdoorTemp = "${temp.formatOneDecimal()}$unit"
                    }
                }

                // If outdoor temp still missing, fallback to sensor.ihome_atlas_temperature
                if (outdoorTemp.isBlank()) {
                    val sensorJson = fetchEntity("sensor.ihome_atlas_temperature")
                    if (sensorJson != null) {
                        val stateStr = sensorJson.optString("state", "")
                        val temp = stateStr.toDoubleOrNull()
                        val unit = sensorJson.optJSONObject("attributes")?.optString("unit_of_measurement", "°C") ?: "°C"
                        if (temp != null) {
                            outdoorTemp = "${temp.formatOneDecimal()}$unit"
                        }
                    }
                }

                // 2. Fetch indoor temperature: try sensor.living_room_temperature, then climate / indoor sensors
                var indoorTemp = ""
                val indoorSensor = fetchEntity("sensor.living_room_temperature")
                    ?: fetchEntity("weather.indoor_climate")
                    ?: fetchEntity("sensor.indoor_temperature")

                if (indoorSensor != null) {
                    val attributes = indoorSensor.optJSONObject("attributes")
                    val tempFromAttr = attributes?.optDouble("current_temperature", Double.NaN)
                    val unit = attributes?.optString("unit_of_measurement")
                        ?: attributes?.optString("temperature_unit", "°C")
                        ?: "°C"

                    if (tempFromAttr != null && !tempFromAttr.isNaN()) {
                        indoorTemp = "${tempFromAttr.formatOneDecimal()}$unit"
                    } else {
                        val stateStr = indoorSensor.optString("state", "")
                        val tempFromState = stateStr.toDoubleOrNull()
                        if (tempFromState != null) {
                            indoorTemp = "${tempFromState.formatOneDecimal()}$unit"
                        }
                    }
                }

                val hasData = outdoorTemp.isNotBlank() || indoorTemp.isNotBlank() || condition.isNotBlank()
                if (hasData) {
                    _weatherState.value = HomeAssistantWeather(
                        condition = condition,
                        conditionIcon = conditionIcon,
                        outdoorTemp = outdoorTemp,
                        indoorTemp = indoorTemp,
                        isAvailable = true
                    )
                }
            } catch (_: Exception) {
                // Silently keep last state if temporary network hiccup
            }
        }
    }

    private fun fetchEntity(entityId: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$BASE_URL/api/states/$entityId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $TOKEN")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(responseText)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun Double.formatOneDecimal(): String {
        return String.format(java.util.Locale.US, "%.1f", this)
    }

    private fun mapCondition(rawState: String?): Pair<String, String> {
        val clean = rawState?.lowercase()?.replace("-", "")?.replace("_", "") ?: ""
        return when {
            clean.contains("clearnight") -> "Clear" to "🌙"
            clean.contains("partlycloudy") -> "Partly Cloudy" to "⛅"
            clean.contains("cloudy") -> "Cloudy" to "☁️"
            clean.contains("fog") -> "Foggy" to "🌫️"
            clean.contains("hail") -> "Hail" to "🌨️"
            clean.contains("lightningrainy") || clean.contains("thunderstorm") -> "Storms" to "⛈️"
            clean.contains("lightning") -> "Thunder" to "🌩️"
            clean.contains("pouring") -> "Heavy Rain" to "🌧️"
            clean.contains("rain") -> "Rain" to "🌧️"
            clean.contains("snowyrainy") -> "Sleet" to "🌨️"
            clean.contains("snow") -> "Snow" to "❄️"
            clean.contains("sunny") || clean.contains("clear") -> "Sunny" to "☀️"
            clean.contains("windy") -> "Windy" to "💨"
            else -> (rawState?.replaceFirstChar { it.uppercase() } ?: "") to "⛅"
        }
    }
}
