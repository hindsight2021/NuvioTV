package com.nuvio.tv.ambient.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.ambient.AmbientCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single record of an ambient candidate interaction (playback, feedback, failure).
 */
data class AmbientHistoryEntry(
    val candidateId: String,
    val timestampEpochMs: Long,
    val durationSecondsShown: Long,
    val completed: Boolean,
    val failureCount: Int = 0,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val category: String? = null
)

// DataStore instance scoped to the application context.
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "ambient_history")

/**
 * Persists ambient playback history and derived queries (failure counts, feedback, recency).
 */
@Singleton
class AmbientHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()
    private val historyKey = stringPreferencesKey("ambient_history_entries")
    private val listType = object : TypeToken<List<AmbientHistoryEntry>>() {}.type

    /** Reads the full history list from DataStore, returning an empty list on parse failure. */
    private suspend fun readEntries(): List<AmbientHistoryEntry> {
        val json = context.historyDataStore.data.map { it[historyKey] }.first()
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson<List<AmbientHistoryEntry>>(json, listType) }
            .getOrNull()
            ?: emptyList()
    }

    /** Persists the given history list back to DataStore. */
    private suspend fun writeEntries(entries: List<AmbientHistoryEntry>) {
        val json = gson.toJson(entries, listType)
        context.historyDataStore.edit { prefs -> prefs[historyKey] = json }
    }

    /**
     * Records a playback event. Merges into an existing entry for the same candidate,
     * accumulating shown duration and preserving prior feedback/failure data.
     */
    suspend fun recordPlayed(candidate: AmbientCandidate, durationSeconds: Long, completed: Boolean) {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.candidateId == candidate.id }
        val now = System.currentTimeMillis()

        if (index >= 0) {
            val existing = entries[index]
            entries[index] = existing.copy(
                timestampEpochMs = now,
                durationSecondsShown = existing.durationSecondsShown + durationSeconds,
                completed = existing.completed || completed,
                category = candidate.category
            )
        } else {
            entries.add(
                AmbientHistoryEntry(
                    candidateId = candidate.id,
                    timestampEpochMs = now,
                    durationSecondsShown = durationSeconds,
                    completed = completed,
                    category = candidate.category
                )
            )
        }
        writeEntries(entries)
    }

    /** Increments the failure counter for a candidate, creating an entry if none exists. */
    suspend fun recordFailure(candidateId: String) {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.candidateId == candidateId }
        val now = System.currentTimeMillis()

        if (index >= 0) {
            val existing = entries[index]
            entries[index] = existing.copy(
                failureCount = existing.failureCount + 1,
                timestampEpochMs = now
            )
        } else {
            entries.add(
                AmbientHistoryEntry(
                    candidateId = candidateId,
                    timestampEpochMs = now,
                    durationSecondsShown = 0L,
                    completed = false,
                    failureCount = 1
                )
            )
        }
        writeEntries(entries)
    }

    /** Records like/dislike feedback for a candidate, creating an entry if none exists. */
    suspend fun recordFeedback(candidateId: String, isLiked: Boolean, isDisliked: Boolean) {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.candidateId == candidateId }
        val now = System.currentTimeMillis()

        if (index >= 0) {
            entries[index] = entries[index].copy(
                isLiked = isLiked,
                isDisliked = isDisliked,
                timestampEpochMs = now
            )
        } else {
            entries.add(
                AmbientHistoryEntry(
                    candidateId = candidateId,
                    timestampEpochMs = now,
                    durationSecondsShown = 0L,
                    completed = false,
                    isLiked = isLiked,
                    isDisliked = isDisliked
                )
            )
        }
        writeEntries(entries)
    }

    /** Returns the timestamp in epoch ms when the candidate was last played, or null. */
    suspend fun getLastPlayedTime(candidateId: String): Long? =
        readEntries().firstOrNull { it.candidateId == candidateId }?.timestampEpochMs

    /** Returns true if the candidate was played within the last [days] days. */
    suspend fun hasBeenPlayedWithin(candidateId: String, days: Int = 7): Boolean {
        val entry = readEntries().firstOrNull { it.candidateId == candidateId } ?: return false
        val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
        return entry.timestampEpochMs >= cutoff
    }

    /** Returns the accumulated failure count for a candidate (0 if unknown). */
    suspend fun getFailureCount(candidateId: String): Int =
        readEntries().firstOrNull { it.candidateId == candidateId }?.failureCount ?: 0

    /** Returns true if the candidate is currently marked as disliked. */
    suspend fun isDisliked(candidateId: String): Boolean =
        readEntries().firstOrNull { it.candidateId == candidateId }?.isDisliked ?: false

    /** Returns true if the candidate is currently marked as liked. */
    suspend fun isLiked(candidateId: String): Boolean =
        readEntries().firstOrNull { it.candidateId == candidateId }?.isLiked ?: false

    /**
     * Returns the most recently played categories (newest first), de-duplicated,
     * limited to [limit] entries.
     */
    suspend fun getRecentCategories(limit: Int = 5): List<String> =
        readEntries()
            .asSequence()
            .filter { !it.category.isNullOrBlank() }
            .sortedByDescending { it.timestampEpochMs }
            .mapNotNull { it.category }
            .distinct()
            .take(limit)
            .toList()

    /** Clears all stored history. */
    suspend fun clearHistory() {
        context.historyDataStore.edit { prefs -> prefs.remove(historyKey) }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
