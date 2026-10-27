package com.nuvio.tv.core.autopilot

/**
 * Represents the strategy used by the Autopilot system to select the next item to play.
 */
enum class AutopilotMode {
    /**
     * Deterministic selection strategy.
     *
     * Prioritizes the newest release episodes first across Continue Watching,
     * then falls back to the next available continue watching items in line.
     */
    DEFAULT,

    /**
     * AI-driven curated selection strategy.
     *
     * Uses Gemini AI to evaluate the available Continue Watching inventory and
     * curate the next best episode based on narrative momentum, genre transition,
     * and viewing context.
     */
    AI_CURATED
}

/**
 * A single playable candidate that the Autopilot system can choose from.
 *
 * @property contentId Unique identifier of the parent content (series or movie).
 * @property contentType Type of the content ("series" or "movie").
 * @property title Display title of the show or movie.
 * @property poster Optional URL of the poster image.
 * @property backdrop Optional URL of the backdrop image.
 * @property logo Optional URL of the logo image.
 * @property videoId Unique identifier of the specific video/episode stream to play.
 * @property season Optional season number for episodic content.
 * @property episode Optional episode number for episodic content.
 * @property episodeTitle Optional title of the episode.
 * @property episodeDescription Optional description/synopsis of the episode.
 * @property thumbnail Optional URL of the episode thumbnail.
 * @property genres List of genres associated with the content.
 * @property isNewRelease Whether this candidate is considered a newly released episode.
 * @property releaseDateLabel Optional human-readable release date label (e.g. "Today", "2 days ago").
 * @property lastWatched Epoch millis of the last time this content was watched; `0L` if never.
 * @property imdbRating Optional IMDb rating of the content.
 */
data class AutopilotCandidate(
    val contentId: String,
    val contentType: String = "series",
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val videoId: String = contentId,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val thumbnail: String? = null,
    val genres: List<String> = emptyList(),
    val isNewRelease: Boolean = false,
    val releaseDateLabel: String? = null,
    val lastWatched: Long = 0L,
    val imdbRating: Float? = null
)

/**
 * The result of an Autopilot selection.
 *
 * @property candidate The chosen candidate to play.
 * @property mode The [AutopilotMode] that produced this decision.
 * @property aiRationale Optional human-readable rationale provided by the AI when
 *   [mode] is [AutopilotMode.AI_CURATED] (or fallback indicator).
 */
data class AutopilotDecision(
    val candidate: AutopilotCandidate,
    val mode: AutopilotMode,
    val aiRationale: String? = null
)

/**
 * Returns a formatted episode code such as `S01E02`, or `null` if either [AutopilotCandidate.season]
 * or [AutopilotCandidate.episode] is missing.
 */
fun AutopilotCandidate.formattedEpisodeCode(): String? {
    val s = season ?: return null
    val e = episode ?: return null
    return "S%02dE%02d".format(s, e)
}

/**
 * Returns `true` if this candidate represents episodic content (i.e. both [AutopilotCandidate.season]
 * and [AutopilotCandidate.episode] are present).
 */
fun AutopilotCandidate.isEpisodic(): Boolean = season != null && episode != null

/**
 * Returns `true` if this candidate has been watched at least once.
 */
fun AutopilotCandidate.hasBeenWatched(): Boolean = lastWatched > 0L
