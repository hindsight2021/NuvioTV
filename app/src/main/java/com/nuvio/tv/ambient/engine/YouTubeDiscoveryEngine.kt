package com.nuvio.tv.ambient.engine

import com.nuvio.tv.ambient.AmbientCandidate
import com.nuvio.tv.ambient.AmbientChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides categorized, optimized YouTube search queries for each [AmbientChannel].
 */
@Singleton
class YouTubeDiscoveryEngine @Inject constructor() {

    private val queriesByChannel: Map<AmbientChannel, List<String>> = mapOf(
        AmbientChannel.AERIAL to listOf(
            "Scenic Relaxation 4K HDR Alps",
            "Norway 4K 60fps drone fjords",
            "New Zealand 4K aerial drone",
            "Iceland 4K drone waterfalls",
            "Dolomites 4K aerial drone"
        ),
        AmbientChannel.CITIES to listOf(
            "Tokyo 4K HDR night walk 60fps",
            "New York 4K rain walk binaural",
            "London 4K HDR walking tour evening",
            "Shibuya 4K HDR night walk",
            "Paris 4K HDR evening walk"
        ),
        AmbientChannel.AFTER_DARK to listOf(
            "Tokyo midnight neon rain 4K HDR",
            "Cyberpunk city night walk 4K HDR",
            "Night drive city rain 4K",
            "Nocturnal city skylines 4K HDR"
        ),
        AmbientChannel.NATURE to listOf(
            "Scenic Relaxation 4K HDR nature",
            "Canadian Rockies 4K lake louise nature",
            "Coral reef underwater 4K HDR",
            "Deep forest redwoods 4K relaxation",
            "Ocean waves 4K HDR relaxation"
        ),
        AmbientChannel.SPACE to listOf(
            "NASA ISS Earth from space 4K ultra hd",
            "Sen SpaceTV ISS 4K live from space",
            "Aurora Borealis from space 4K ISS",
            "Solar Dynamics Observatory 4K NASA Sun",
            "James Webb deep space 4K visualization"
        ),
        AmbientChannel.WEATHER to listOf(
            "Nomadic Ambience 4K rain walk",
            "Heavy rain on window 4K HDR",
            "Blizzard in mountain cabin 4K falling snow",
            "Thunderstorm distant lightning 4K"
        ),
        AmbientChannel.FIREPLACE to listOf(
            "Fireplace 4K 10 hours crackling fire",
            "Cozy fireplace snow outside window 4K HDR",
            "Luxury lodge fireplace 4K ambient flames",
            "FOBOS PLANET Fireplace 4K"
        ),
        AmbientChannel.CINEMA to listOf(
            "Cinematic cosmic travel 4K",
            "Blade runner neon aesthetic 4K ambient",
            "Cinematic nature landscape 4K film"
        )
    )

    fun getQueriesForChannel(channel: AmbientChannel): List<String> =
        queriesByChannel[channel].orEmpty()

    fun getRandomQuery(channel: AmbientChannel): String =
        queriesByChannel[channel]?.randomOrNull().orEmpty()
}
