package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// TTL tiers - the architectural fix
const val ONGOING_WITH_AIRDATE_BUFFER_MS = 1L * 60 * 60 * 1000 // 1h before air date
const val ONGOING_NO_AIRDATE_TTL_MS = 2L * 60 * 60 * 1000 // 2 hours
const val ENDED_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
const val CANCELED_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
const val UNKNOWN_TTL_MS = 4L * 60 * 60 * 1000 // 4 hours

/**
 * Computes the optimal revalidation deadline for a series based on its air status
 * and known next episode schedule.
 */
fun computeDeadline(
    status: SeriesAirStatus,
    nextAirTimestampMs: Long?,
    nowMs: Long = System.currentTimeMillis()
): Long {
    // Rule 1: If we know exactly when the next episode airs, wake up 1h before
    if (nextAirTimestampMs != null && nextAirTimestampMs > nowMs) {
        val airDateDeadline = nextAirTimestampMs - ONGOING_WITH_AIRDATE_BUFFER_MS
        // If air date is imminent (< 1h away), revalidate in 5 minutes
        return if (airDateDeadline <= nowMs) nowMs + 5L * 60 * 1000
        else airDateDeadline
    }

    // Rule 2+: Status-based TTL
    return nowMs + when (status) {
        SeriesAirStatus.ONGOING -> ONGOING_NO_AIRDATE_TTL_MS
        SeriesAirStatus.ENDED -> ENDED_TTL_MS
        SeriesAirStatus.CANCELED -> CANCELED_TTL_MS
        SeriesAirStatus.UNKNOWN -> UNKNOWN_TTL_MS
    }
}

/**
 * Shared holder for fully-watched series IDs derived from the CW pipeline.
 * Updated by HomeViewModel, observed by any screen that needs series watched badges.
 * Persisted per profile so badges appear instantly on cold start.
 *
 * Uses per-series event-driven revalidation state instead of static lockouts.
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val REVALIDATION_STATE_KEY = stringPreferencesKey("revalidation_state_v2")
        private val VALIDATION_RESET_KEY = intPreferencesKey("validation_reset_version")
        private const val VALIDATION_RESET_VERSION = 4 // bump to force migration
        private const val MAX_DEADLINE_THRESHOLD = Long.MAX_VALUE - 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    @Volatile
    private var revalidationStates: Map<String, SeriesRevalidationState> = emptyMap()
    @Volatile
    private var loadedForProfileId: Int? = null

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    suspend fun loadFromDisk(profileId: Int = profileManager.activeProfileId.value) {
        if (loadedForProfileId == profileId) return
        val prefs = store(profileId).data.first()
        val persisted = prefs[KEY] ?: emptySet()
        val resetVersion = prefs[VALIDATION_RESET_KEY] ?: 0
        if (resetVersion < VALIDATION_RESET_VERSION) {
            revalidationStates = emptyMap()
            store(profileId).edit { p ->
                p.remove(REVALIDATION_STATE_KEY)
                p.remove(stringPreferencesKey("revalidate_after"))
                p.remove(stringSetPreferencesKey("ongoing_series_ids"))
                p[VALIDATION_RESET_KEY] = VALIDATION_RESET_VERSION
            }
        } else {
            revalidationStates = parseRevalidationStates(prefs[REVALIDATION_STATE_KEY])
                .filter { (id, state) ->
                    id in persisted && state.deadline < MAX_DEADLINE_THRESHOLD
                }
        }
        if (_fullyWatchedSeriesIds.value.isEmpty() && persisted.isNotEmpty()) {
            _fullyWatchedSeriesIds.value = persisted
        }
        loadedForProfileId = profileId
    }

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
        val profileId = profileManager.activeProfileId.value
        scope.launch {
            store(profileId).edit { prefs -> prefs[KEY] = ids }
        }
    }

    /** Clear in-memory state only — does NOT touch DataStore on disk. */
    fun clearInMemory() {
        _fullyWatchedSeriesIds.value = emptySet()
        revalidationStates = emptyMap()
    }

    /**
     * Update badge IDs and mark the given series as freshly validated.
     * Records per-series revalidation state based on air status and next scheduled episode.
     */
    @Synchronized
    fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        seriesStates: Map<String, SeriesValidationInput> = emptyMap(),
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val idsChanged = _fullyWatchedSeriesIds.value != ids
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val updated = revalidationStates.toMutableMap()
        var statesChanged = false

        validatedIds.forEach { id ->
            if (id in ids) {
                val input = seriesStates[id]
                val status = input?.status ?: SeriesAirStatus.UNKNOWN
                val nextAir = input?.nextAirTimestampMs
                val deadline = computeDeadline(status, nextAir, now)
                val newState = SeriesRevalidationState(
                    deadline = deadline,
                    status = status,
                    nextAirTimestampMs = nextAir,
                    lastValidatedMs = now
                )
                if (updated[id] != newState) {
                    updated[id] = newState
                    statesChanged = true
                }
            } else {
                if (updated.remove(id) != null) {
                    statesChanged = true
                }
            }
        }

        if (idsChanged) {
            val keysToRetain = ids + validatedIds
            val sizeBefore = updated.size
            updated.keys.retainAll(keysToRetain)
            if (updated.size != sizeBefore) statesChanged = true
        }

        revalidationStates = updated
        if (idsChanged || statesChanged) {
            scope.launch {
                store(profileId).edit { prefs ->
                    prefs[KEY] = ids
                    prefs[REVALIDATION_STATE_KEY] = gson.toJson(updated)
                }
            }
        }
    }

    /**
     * Returns true if the given series does not yet need re-validation.
     * The deadline itself encodes whether the series is ongoing — no separate check needed.
     */
    fun isSeriesValidationFresh(contentId: String): Boolean {
        if (contentId !in _fullyWatchedSeriesIds.value) return false
        val state = revalidationStates[contentId] ?: return false
        if (state.deadline >= MAX_DEADLINE_THRESHOLD) return false
        return System.currentTimeMillis() < state.deadline
    }

    /**
     * Returns true if the series has ever been validated (has an entry in the map).
     */
    fun hasBeenValidated(contentId: String): Boolean =
        contentId in revalidationStates

    /**
     * Clears all revalidation deadlines, forcing every series to be re-evaluated
     * on the next CW pipeline cycle. Called when the user manually clears the CW cache.
     */
    fun clearValidationState(profileId: Int = profileManager.activeProfileId.value) {
        revalidationStates = emptyMap()
        scope.launch {
            store(profileId).edit { prefs -> prefs.remove(REVALIDATION_STATE_KEY) }
        }
    }

    /**
     * Filters the given set to only those series that need re-validation
     * (past their revalidation deadline or never validated).
     */
    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val state = revalidationStates[id] ?: return@filter true
            now >= state.deadline
        }.toSet()
    }

    /** Returns the persisted status for a series, or null if never validated. */
    fun getSeriesStatus(contentId: String): SeriesAirStatus? =
        revalidationStates[contentId]?.status

    /** Returns true if series is known to be actively airing. */
    fun isKnownOngoing(contentId: String): Boolean =
        revalidationStates[contentId]?.status == SeriesAirStatus.ONGOING

    /** Returns the persisted next air timestamp in epoch ms for a series, or null if unknown. */
    fun getNextAirTimestamp(contentId: String): Long? =
        revalidationStates[contentId]?.nextAirTimestampMs

    private fun parseRevalidationStates(json: String?): Map<String, SeriesRevalidationState> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, SeriesRevalidationState>>() {}.type
            gson.fromJson<Map<String, SeriesRevalidationState>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
