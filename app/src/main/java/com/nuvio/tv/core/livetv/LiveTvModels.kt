package com.nuvio.tv.core.livetv

enum class LiveTvCategory(val displayName: String) {
    ALL("All Channels"),
    SPORTS("Sports"),
    ENTERTAINMENT("Entertainment"),
    NEWS("News"),
    MOVIES("Movies & HBO"),
    FRENCH("Français")
}

data class LiveProgram(
    val title: String,
    val episodeTitle: String? = null,
    val description: String? = null,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val genre: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null
) {
    val progressFraction: Float
        get() {
            val now = System.currentTimeMillis()
            if (now <= startTimestampMs) return 0f
            if (now >= endTimestampMs) return 1f
            val total = (endTimestampMs - startTimestampMs).toFloat()
            return if (total > 0f) (now - startTimestampMs) / total else 0f
        }

    val remainingMinutes: Int
        get() {
            val now = System.currentTimeMillis()
            val remMs = (endTimestampMs - now).coerceAtLeast(0L)
            return (remMs / 60_000L).toInt()
        }
}

data class LiveTvChannel(
    val id: String,
    val number: String,
    val name: String,
    val callSign: String,
    val category: LiveTvCategory,
    val logoText: String,
    val accentColorHex: String = "#0055A5", // Classic Canadian Bell blue
    val currentProgram: LiveProgram,
    val nextProgram: LiveProgram? = null,
    val streamUrl: String? = null,
    val logoUrl: String? = null,
    val isCustom: Boolean = false
)
