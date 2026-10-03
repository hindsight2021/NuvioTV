package com.nuvio.tv.core.preshow

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.ai.AiManager
import com.nuvio.tv.data.trailer.TrailerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data model for a single trivia question shown during the cinema pre-show.
 */
data class MovieTriviaItem(
    val question: String,
    val options: List<String>,
    val answer: String,
    val funFact: String
)

/**
 * Data model for a trailer entry in the pre-show package.
 */
data class PreShowTrailer(
    val title: String,
    val videoUrl: String,
    val audioUrl: String? = null
)

/**
 * Aggregated pre-show content for a movie: trivia questions + upcoming trailers.
 */
data class PreShowPackage(
    val trivia: List<MovieTriviaItem>,
    val trailers: List<PreShowTrailer>
)

/**
 * Production service that builds a theatrical-style pre-show package (trivia + trailers)
 * for a given movie. Trivia is generated via AI with rich film context and a robust
 * procedural movie-specific fallback, with results cached in-memory for instant repeat loads.
 */
@Singleton
class MoviePreShowService @Inject constructor(
    private val aiManager: AiManager?,
    private val trailerService: TrailerService? = null
) {
    private companion object {
        const val TAG = "MoviePreShowService"
        const val OVERVIEW_MAX_CHARS = 350
        const val MAX_CAST_IN_PROMPT = 5
        const val TRIVIA_COUNT = 3

        val FAMOUS_DIRECTORS = listOf(
            "Christopher Nolan",
            "Steven Spielberg",
            "Denis Villeneuve",
            "David Fincher",
            "Martin Scorsese",
            "Ridley Scott",
            "James Cameron",
            "Quentin Tarantino",
            "Greta Gerwig",
            "Wes Anderson"
        )

        val FAMOUS_ACTORS = listOf(
            "Leonardo DiCaprio",
            "Tom Cruise",
            "Brad Pitt",
            "Christian Bale",
            "Margot Robbie",
            "Emma Stone",
            "Ryan Gosling",
            "Denzel Washington",
            "Scarlett Johansson",
            "Robert Downey Jr."
        )

        val POPULAR_GENRES = listOf(
            "Action", "Adventure", "Animation", "Comedy", "Crime",
            "Drama", "Fantasy", "Horror", "Mystery", "Sci-Fi", "Thriller"
        )
    }

    /** In-memory cache keyed by "$movieTitle|$movieYear" for instantaneous 0ms repeat loads. */
    private val cache = ConcurrentHashMap<String, PreShowPackage>()

    /**
     * Loads (or builds) the pre-show package for the given movie.
     * Cached results return instantly.
     */
    suspend fun loadPreShow(
        context: Context,
        movieTitle: String,
        movieYear: String?,
        genre: List<String>?,
        overview: String? = null,
        director: List<String> = emptyList(),
        cast: List<String> = emptyList(),
        trailerYtIds: List<String> = emptyList()
    ): PreShowPackage = withContext(Dispatchers.IO) {
        val cacheKey = "$movieTitle|${movieYear.orEmpty()}".lowercase().trim()
        cache[cacheKey]?.takeIf { it.trailers.isNotEmpty() && it.trivia.isNotEmpty() }?.let { cached ->
            Log.d(TAG, "Returning cached pre-show package for: $movieTitle ($movieYear)")
            return@withContext cached
        }

        val triviaList = fetchTrivia(
            context = context,
            movieTitle = movieTitle,
            movieYear = movieYear,
            genre = genre,
            overview = overview,
            director = director,
            cast = cast
        )

        val trailerList = resolveUpcomingTheatricalTrailers(movieTitle)

        val pkg = PreShowPackage(trivia = triviaList, trailers = trailerList)
        if (triviaList.isNotEmpty() && trailerList.isNotEmpty()) {
            cache[cacheKey] = pkg
        }
        pkg
    }

    // ---------------------------------------------------------------------
    // Trivia generation
    // ---------------------------------------------------------------------

    private suspend fun fetchTrivia(
        context: Context,
        movieTitle: String,
        movieYear: String?,
        genre: List<String>?,
        overview: String?,
        director: List<String>,
        cast: List<String>
    ): List<MovieTriviaItem> {
        val ai = aiManager ?: return fallbackTrivia(movieTitle, movieYear, genre, overview, director, cast)

        val systemPrompt = """
            You are a theatrical cinema pre-show trivia master (like Alamo Drafthouse, AMC, and Noovie).
            Create exactly $TRIVIA_COUNT entertaining, multiple-choice trivia questions for the movie below.
            The questions will be displayed on screen in a movie theater before the film starts.
            They must be fun, accurate, and STRICTLY SPOILER-FREE (never spoil key plot twists, character deaths, or endings).

            QUESTION TOPICS:
            - Question 1: Behind-the-scenes, casting, production, or director vision.
            - Question 2: In-universe detail, memorable dialogue setup, or character quirk.
            - Question 3: Cultural impact, box office milestone, awards, soundtrack, or easter egg.

            OUTPUT FORMAT:
            Return ONLY a valid JSON array of objects with this schema:
            [
              {
                "question": "The question text?",
                "options": ["Choice A", "Choice B", "Choice C", "Choice D"],
                "answer": "Choice A",
                "funFact": "One fascinating 1-2 sentence behind-the-scenes fact revealed after answering."
              }
            ]
        """.trimIndent()

        val yearStr = movieYear?.takeIf { it.isNotBlank() } ?: "unknown"
        val genreStr = genre?.filter { it.isNotBlank() }?.joinToString(", ")?.ifBlank { "unknown" } ?: "unknown"
        val directorStr = director.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "unknown" }
        val castStr = cast.filter { it.isNotBlank() }.take(MAX_CAST_IN_PROMPT).joinToString(", ").ifBlank { "unknown" }
        val overviewStr = overview?.trim()?.take(OVERVIEW_MAX_CHARS)?.ifBlank { "unknown" } ?: "unknown"

        val prompt = """
            Generate 3 theatrical cinema trivia questions for:
            - Title: $movieTitle
            - Release Year: $yearStr
            - Genre: $genreStr
            - Director: $directorStr
            - Top Cast: $castStr
            - Synopsis: $overviewStr
        """.trimIndent()

        return try {
            val result = ai.queryRaw(
                context = context,
                systemPrompt = systemPrompt,
                prompt = prompt,
                expectJson = true
            )
            val jsonStr = result.getOrNull()
            val parsed = parseTriviaResponse(jsonStr)
            if (parsed.isNotEmpty()) {
                parsed
            } else {
                Log.w(TAG, "Empty or invalid AI trivia parsed for '$movieTitle', using procedural fallback.")
                fallbackTrivia(movieTitle, movieYear, genre, overview, director, cast)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AI trivia generation exception for '$movieTitle': ${t.message}")
            fallbackTrivia(movieTitle, movieYear, genre, overview, director, cast)
        }
    }

    /**
     * Robustly extracts and parses trivia JSON from an AI response,
     * supporting root arrays, object wrappers ("trivia", "questions", "data"),
     * and markdown code fence stripping.
     */
    private fun parseTriviaResponse(raw: String?): List<MovieTriviaItem> {
        if (raw.isNullOrBlank()) return emptyList()

        val cleaned = stripCodeFences(raw)
        val jsonArray = extractJsonArray(cleaned) ?: return emptyList()

        val result = mutableListOf<MovieTriviaItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val item = parseTriviaItem(obj) ?: continue
            result.add(item)
        }
        return result
    }

    private fun stripCodeFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline >= 0) s.substring(firstNewline + 1) else s.removePrefix("```")
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length - 3)
        }
        return s.trim()
    }

    private fun extractJsonArray(text: String): JSONArray? {
        // 1. Try direct array first
        val arrayStart = text.indexOf('[')
        val arrayEnd = text.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            val candidate = text.substring(arrayStart, arrayEnd + 1)
            try {
                return JSONArray(candidate)
            } catch (_: Exception) {
                // fall through to object handling
            }
        }

        // 2. Try object wrapper (e.g., { "trivia": [...] } or { "questions": [...] })
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            val candidate = text.substring(objStart, objEnd + 1)
            try {
                val obj = JSONObject(candidate)
                obj.optJSONArray("trivia")?.let { return it }
                obj.optJSONArray("questions")?.let { return it }
                obj.optJSONArray("items")?.let { return it }
                obj.optJSONObject("data")?.let { data ->
                    data.optJSONArray("trivia")?.let { return it }
                    data.optJSONArray("questions")?.let { return it }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun parseTriviaItem(obj: JSONObject): MovieTriviaItem? {
        val question = obj.optString("question").trim()
        val answer = obj.optString("answer").trim()
        val funFact = obj.optString("funFact").trim()

        if (question.isBlank() || answer.isBlank()) return null

        val optionsArray = obj.optJSONArray("options") ?: return null
        val rawOptions = mutableListOf<String>()
        for (i in 0 until optionsArray.length()) {
            val opt = optionsArray.optString(i).trim()
            if (opt.isNotBlank()) rawOptions.add(opt)
        }
        if (rawOptions.isEmpty()) return null

        // Ensure the answer is present in options
        val optionsWithAnswer = rawOptions.toMutableList()
        if (optionsWithAnswer.none { it.equals(answer, ignoreCase = true) }) {
            optionsWithAnswer.add(answer)
        }

        // Deduplicate while preserving unique entries
        val distinct = optionsWithAnswer.distinctBy { it.lowercase() }.toMutableList()

        // Ensure at least 3 options; pad with plausible distractors if needed
        if (distinct.size < 3) {
            val fillers = listOf("None of the above", "All of the above", "Not revealed")
            for (f in fillers) {
                if (distinct.size >= 4) break
                if (distinct.none { it.equals(f, ignoreCase = true) }) distinct.add(f)
            }
        }

        // Cap at 4 options while guaranteeing answer is included
        val capped = if (distinct.size > 4) {
            val answerEntry = distinct.firstOrNull { it.equals(answer, ignoreCase = true) }
            val others = distinct.filterNot { it.equals(answer, ignoreCase = true) }
            val trimmed = mutableListOf<String>()
            if (answerEntry != null) trimmed.add(answerEntry)
            trimmed.addAll(others.take(4 - trimmed.size))
            trimmed
        } else {
            distinct
        }

        // IMPORTANT: Shuffle so the correct answer is not always in position A
        val shuffled = capped.shuffled()

        return MovieTriviaItem(
            question = question,
            options = shuffled,
            answer = answer,
            funFact = funFact.ifBlank { "Enjoy the show!" }
        )
    }

    // ---------------------------------------------------------------------
    // Procedural Fallback Trivia (Specific to Movie Metadata)
    // ---------------------------------------------------------------------

    private fun fallbackTrivia(
        movieTitle: String,
        movieYear: String?,
        genre: List<String>?,
        overview: String?,
        director: List<String>,
        cast: List<String>
    ): List<MovieTriviaItem> {
        val year = movieYear?.trim()?.takeIf { it.isNotBlank() }
        val items = mutableListOf<MovieTriviaItem>()

        // Q1: Director (or release year if director unknown)
        items.add(buildDirectorQuestion(movieTitle, year, director))

        // Q2: Cast (or genre if cast unknown)
        items.add(buildCastQuestion(movieTitle, year, cast, genre))

        // Q3: Premise / Genre / Release window
        items.add(buildPremiseQuestion(movieTitle, year, genre, overview))

        return items
    }

    private fun buildDirectorQuestion(
        movieTitle: String,
        year: String?,
        director: List<String>
    ): MovieTriviaItem {
        val realDirector = director.firstOrNull { it.isNotBlank() }?.trim()

        if (realDirector != null) {
            val distractors = FAMOUS_DIRECTORS
                .filterNot { it.equals(realDirector, ignoreCase = true) }
                .shuffled()
                .take(3)
            val options = (distractors + realDirector).shuffled()
            return MovieTriviaItem(
                question = "Who directed the theatrical release of $movieTitle${year?.let { " ($it)" } ?: ""}?",
                options = options,
                answer = realDirector,
                funFact = "$realDirector brought $movieTitle to the big screen, crafting its distinct visual identity and cinematic atmosphere."
            )
        }

        // Fallback: release year question with realistic nearby alternatives
        val baseYear = year?.toIntOrNull() ?: 2020
        val correct = baseYear.toString()
        val candidates = listOf(
            (baseYear - 2).toString(),
            (baseYear + 1).toString(),
            (baseYear + 3).toString()
        ).filter { it != correct }.distinct().take(3)
        val options = (candidates + correct).shuffled()
        return MovieTriviaItem(
            question = "In what year did $movieTitle first hit theaters worldwide?",
            options = options,
            answer = correct,
            funFact = "$movieTitle arrived in theaters in $correct, making waves with theatergoers worldwide."
        )
    }

    private fun buildCastQuestion(
        movieTitle: String,
        year: String?,
        cast: List<String>,
        genre: List<String>?
    ): MovieTriviaItem {
        val realActor = cast.firstOrNull { it.isNotBlank() }?.trim()

        if (realActor != null) {
            val distractors = FAMOUS_ACTORS
                .filterNot { it.equals(realActor, ignoreCase = true) }
                .shuffled()
                .take(3)
            val options = (distractors + realActor).shuffled()
            return MovieTriviaItem(
                question = "Which of the following stars in $movieTitle${year?.let { " ($it)" } ?: ""}?",
                options = options,
                answer = realActor,
                funFact = "$realActor delivered a defining performance in $movieTitle that captivated audiences and critics alike."
            )
        }

        // Fallback: primary genre
        val realGenre = genre?.firstOrNull { it.isNotBlank() }?.trim()
        if (realGenre != null) {
            val distractors = POPULAR_GENRES
                .filterNot { it.equals(realGenre, ignoreCase = true) }
                .shuffled()
                .take(3)
            val options = (distractors + realGenre).shuffled()
            return MovieTriviaItem(
                question = "Which genre best defines the theatrical experience of $movieTitle?",
                options = options,
                answer = realGenre,
                funFact = "$movieTitle is acclaimed for pushing the boundaries of the $realGenre genre with unforgettable theatrical moments."
            )
        }

        // Last resort: release decade/year
        val baseYear = year?.toIntOrNull() ?: 2021
        val correct = baseYear.toString()
        val candidates = listOf(
            (baseYear - 1).toString(),
            (baseYear + 2).toString(),
            (baseYear + 4).toString()
        ).filter { it != correct }.distinct().take(3)
        val options = (candidates + correct).shuffled()
        return MovieTriviaItem(
            question = "In what year did $movieTitle premiere in theaters?",
            options = options,
            answer = correct,
            funFact = "$movieTitle premiered theatrically in $correct to worldwide acclaim."
        )
    }

    private fun buildPremiseQuestion(
        movieTitle: String,
        year: String?,
        genre: List<String>?,
        overview: String?
    ): MovieTriviaItem {
        val realGenre = genre?.firstOrNull { it.isNotBlank() }?.trim()
        if (realGenre != null) {
            val distractors = POPULAR_GENRES
                .filterNot { it.equals(realGenre, ignoreCase = true) }
                .shuffled()
                .take(3)
            val options = (distractors + realGenre).shuffled()
            return MovieTriviaItem(
                question = "In which cinematic category does $movieTitle deliver its story?",
                options = options,
                answer = realGenre,
                funFact = "Critics singled out $movieTitle for its standout storytelling within the $realGenre landscape."
            )
        }

        val baseYear = year?.toIntOrNull() ?: 2022
        val correct = baseYear.toString()
        val candidates = listOf(
            (baseYear - 3).toString(),
            (baseYear - 1).toString(),
            (baseYear + 2).toString()
        ).filter { it != correct }.distinct().take(3)
        val options = (candidates + correct).shuffled()
        return MovieTriviaItem(
            question = "What year did audiences first experience $movieTitle on the big screen?",
            options = options,
            answer = correct,
            funFact = "$movieTitle made its memorable theatrical debut in $correct."
        )
    }

    // ---------------------------------------------------------------------
    // Upcoming Theatrical Trailer Resolution (Coming Attractions)
    // ---------------------------------------------------------------------

    private suspend fun resolveUpcomingTheatricalTrailers(
        movieTitle: String
    ): List<PreShowTrailer> {
        val service = trailerService ?: return emptyList()
        return try {
            val upcoming = service.getUpcomingTheatricalTrailers(
                excludeTitle = movieTitle,
                limit = 2
            )
            upcoming.map { item ->
                PreShowTrailer(
                    title = item.title,
                    videoUrl = item.videoUrl,
                    audioUrl = item.audioUrl
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve upcoming theatrical trailers: ${t.message}", t)
            emptyList()
        }
    }
}
