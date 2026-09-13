package com.nuvio.tv.core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiManager @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val aiHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun query(
        context: Context,
        prompt: String,
        history: List<AiChatMessage> = emptyList()
    ): Result<AiResponse> = withContext(Dispatchers.IO) {
        val prefs = AiPreferences(context)
        val provider = prefs.activeProvider
        val apiKey = prefs.getApiKey(provider)
        val model = prefs.getModel(provider)

        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Please configure an API key for ${provider.displayName} in Settings -> AI Assistant")
            )
        }

        val systemPrompt = buildSystemPrompt(prefs.customPersona)

        try {
            val rawJson = when (provider) {
                AiProvider.GEMINI -> callGemini(apiKey, model, systemPrompt, prompt, history)
                AiProvider.OPENAI -> callOpenAiCompatible(
                    url = "https://api.openai.com/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    prompt = prompt,
                    history = history
                )
                AiProvider.ANTHROPIC -> callAnthropic(apiKey, model, systemPrompt, prompt, history)
                AiProvider.GROK -> callOpenAiCompatible(
                    url = "https://api.x.ai/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    prompt = prompt,
                    history = history
                )
                AiProvider.OPENROUTER -> callOpenAiCompatible(
                    url = "https://openrouter.ai/api/v1/chat/completions",
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    prompt = prompt,
                    history = history,
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://nuvio.tv",
                        "X-Title" to "Nuvio+ TV"
                    )
                )
            }

            val parsed = parseAiResponse(rawJson)
            Result.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "AI query failed for provider $provider: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun callGemini(
        apiKey: String,
        model: String,
        systemPrompt: String,
        prompt: String,
        history: List<AiChatMessage>
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val root = JSONObject()
        root.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))

        val contents = JSONArray()
        for (msg in history) {
            val geminiRole = if (msg.role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        })
        root.put("contents", contents)

        val generationConfig = JSONObject().apply {
            put("responseMimeType", "application/json")
            put("temperature", 0.7)
        }
        root.put("generationConfig", generationConfig)

        val request = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = aiHttpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Gemini API error (${response.code}): $responseBody")
        }

        val jsonResp = JSONObject(responseBody)
        val candidates = jsonResp.optJSONArray("candidates")
            ?: throw IllegalStateException("No candidates returned from Gemini")
        val candidate = candidates.getJSONObject(0)
        val text = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return text
    }

    private fun callOpenAiCompatible(
        url: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        prompt: String,
        history: List<AiChatMessage>,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val root = JSONObject()
        root.put("model", model)

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        for (msg in history) {
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })
        root.put("messages", messages)

        // Request JSON object output format if supported
        root.put("response_format", JSONObject().put("type", "json_object"))

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))

        for ((k, v) in extraHeaders) {
            reqBuilder.header(k, v)
        }

        val response = aiHttpClient.newCall(reqBuilder.build()).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("API error (${response.code}): $responseBody")
        }

        val jsonResp = JSONObject(responseBody)
        val choices = jsonResp.optJSONArray("choices")
            ?: throw IllegalStateException("No choices returned from AI API")
        val text = choices.getJSONObject(0).getJSONObject("message").getString("content")
        return text
    }

    private fun callAnthropic(
        apiKey: String,
        model: String,
        systemPrompt: String,
        prompt: String,
        history: List<AiChatMessage>
    ): String {
        val url = "https://api.anthropic.com/v1/messages"

        val root = JSONObject()
        root.put("model", model)
        root.put("system", systemPrompt)
        root.put("max_tokens", 1024)

        val messages = JSONArray()
        for (msg in history) {
            if (msg.role != "system") {
                messages.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", "$prompt\n\nRemember to respond ONLY with valid JSON matching the specified schema.")
        })
        root.put("messages", messages)

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = aiHttpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Anthropic API error (${response.code}): $responseBody")
        }

        val jsonResp = JSONObject(responseBody)
        val contentArray = jsonResp.optJSONArray("content")
            ?: throw IllegalStateException("No content returned from Anthropic")
        val text = contentArray.getJSONObject(0).getString("text")
        return text
    }

    private fun parseAiResponse(raw: String): AiResponse {
        var clean = raw.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").substringBeforeLast("```").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").substringBeforeLast("```").trim()
        }

        return try {
            json.decodeFromString<AiResponse>(clean)
        } catch (e: Exception) {
            // Robust JSON extraction fallback using org.json
            val obj = JSONObject(clean)
            val spoken = obj.optString("spokenResponse", obj.optString("spoken_response", "Here are your recommendations."))
            val catalog = obj.optString("catalogTitle", obj.optString("catalog_title", "AI Recommendations"))
            val questions = mutableListOf<String>()
            val qArr = obj.optJSONArray("suggestedQuestions") ?: obj.optJSONArray("suggested_questions")
            if (qArr != null) {
                for (i in 0 until qArr.length()) {
                    questions.add(qArr.getString(i))
                }
            }
            val recs = mutableListOf<AiRecommendationItem>()
            val rArr = obj.optJSONArray("recommendations")
            if (rArr != null) {
                for (i in 0 until rArr.length()) {
                    val item = rArr.getJSONObject(i)
                    recs.add(
                        AiRecommendationItem(
                            title = item.getString("title"),
                            year = item.optInt("year").takeIf { it > 0 },
                            type = item.optString("type", "movie"),
                            rationale = item.optString("rationale").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            AiResponse(
                spokenResponse = spoken,
                catalogTitle = catalog,
                suggestedQuestions = questions,
                recommendations = recs
            )
        }
    }

    private fun buildSystemPrompt(persona: String): String {
        return """
$persona

You are powering the TV search and recommendation system for Nuvio+.
When the user asks for recommendations, speaks conversationally, or searches for content:
1. Formulate a short, engaging spoken response (1-2 sentences) that answers them directly and conversationally as if talking to them in their living room.
2. Formulate a creative, thematic catalog title (e.g. "90s Cyberpunk Thrillers", "Twisty Psychological Mysteries", "Adrenaline Heist Nights").
3. Provide 2-3 short suggested follow-up questions to continue refining their taste.
4. Curate 4 to 8 exact real movies or TV shows matching their request.

You MUST respond ONLY with a valid JSON object strictly matching this schema:
{
  "spokenResponse": "Brief conversational reply to speak aloud to the user.",
  "catalogTitle": "Creative Thematic Catalog Title",
  "suggestedQuestions": ["Follow up question 1?", "Follow up question 2?"],
  "recommendations": [
    {
      "title": "Exact Title of Movie or Series",
      "year": 1999,
      "type": "movie",
      "rationale": "Why this fits their mood in one sentence."
    }
  ]
}
Do not output any markdown surrounding text other than the JSON object itself.
        """.trimIndent()
    }

    companion object {
        private const val TAG = "AiManager"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
