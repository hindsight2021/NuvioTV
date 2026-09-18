package com.nuvio.tv.ambient.context

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks recent viewing context and applies ambient category bonuses.
 * Context expires after 2 hours.
 */
@Singleton
class AmbientRecentWatchContext @Inject constructor() {

    data class RecentWatch(
        val title: String,
        val genres: List<String>,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val recentWatch = AtomicReference<RecentWatch?>(null)

    fun recordWatch(title: String, genres: List<String>) {
        recentWatch.set(RecentWatch(title = title, genres = genres))
    }

    fun getRecentWatch(nowMs: Long = System.currentTimeMillis()): RecentWatch? {
        val watch = recentWatch.get() ?: return null
        return if (nowMs - watch.timestampMs > EXPIRATION_MS) null else watch
    }

    fun getCategoryBonus(category: String, nowMs: Long = System.currentTimeMillis()): Float {
        val watch = getRecentWatch(nowMs) ?: return 0.0f
        val genres = watch.genres.map { it.lowercase() }

        if (genres.any { it == "sci-fi" || it == "science fiction" }) {
            when (category) {
                "space" -> return 0.35f
                "after_dark" -> return 0.15f
            }
        }

        if (genres.any { it == "documentary" || it == "nature" }) {
            when (category) {
                "nature" -> return 0.35f
                "aerial" -> return 0.20f
            }
        }

        if (genres.any { it == "action" || it == "crime" || it == "thriller" }) {
            when (category) {
                "cities" -> return 0.30f
                "after_dark" -> return 0.20f
            }
        }

        if (genres.any { it == "romance" || it == "drama" }) {
            when (category) {
                "fireplace" -> return 0.35f
                "cities" -> return 0.15f
            }
        }

        return 0.0f
    }

    companion object {
        const val EXPIRATION_MS = 2 * 3600 * 1000L
    }
}
