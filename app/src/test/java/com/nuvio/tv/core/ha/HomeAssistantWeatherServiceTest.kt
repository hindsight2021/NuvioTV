package com.nuvio.tv.core.ha

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAssistantWeatherServiceTest {

    @Test
    fun `parseIndoorTemperature falls through unavailable sensor to indoor climate`() {
        val entities = sequenceOf(
            entity(
                entityId = "sensor.living_room_temperature",
                state = "unavailable"
            ),
            entity(
                entityId = "weather.indoor_climate",
                state = "clear-night",
                attributes = mapOf(
                    "temperature" to 23.8,
                    "temperature_unit" to "°C"
                )
            )
        )

        val result = HomeAssistantWeatherService.parseIndoorTemperature(entities)

        assertEquals("23.8°C", result)
    }

    @Test
    fun `parseIndoorTemperature falls through unavailable and unknown sensors to main floor average`() {
        val entities = sequenceOf(
            entity(
                entityId = "sensor.living_room_temperature",
                state = "unavailable"
            ),
            entity(
                entityId = "sensor.indoor_temperature",
                state = "unknown"
            ),
            entity(
                entityId = "sensor.main_floor_average_temperature",
                state = "23.85",
                attributes = mapOf("unit_of_measurement" to "C")
            )
        )

        val result = HomeAssistantWeatherService.parseIndoorTemperature(entities)

        assertEquals("23.9°C", result)
    }

    @Test
    fun `parseOutdoorWeather falls through unavailable weather entity to home weather station`() {
        val entities = sequenceOf(
            entity(
                entityId = "weather.ihome",
                state = "unavailable"
            ),
            entity(
                entityId = "weather.home_weather_station",
                state = "clear-night",
                attributes = mapOf("temperature" to -9.2)
            )
        )

        val result = HomeAssistantWeatherService.parseOutdoorWeather(entities)

        assertEquals("Clear", result.condition)
        assertEquals("🌙", result.conditionIcon)
        assertEquals("-9.2°C", result.outdoorTemp)
    }

    @Test
    fun `parseOutdoorTemperatureSensors skips unavailable and unknown sensors`() {
        val entities = sequenceOf(
            entity(
                entityId = "sensor.ihome_atlas_temperature",
                state = "unavailable"
            ),
            entity(
                entityId = "sensor.backup_outdoor_temperature",
                state = "unknown"
            ),
            entity(
                entityId = "sensor.average_outdoor_temperature",
                state = "-9.2",
                attributes = mapOf("unit_of_measurement" to "°C")
            )
        )

        val result = HomeAssistantWeatherService.parseOutdoorTemperatureSensors(entities)

        assertEquals("-9.2°C", result)
    }

    @Test
    fun `parseOutdoorTemperatureSensors returns empty string when no valid sensor exists`() {
        val entities = sequenceOf(
            entity(
                entityId = "sensor.ihome_atlas_temperature",
                state = "unavailable"
            ),
            entity(
                entityId = "sensor.average_outdoor_temperature",
                state = "unknown"
            )
        )

        val result = HomeAssistantWeatherService.parseOutdoorTemperatureSensors(entities)

        assertEquals("", result)
    }

    @Test
    fun `parseIndoorTemperature stops evaluating sequence once a valid entity is found`() {
        var invocations = 0

        val entities = sequence {
            invocations++
            yield(
                entity(
                    entityId = "sensor.living_room_temperature",
                    state = "unavailable"
                )
            )
            invocations++
            yield(
                entity(
                    entityId = "weather.indoor_climate",
                    state = "clear-night",
                    attributes = mapOf(
                        "temperature" to 23.8,
                        "temperature_unit" to "°C"
                    )
                )
            )
            invocations++
            yield(
                entity(
                    entityId = "sensor.main_floor_average_temperature",
                    state = "23.85",
                    attributes = mapOf("unit_of_measurement" to "C")
                )
            )
        }

        val result = HomeAssistantWeatherService.parseIndoorTemperature(entities)

        assertEquals("23.8°C", result)
        assertEquals(2, invocations)
    }

    @Test
    fun `parseOutdoorTemperatureSensors stops evaluating sequence once a valid sensor is found`() {
        var invocations = 0

        val entities = sequence {
            invocations++
            yield(
                entity(
                    entityId = "sensor.ihome_atlas_temperature",
                    state = "unavailable"
                )
            )
            invocations++
            yield(
                entity(
                    entityId = "sensor.average_outdoor_temperature",
                    state = "-9.2",
                    attributes = mapOf("unit_of_measurement" to "°C")
                )
            )
            invocations++
            yield(
                entity(
                    entityId = "sensor.other_outdoor_temperature",
                    state = "-1.0",
                    attributes = mapOf("unit_of_measurement" to "°C")
                )
            )
        }

        val result = HomeAssistantWeatherService.parseOutdoorTemperatureSensors(entities)

        assertEquals("-9.2°C", result)
        assertEquals(2, invocations)
    }

    private fun entity(
        entityId: String,
        state: String,
        attributes: Map<String, Any?> = emptyMap()
    ): JSONObject {
        val attributesJson = JSONObject()
        attributes.forEach { (key, value) -> attributesJson.put(key, value) }

        return JSONObject().apply {
            put("entity_id", entityId)
            put("state", state)
            put("attributes", attributesJson)
        }
    }
}
