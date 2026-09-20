package com.nuvio.tv.core.ai

import android.content.Context
import android.content.SharedPreferences

class AiPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var activeProvider: AiProvider
        get() {
            val name = prefs.getString(KEY_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
            return runCatching { AiProvider.valueOf(name) }.getOrDefault(AiProvider.GEMINI)
        }
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.name).apply()

    fun getApiKey(provider: AiProvider): String {
        return prefs.getString("${KEY_API_KEY_PREFIX}${provider.name}", "").orEmpty()
    }

    fun setApiKey(provider: AiProvider, key: String) {
        prefs.edit().putString("${KEY_API_KEY_PREFIX}${provider.name}", key.trim()).apply()
    }

    fun getModel(provider: AiProvider): String {
        val stored = prefs.getString("${KEY_MODEL_PREFIX}${provider.name}", provider.defaultModel)
            ?.ifBlank { provider.defaultModel } ?: provider.defaultModel
        if (provider == AiProvider.GEMINI && (stored == "gemini-2.0-flash" || stored == "gemini-2.0-flash-exp")) {
            return provider.defaultModel
        }
        return stored
    }

    fun setModel(provider: AiProvider, model: String) {
        prefs.edit().putString("${KEY_MODEL_PREFIX}${provider.name}", model.trim()).apply()
    }

    var isTtsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var isAiSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SEARCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_SEARCH_ENABLED, value).apply()

    var ttsVoicePersona: AiVoicePersona
        get() {
            val id = prefs.getString(KEY_TTS_VOICE_PERSONA, AiVoicePersona.CHIC_CRITIC_BRITISH.id)
            return AiVoicePersona.fromId(id)
        }
        set(value) = prefs.edit().putString(KEY_TTS_VOICE_PERSONA, value.id).apply()

    var customPersona: String
        get() = prefs.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    fun hasAnyApiKey(): Boolean {
        return AiProvider.entries.any { getApiKey(it).isNotBlank() }
    }

    fun isConfigured(provider: AiProvider = activeProvider): Boolean {
        return getApiKey(provider).isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "nuvio_ai_prefs"
        private const val KEY_PROVIDER = "ai_active_provider"
        private const val KEY_API_KEY_PREFIX = "ai_api_key_"
        private const val KEY_MODEL_PREFIX = "ai_model_"
        private const val KEY_AI_SEARCH_ENABLED = "ai_search_enabled"
        private const val KEY_TTS_ENABLED = "ai_tts_enabled"
        private const val KEY_TTS_VOICE_PERSONA = "ai_tts_voice_persona"
        private const val KEY_PERSONA = "ai_persona"

        const val DEFAULT_PERSONA =
            "You are Nuvio+ Cinema AI, a brilliant, witty, and concise movie and TV concierge on Android TV. " +
            "Provide conversational commentary, curated recommendations, and engaging follow-up questions."
    }
}
