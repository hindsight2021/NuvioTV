package com.nuvio.tv.core.preshow

import android.content.Context
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.data.trailer.TrailerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MovieTriviaItem(
    val question: String,
    val options: List<String>,
    val answer: String,
    val funFact: String
)

data class PreShowTrailer(
    val title: String,
    val videoUrl: String
)

data class PreShowPackage(
    val trivia: List<MovieTriviaItem>,
    val trailers: List<PreShowTrailer>
)

@Singleton
class MoviePreShowService @Inject constructor(
    private val aiManager: AiManager,
    private val trailerService: TrailerService? = null
) {
    suspend fun loadPreShow(
        context: Context,
        movieTitle: String,
        movieYear: String?,
        genre: List<String>?
    ): PreShowPackage = withContext(Dispatchers.IO) {
        val triviaList = fetchTrivia(context, movieTitle, movieYear)
        PreShowPackage(trivia = triviaList, trailers = emptyList())
    }

    private suspend fun fetchTrivia(
        context: Context,
        title: String,
        year: String?
    ): List<MovieTriviaItem> {
        val systemPrompt = """
            You are a cinema trivia expert for movie theaters.
            Generate 2 fun, engaging, completely spoiler-free multiple-choice trivia questions for the movie: $title ($year).
            Return ONLY a valid JSON array of objects with this schema:
            [
              {
                "question": "Trivia question text?",
                "options": ["Option A", "Option B", "Option C"],
                "answer": "Correct Option",
                "funFact": "One fascinating behind-the-scenes sentence about this."
              }
            ]
        """.trimIndent()

        val result = aiManager.queryRaw(
            context = context,
            systemPrompt = systemPrompt,
            prompt = "Generate trivia questions for $title ($year)",
            expectJson = true
        )

        return result.mapCatching { jsonStr ->
            val clean = jsonStr.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array = JSONArray(clean)
            val items = mutableListOf<MovieTriviaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val opts = mutableListOf<String>()
                val optArray = obj.optJSONArray("options")
                if (optArray != null) {
                    for (j in 0 until optArray.length()) {
                        opts.add(optArray.getString(j))
                    }
                }
                items.add(
                    MovieTriviaItem(
                        question = obj.optString("question", "Did you know this movie was filmed in record time?"),
                        options = opts,
                        answer = obj.optString("answer", ""),
                        funFact = obj.optString("funFact", "The director insisted on practical effects.")
                    )
                )
            }
            items
        }.getOrDefault(
            listOf(
                MovieTriviaItem(
                    question = "Did you know?",
                    options = emptyList(),
                    answer = "",
                    funFact = "Sit back, dim the lights, and enjoy $title!"
                )
            )
        )
    }
}
