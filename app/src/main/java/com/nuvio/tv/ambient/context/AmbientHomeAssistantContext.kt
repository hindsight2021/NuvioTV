package com.nuvio.tv.ambient.context

import com.nuvio.tv.ambient.AmbientChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates weather and time context to recommend ambient channels
 * and determine environmental ambiance.
 */
@Singleton
class AmbientHomeAssistantContext @Inject constructor(
    val weatherContext: AmbientWeatherContext,
    val timeContext: AmbientTimeContext
) {

    fun getRecommendedChannels(): List<AmbientChannel> {
        if (weatherContext.isSnowing() || weatherContext.isCold()) {
            return listOf(
                AmbientChannel.FIREPLACE,
                AmbientChannel.WEATHER,
                AmbientChannel.AERIAL
            )
        }

        if (weatherContext.isRaining()) {
            return listOf(
                AmbientChannel.WEATHER,
                AmbientChannel.FIREPLACE,
                AmbientChannel.CITIES
            )
        }

        val timeBucketName = timeContext.getCurrentTimeBucket().name

        if (timeBucketName.contains("NIGHT") || timeBucketName.contains("OVERNIGHT")) {
            return listOf(
                AmbientChannel.AFTER_DARK,
                AmbientChannel.SPACE,
                AmbientChannel.FIREPLACE
            )
        }

        if (timeBucketName.contains("GOLDEN")) {
            return listOf(
                AmbientChannel.AERIAL,
                AmbientChannel.NATURE,
                AmbientChannel.CITIES
            )
        }

        return listOf(
            AmbientChannel.AERIAL,
            AmbientChannel.NATURE,
            AmbientChannel.CITIES
        )
    }

    fun isDarkEnvironment(): Boolean {
        val timeBucketName = timeContext.getCurrentTimeBucket().name
        val isNight = timeBucketName.contains("NIGHT") || timeBucketName.contains("OVERNIGHT")
        return isNight
    }
}
