package com.nuvio.tv.core.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ChicCriticReview(
    val headline: String,
    val reviewText: String,
    val verdict: String,
    val campScore: String
)

@Singleton
class ChicCriticReviewService @Inject constructor(
    private val aiManager: AiManager
) {
    private val systemPrompt = """
        You are a chic, delightfully uppity, fabulous gay movie and TV critic who knows EXACTLY what Mike & Chris adore in their entertainment (camp, prestige drama, biting dialogue, gorgeous cinematography, tight pacing, zero boring filler).
        Your task is to give a punchy, hilarious, sharp 2-to-3 sentence review and verdict on whether Mike & Chris should devour this or skip it.
        Be deeply knowledgeable, full of flavor, unapologetic, and utterly entertaining.
        Do NOT use markdown asterisks (* or **), hashtags, or bullet points because this will be read aloud via Text-To-Speech.
        
        Return ONLY a JSON object with this exact structure:
        {
          "headline": "A short, fabulous, unforgettable one-liner verdict",
          "review": "2 to 3 sentences of witty, chic commentary tailored to Mike & Chris",
          "verdict": "Must Devour | Guilty Pleasure | Pure Camp Classic | Hard Pass Darling",
          "campScore": "e.g. 10/10 Pure Cinema | 9/10 High Camp | 3/10 Dull Tragedy"
        }
    """.trimIndent()

    suspend fun generateReview(
        context: Context,
        title: String,
        overview: String?,
        genre: List<String>?,
        year: String?,
        isEpisode: Boolean = false,
        episodeTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<ChicCriticReview> = withContext(Dispatchers.IO) {
        val prompt = if (isEpisode) {
            """
            Show: $title
            Season $seasonNumber, Episode $episodeNumber: ${episodeTitle ?: "Episode $episodeNumber"}
            Overview: ${overview ?: "No overview available."}
            Genres: ${genre?.joinToString(", ") ?: "Drama"}
            Give Mike & Chris your chic, witty review and verdict for this specific episode.
            """.trimIndent()
        } else {
            """
            Title: $title ($year)
            Genres: ${genre?.joinToString(", ") ?: "Entertainment"}
            Overview: ${overview ?: "No overview available."}
            Give Mike & Chris your chic, witty review and verdict on whether they should watch this.
            """.trimIndent()
        }

        val rawResult = aiManager.queryRaw(context, systemPrompt, prompt, expectJson = true)
        rawResult.mapCatching { jsonStr ->
            val cleanJson = jsonStr.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleanJson)
            ChicCriticReview(
                headline = obj.optString("headline", "Darling, it's an absolute feast."),
                reviewText = obj.optString("review", "A complete visual treat with just the right amount of drama."),
                verdict = obj.optString("verdict", "Must Devour"),
                campScore = obj.optString("campScore", "10/10 Chic")
            )
        }
    }
}
