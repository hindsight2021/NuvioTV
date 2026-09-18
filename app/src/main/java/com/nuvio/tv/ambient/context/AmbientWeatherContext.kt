package com.nuvio.tv.ambient.context

import com.nuvio.tv.core.ha.HomeAssistantWeather
import com.nuvio.tv.core.ha.HomeAssistantWeatherService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides ambient weather context derived from [HomeAssistantWeatherService].
 */
@Singleton
class AmbientWeatherContext @Inject constructor(
    private val weatherService: HomeAssistantWeatherService
) {

    val weatherState: StateFlow<HomeAssistantWeather> = weatherService.weatherState

    /**
     * Returns a list of semantic tags describing the current weather condition.
     */
    fun getCurrentWeatherTags(): List<String> {
        val condition = weatherService.weatherState.value.condition.lowercase()

        return when {
            condition.contains("rain") || condition.contains("pouring") ->
                listOf("rain", "storm", "wet")

            condition.contains("thunder") || condition.contains("lightning") ->
                listOf("thunderstorm", "lightning", "storm", "rain")

            condition.contains("snow") ||
                condition.contains("sleet") ||
                condition.contains("hail") ->
                listOf("snow", "blizzard", "cold", "winter")

            condition.contains("fog") || condition.contains("mist") ->
                listOf("fog", "mist", "moody")

            condition.contains("cloudy") || condition.contains("clouds") ->
                listOf("clouds", "overcast")

            condition.contains("clear") || condition.contains("sunny") ->
                listOf("clear", "sunny", "bright")

            condition.contains("wind") ->
                listOf("windy", "breeze")

            else -> listOf("clear")
        }
    }

    fun isRaining(): Boolean {
        val condition = weatherService.weatherState.value.condition.lowercase()
        return condition.contains("rain") || condition.contains("pouring")
    }

    fun isSnowing(): Boolean {
        val condition = weatherService.weatherState.value.condition.lowercase()
        return condition.contains("snow") ||
            condition.contains("sleet") ||
            condition.contains("hail")
    }

    fun isCold(): Boolean {
        val weather = weatherService.weatherState.value
        val condition = weather.condition.lowercase()

        val isSnowyCondition = condition.contains("snow") ||
            condition.contains("sleet") ||
            condition.contains("hail") ||
            condition.contains("winter")

        val tempNum = weather.outdoorTemp
            .replace("°C", "")
            .replace("°F", "")
            .trim()
            .toDoubleOrNull()
        val isLowTemperature = tempNum != null && tempNum <= 5.0

        return isSnowyCondition || isLowTemperature
    }

    suspend fun refresh() {
        weatherService.refresh()
    }
}
