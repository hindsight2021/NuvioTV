package com.nuvio.tv.core.ai

enum class AiVoicePersona(
    val id: String,
    val displayName: String,
    val description: String,
    val targetLocaleLanguage: String,
    val preferredVoiceKeywords: List<String>,
    val pitch: Float,
    val speechRate: Float
) {
    CHIC_CRITIC_BRITISH(
        id = "chic_british",
        displayName = "Chic Critic (British)",
        description = "Sophisticated, articulate film-critic tone with British inflection.",
        targetLocaleLanguage = "en",
        preferredVoiceKeywords = listOf("en-gb", "en_gb", "rjs", "gba", "fis", "gbc", "female_2", "british"),
        pitch = 0.98f,
        speechRate = 0.96f
    ),
    STUDIO_NATURAL_FEMALE(
        id = "studio_female",
        displayName = "Studio Natural (Female)",
        description = "Warm, clear cinematic narrator with natural intonation.",
        targetLocaleLanguage = "en",
        preferredVoiceKeywords = listOf("sfg", "tpf", "female", "en-us-x"),
        pitch = 1.0f,
        speechRate = 0.98f
    ),
    CINEMA_BARITONE_MALE(
        id = "cinema_baritone",
        displayName = "Cinema Baritone (Male)",
        description = "Deep, resonant movie-concierge voice.",
        targetLocaleLanguage = "en",
        preferredVoiceKeywords = listOf("iol", "iob", "iom", "male", "en-us-x"),
        pitch = 0.93f,
        speechRate = 0.96f
    ),
    SYSTEM_DEFAULT(
        id = "system_default",
        displayName = "System Default",
        description = "Standard system Text-to-Speech voice.",
        targetLocaleLanguage = "",
        preferredVoiceKeywords = emptyList(),
        pitch = 1.0f,
        speechRate = 1.0f
    );

    companion object {
        fun fromId(id: String?): AiVoicePersona {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CHIC_CRITIC_BRITISH
        }
    }
}
