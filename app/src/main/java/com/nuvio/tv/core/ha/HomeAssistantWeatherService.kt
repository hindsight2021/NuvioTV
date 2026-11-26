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
    private val BASE_URL: String = com.nuvio.tv.BuildConfig.HOME_ASSISTANT_BASE_URL
    private val TOKEN: String = com.nuvio.tv.BuildConfig.HOME_ASSISTANT_TOKEN

    private val _weatherState = MutableStateFlow(HomeAssistantWeather())
    val weatherState: StateFlow<HomeAssistantWeather> = _weatherState.asStateFlow()

    private val OUTDOOR_WEATHER_ENTITIES = listOf(
        "weather.ihome",
        "weather.home_weather_station",
        "weather.fredericton"
    )

    private val OUTDOOR_TEMP_SENSORS = listOf(
        "sensor.ihome_atlas_temperature",
        "sensor.average_outdoor_temperature"
    )

    private val INDOOR_ENTITIES = listOf(
        "sensor.living_room_temperature",
        "weather.indoor_climate",
        "sensor.main_floor_average_temperature",
        "sensor.average_main_floor_temperature",
        "sensor.main_floor_ac_current_temperature",
        "sensor.indoor_temperature"
    )

    internal data class OutdoorWeatherResult(
        val condition: String = "",
        val conditionIcon: String = "",
        val outdoorTemp: String = ""
    )

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            if (BASE_URL.isBlank() || TOKEN.isBlank()) {
                _weatherState.value = HomeAssistantWeather(isAvailable = false)
                return@withContext
            }
            try {
                // 1. Lazily fetch outdoor weather in priority order, skipping unavailable/unknown entities
                val outdoor = parseOutdoorWeather(
                    OUTDOOR_WEATHER_ENTITIES.asSequence().mapNotNull { fetchEntity(it) }
                )

                val condition = outdoor.condition
                val conditionIcon = outdoor.conditionIcon
                var outdoorTemp = outdoor.outdoorTemp

                // If outdoor temp still missing, fallback to outdoor temperature sensors
                if (outdoorTemp.isBlank()) {
                    outdoorTemp = parseOutdoorTemperatureSensors(
                        OUTDOOR_TEMP_SENSORS.asSequence().mapNotNull { fetchEntity(it) }
                    )
                }

                // 2. Lazily fetch indoor temperature in priority order, skipping unavailable/unknown sensors
                val indoorTemp = parseIndoorTemperature(
                    INDOOR_ENTITIES.asSequence().mapNotNull { fetchEntity(it) }
                )

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

    internal fun isValidState(state: String?): Boolean {
        if (state == null) return false
        val trimmed = state.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        return lower != "unavailable" && lower != "unknown" && lower != "none"
    }

    internal fun parseOutdoorWeather(entities: Sequence<JSONObject>): OutdoorWeatherResult {
        for (entity in entities) {
            val rawState = entity.optString("state", "")
            if (rawState.trim().equals("unavailable", ignoreCase = true)) continue

            val attributes = entity.optJSONObject("attributes")
            val temp = attributes?.optDouble("temperature", Double.NaN) ?: Double.NaN
            val hasNumericTemp = !temp.isNaN()

            val validConditionState = isValidState(rawState)
            val (cText, cIcon) = if (validConditionState) mapCondition(rawState) else ("" to "")
            val hasCondition = cText.isNotBlank()

            if (!hasCondition && !hasNumericTemp) continue

            val outdoorTemp = if (hasNumericTemp) {
                val unit = normalizeUnit(attributes?.optString("temperature_unit", "°C"))
                "${temp.formatOneDecimal()}$unit"
            } else {
                ""
            }

            return OutdoorWeatherResult(
                condition = cText,
                conditionIcon = cIcon,
                outdoorTemp = outdoorTemp
            )
        }
        return OutdoorWeatherResult()
    }

    internal fun parseOutdoorTemperatureSensors(entities: Sequence<JSONObject>): String {
        for (entity in entities) {
            val stateStr = entity.optString("state", "")
            if (!isValidState(stateStr)) continue
            val temp = stateStr.trim().toDoubleOrNull() ?: continue
            val unit = normalizeUnit(entity.optJSONObject("attributes")?.optString("unit_of_measurement", "°C"))
            return "${temp.formatOneDecimal()}$unit"
        }
        return ""
    }

    internal fun parseIndoorTemperature(entities: Sequence<JSONObject>): String {
        for (entity in entities) {
            val rawState = entity.optString("state", "")
            if (rawState.trim().equals("unavailable", ignoreCase = true)) continue

            val attributes = entity.optJSONObject("attributes")
            var tempFromAttr = attributes?.optDouble("current_temperature", Double.NaN) ?: Double.NaN
            if (tempFromAttr.isNaN()) {
                tempFromAttr = attributes?.optDouble("temperature", Double.NaN) ?: Double.NaN
            }

            val unit = normalizeUnit(
                attributes?.optString("unit_of_measurement")?.takeIf { it.isNotBlank() }
                    ?: attributes?.optString("temperature_unit", "°C")
            )

            if (!tempFromAttr.isNaN()) {
                return "${tempFromAttr.formatOneDecimal()}$unit"
            }

            if (isValidState(rawState)) {
                val tempFromState = rawState.trim().toDoubleOrNull()
                if (tempFromState != null) {
                    return "${tempFromState.formatOneDecimal()}$unit"
                }
            }
        }
        return ""
    }

    private fun normalizeUnit(rawUnit: String?): String {
        val cleaned = rawUnit?.trim().orEmpty()
        return when {
            cleaned.isBlank() -> "°C"
            cleaned.equals("C", ignoreCase = true) -> "°C"
            cleaned.equals("F", ignoreCase = true) -> "°F"
            else -> cleaned
        }
    }

    private fun fetchEntity(entityId: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val cleanBaseUrl = BASE_URL.trimEnd('/')
            val url = URL("$cleanBaseUrl/api/states/$entityId")
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
