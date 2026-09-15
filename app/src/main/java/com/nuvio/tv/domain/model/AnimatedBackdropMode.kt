package com.nuvio.tv.domain.model

enum class AnimatedBackdropMode(val displayName: String, val description: String) {
    OFF(
        displayName = "Off (Static)",
        description = "Standard still artwork without motion."
    ),
    KEN_BURNS(
        displayName = "Cinematic Drift (Ken Burns)",
        description = "Slow, sweeping pan and zoom drift across high-res artwork (Apple TV style)."
    ),
    AMBIENT_BREATHE(
        displayName = "Ambient Breathe",
        description = "Gentle rhythmic breathing scale pulse."
    ),
    CINEMATIC_FLOW(
        displayName = "Cinematic Flow (Drift & Breathe)",
        description = "Continuous sweeping motion combined with ambient breathing depth."
    );

    companion object {
        fun fromName(name: String?): AnimatedBackdropMode {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
        }
    }
}
