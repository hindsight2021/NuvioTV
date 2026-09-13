package com.nuvio.tv.core.ai

import kotlinx.serialization.Serializable

enum class AiProvider(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-3.6-flash"),
    OPENAI("OpenAI", "gpt-4o-mini"),
    ANTHROPIC("Anthropic Claude", "claude-3-5-haiku-20241022"),
    GROK("xAI Grok", "grok-2-latest"),
    OPENROUTER("OpenRouter", "google/gemini-2.0-flash-001")
}

@Serializable
data class AiRecommendationItem(
    val title: String,
    val year: Int? = null,
    val type: String = "movie",
    val rationale: String? = null
)

@Serializable
data class AiResponse(
    val spokenResponse: String,
    val catalogTitle: String = "AI Recommendations",
    val suggestedQuestions: List<String> = emptyList(),
    val recommendations: List<AiRecommendationItem> = emptyList()
)

data class AiChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String
)
