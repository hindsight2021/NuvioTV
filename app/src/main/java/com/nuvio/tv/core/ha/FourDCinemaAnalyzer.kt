package com.nuvio.tv.core.ha

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FourDCinemaAnalyzer @Inject constructor(
    private val cinemaLightingController: CinemaLightingController
) {
    fun inspectSubtitleText(text: String) {
        val lower = text.lowercase()
        val detectedMood = when {
            lower.contains("thunder") || lower.contains("lightning") || lower.contains("heavy rain") ||
                lower.contains("storm is coming") || lower.contains("pouring rain") -> CinemaMood.STORM

            lower.contains("explosion") || lower.contains("bomb") || lower.contains("on fire") ||
                lower.contains("it's gonna blow") || lower.contains("flames") || lower.contains("grenade") -> CinemaMood.FIRE

            lower.contains("red alert") || lower.contains("we're surrounded") || lower.contains("hostage") ||
                lower.contains("they found us") || lower.contains("containment breach") -> CinemaMood.SUSPENSE

            lower.contains("sunrise") || lower.contains("new day") || lower.contains("sun's coming up") -> CinemaMood.SUNRISE

            lower.contains("nightclub") || lower.contains("dance floor") || lower.contains("rave") -> CinemaMood.PARTY

            else -> null
        }

        detectedMood?.let { mood ->
            cinemaLightingController.triggerMood(mood)
        }
    }
}
