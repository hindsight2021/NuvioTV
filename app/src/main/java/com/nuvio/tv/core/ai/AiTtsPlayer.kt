package com.nuvio.tv.core.ai

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AiTtsPlayer(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val prefs = AiPreferences(context.applicationContext)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
                applyPersona(prefs.ttsVoicePersona)
            } else {
                Log.w(TAG, "Failed to initialize TextToSpeech: status=$status")
            }
        }
    }

    fun applyPersona(persona: AiVoicePersona) {
        val engine = tts ?: return
        if (persona == AiVoicePersona.SYSTEM_DEFAULT) {
            engine.language = Locale.getDefault()
            engine.setPitch(1.0f)
            engine.setSpeechRate(1.0f)
            return
        }

        val availableVoices = runCatching { engine.voices }.getOrNull()
        if (!availableVoices.isNullOrEmpty()) {
            val installedVoices = availableVoices.filter { voice ->
                val notInstalled = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
                !notInstalled
            }

            val bestVoice = installedVoices.maxByOrNull { voice ->
                var score = 0
                val voiceName = voice.name.lowercase(Locale.ROOT)
                val voiceLang = voice.locale.language.lowercase(Locale.ROOT)
                val voiceCountry = voice.locale.country.lowercase(Locale.ROOT)

                // High-definition quality tier
                when (voice.quality) {
                    Voice.QUALITY_VERY_HIGH -> score += 600
                    Voice.QUALITY_HIGH -> score += 400
                    Voice.QUALITY_NORMAL -> score += 150
                    else -> score += 50
                }

                // Prefer zero-latency local speech models over cloud network models
                if (!voice.isNetworkConnectionRequired) {
                    score += 200
                }

                // Google Neural / Wavenet voices have "-x-" in their identifier
                if (voiceName.contains("-x-")) {
                    score += 300
                }

                // Match persona keywords (e.g. British identifiers or US studio female/male)
                for (keyword in persona.preferredVoiceKeywords) {
                    if (voiceName.contains(keyword) || voiceCountry.contains(keyword)) {
                        score += 500
                    }
                }

                // Language match
                if (persona.targetLocaleLanguage.isNotEmpty() && voiceLang == persona.targetLocaleLanguage) {
                    score += 200
                }

                score
            }

            if (bestVoice != null) {
                try {
                    engine.voice = bestVoice
                    Log.d(TAG, "Configured TTS voice for ${persona.name}: ${bestVoice.name} (quality=${bestVoice.quality}, local=${!bestVoice.isNetworkConnectionRequired})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set voice: ${bestVoice.name}", e)
                    engine.language = if (persona.targetLocaleLanguage.isNotEmpty()) Locale(persona.targetLocaleLanguage) else Locale.getDefault()
                }
            } else {
                engine.language = if (persona.targetLocaleLanguage.isNotEmpty()) Locale(persona.targetLocaleLanguage) else Locale.getDefault()
            }
        } else {
            engine.language = if (persona.targetLocaleLanguage.isNotEmpty()) Locale(persona.targetLocaleLanguage) else Locale.getDefault()
        }

        engine.setPitch(persona.pitch)
        engine.setSpeechRate(persona.speechRate)
    }

    fun speak(text: String, persona: AiVoicePersona? = null) {
        if (!isInitialized || text.isBlank()) return
        stop()
        val activePersona = persona ?: prefs.ttsVoicePersona
        applyPersona(activePersona)
        val cleanText = sanitizeForSpeech(text)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "nuvio_ai_${System.currentTimeMillis()}")
    }

    fun speakChic(text: String, persona: AiVoicePersona? = null) {
        if (!isInitialized || text.isBlank()) return
        stop()
        val activePersona = persona ?: prefs.ttsVoicePersona
        applyPersona(activePersona)
        val cleanText = sanitizeForSpeech(text)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "nuvio_chic_${System.currentTimeMillis()}")
    }

    fun previewPersona(persona: AiVoicePersona, sampleText: String? = null) {
        if (!isInitialized) return
        stop()
        applyPersona(persona)
        val text = sampleText ?: "Now presenting the critic's verdict for Nuvio Plus."
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "nuvio_preview_${System.currentTimeMillis()}")
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            // Remove markdown headers, bold, italics, code
            .replace(Regex("[*#_`~>|\\[\\]]"), "")
            // Remove emojis (preserve letters, digits, punctuation, whitespace)
            .replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), " ")
            // Smooth out fractions like 8.5/10 to 8.5 out of 10
            .replace(Regex("(\\d+(?:\\.\\d+)?)/10"), "$1 out of 10")
            // Convert long dashes and colons to natural pauses
            .replace("—", ", ")
            .replace("--", ", ")
            .replace(":", ", ")
            // Normalize spaces
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    fun shutdown() = release()

    companion object {
        private const val TAG = "AiTtsPlayer"
    }
}
