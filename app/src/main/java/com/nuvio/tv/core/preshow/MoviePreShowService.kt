package com.nuvio.tv.core.preshow

import android.content.Context
import android.util.Log
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
    val videoUrl: String,
    val audioUrl: String? = null
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
    private companion object {
        const val TAG = "MoviePreShowService"
    }

    suspend fun loadPreShow(
        context: Context,
        movieTitle: String,
        movieYear: String?,
        genre: List<String>?,
        trailerYtIds: List<String> = emptyList()
    ): PreShowPackage = withContext(Dispatchers.IO) {
        val triviaList = fetchTrivia(context, movieTitle, movieYear, genre)
        val trailerList = resolveTrailers(movieTitle, movieYear, trailerYtIds)
        PreShowPackage(trivia = triviaList, trailers = trailerList)
    }

    private suspend fun resolveTrailers(
        movieTitle: String,
        movieYear: String?,
        trailerYtIds: List<String>
    ): List<PreShowTrailer> {
        val service = trailerService ?: return emptyList()
        val results = mutableListOf<PreShowTrailer>()

        // 1. Try provided YouTube trailer IDs (up to 2)
        val validIds = trailerYtIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(2)
        for (ytId in validIds) {
            try {
                val source = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=$ytId")
                if (source != null && source.videoUrl.isNotBlank()) {
                    results.add(
                        PreShowTrailer(
                            title = "$movieTitle — Official Trailer",
                            videoUrl = source.videoUrl,
                            audioUrl = source.audioUrl
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to resolve trailer for ytId $ytId: ${t.message}")
            }
        }

        // 2. If no trailer resolved from IDs, search TMDB by title/year
        if (results.isEmpty()) {
            try {
                val source = service.getTrailerPlaybackSource(
                    title = movieTitle,
                    year = movieYear,
                    tmdbId = null,
                    type = "movie"
                )
                if (source != null && source.videoUrl.isNotBlank()) {
                    results.add(
                        PreShowTrailer(
                            title = "$movieTitle — Theatrical Trailer",
                            videoUrl = source.videoUrl,
                            audioUrl = source.audioUrl
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to resolve fallback trailer for $movieTitle: ${t.message}")
            }
        }

        return results
    }

    private suspend fun fetchTrivia(
        context: Context,
        title: String,
        year: String?,
        genre: List<String>?
    ): List<MovieTriviaItem> {
        val genreString = genre?.filter { it.isNotBlank() }?.joinToString(", ") ?: ""
        val systemPrompt = """
            You are a cinema trivia expert for theatrical movie pre-shows (like AMC, Regal, and Alamo Drafthouse).
            Generate 3 fun, entertaining, completely spoiler-free multiple-choice trivia questions for the movie: $title ($year).
            ${if (genreString.isNotBlank()) "Genre: $genreString." else ""}
            Each question MUST have exactly 4 multiple-choice options, one correct answer (matching an option exactly), and a fascinating behind-the-scenes fun fact.
            Return ONLY a valid JSON array of objects with this schema:
            [
              {
                "question": "Trivia question text?",
                "options": ["Option A", "Option B", "Option C", "Option D"],
                "answer": "Option A",
                "funFact": "One fascinating behind-the-scenes sentence about this."
              }
            ]
        """.trimIndent()

        val result = aiManager.queryRaw(
            context = context,
            systemPrompt = systemPrompt,
            prompt = "Generate 3 theatrical cinema trivia questions for $title ($year)",
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
                        val optText = optArray.getString(j).trim()
                        if (optText.isNotBlank()) opts.add(optText)
                    }
                }
                val q = obj.optString("question", "").trim()
                val ans = obj.optString("answer", "").trim()
                val fact = obj.optString("funFact", "").trim()

                if (q.isNotBlank() && ans.isNotBlank() && opts.size >= 2) {
                    items.add(
                        MovieTriviaItem(
                            question = q,
                            options = opts,
                            answer = ans,
                            funFact = fact.ifBlank { "Enjoy the show!" }
                        )
                    )
                }
            }
            if (items.isNotEmpty()) items else fallbackTrivia(title, year)
        }.getOrDefault(fallbackTrivia(title, year))
    }

    private fun fallbackTrivia(title: String, year: String?): List<MovieTriviaItem> {
        val yr = year?.takeIf { it.isNotBlank() } ?: "the cinema"
        return listOf(
            MovieTriviaItem(
                question = "In what year did $title first hit theaters worldwide?",
                options = listOf(yr, "1999", "2015", "2021").distinct(),
                answer = yr,
                funFact = "$title captivated audiences upon its theatrical release."
            ),
            MovieTriviaItem(
                question = "What makes the cinematic production of $title stand out?",
                options = listOf(
                    "Pioneering visual style & storytelling",
                    "Filmed in a single continuous take",
                    "Produced with zero budget",
                    "Shot entirely on 16mm film"
                ),
                answer = "Pioneering visual style & storytelling",
                funFact = "The filmmakers pushed creative boundaries to deliver an unforgettable theatrical experience."
            )
        )
    }
}
