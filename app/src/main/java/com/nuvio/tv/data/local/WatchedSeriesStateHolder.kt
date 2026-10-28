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

/**
 * Shared holder for fully-watched series IDs derived from the CW pipeline.
 * Updated by HomeViewModel, observed by any screen that needs series watched badges.
 * Persisted per profile so badges appear instantly on cold start.
 *
 * Uses per-series revalidation deadlines instead of a global TTL:
 * - Default: 7 days after last validation
 * - If a known upcoming season release date exists, revalidate at that date
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "watched_series_cache"
        private val KEY = stringSetPreferencesKey("fully_watched_ids")
        private val REVALIDATE_KEY = stringPreferencesKey("revalidate_after")
        private val VALIDATION_RESET_KEY = intPreferencesKey("validation_reset_version")
        private val ONGOING_KEY = stringSetPreferencesKey("ongoing_series_ids")
        private const val VALIDATION_RESET_VERSION = 3
        private const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours (1 day)
        private const val ONGOING_TTL_MS = 2L * 60 * 60 * 1000 // 2 hours
        private const val MAX_DEADLINE_THRESHOLD = Long.MAX_VALUE - 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    private val _ongoingSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val ongoingSeriesIds: StateFlow<Set<String>> = _ongoingSeriesIds.asStateFlow()

    /** Per-series revalidation deadline (contentId → epochMs when re-check is needed). */
    @Volatile
    private var revalidateAfterMap: Map<String, Long> = emptyMap()
    @Volatile
    private var loadedForProfileId: Int? = null

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    suspend fun loadFromDisk(profileId: Int = profileManager.activeProfileId.value) {
        if (loadedForProfileId == profileId) return
        val prefs = store(profileId).data.first()
        val persisted = prefs[KEY] ?: emptySet()
        val persistedOngoing = prefs[ONGOING_KEY] ?: emptySet()
        _ongoingSeriesIds.value = persistedOngoing
        val resetVersion = prefs[VALIDATION_RESET_KEY] ?: 0
        if (resetVersion < VALIDATION_RESET_VERSION) {
            revalidateAfterMap = emptyMap()
            store().edit { p ->
                p.remove(REVALIDATE_KEY)
                p[VALIDATION_RESET_KEY] = VALIDATION_RESET_VERSION
            }
        } else {
            revalidateAfterMap = parseTimestamps(prefs[REVALIDATE_KEY])
                .filter { (id, deadline) ->
                    id in persisted && deadline < MAX_DEADLINE_THRESHOLD
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
        _ongoingSeriesIds.value = emptySet()
        revalidateAfterMap = emptyMap()
    }

    /**
     * Update badge IDs and mark the given series as freshly validated.
     * [revalidateAt] allows setting per-series deadlines (e.g. upcoming season premiere).
     * Series not in [revalidateAt] get the default 7-day TTL.
     */
    @Synchronized
    fun updateWithValidation(
        ids: Set<String>,
        validatedIds: Set<String>,
        revalidateAt: Map<String, Long> = emptyMap(),
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val idsChanged = _fullyWatchedSeriesIds.value != ids
        _fullyWatchedSeriesIds.value = ids
        val now = System.currentTimeMillis()
        val defaultDeadline = now + DEFAULT_TTL_MS
        val ongoingDeadline = now + ONGOING_TTL_MS
        val updated = revalidateAfterMap.toMutableMap()
        var deadlinesChanged = false
        validatedIds.forEach { id ->
            if (id in ids) {
                var newDeadline = revalidateAt[id] ?: defaultDeadline
                // Ongoing series must be revalidated promptly — cap the deadline.
                if (isOngoingSeries(id) && newDeadline > ongoingDeadline) {
                    newDeadline = ongoingDeadline
                }
                if (newDeadline < MAX_DEADLINE_THRESHOLD) {
                    if (updated[id] != newDeadline) {
                        updated[id] = newDeadline
                        deadlinesChanged = true
                    }
                }
            } else {
                if (updated.remove(id) != null) {
                    deadlinesChanged = true
                }
            }
        }
        // Prune entries for series no longer in badge set AND not freshly validated.
        // Skip pruning when only adding deadlines (validatedIds not in ids) to avoid
        // removing deadlines set by earlier calls in the same async inject loop.
        if (idsChanged) {
            val keysToRetain = ids + validatedIds
            val sizeBefore = updated.size
            updated.keys.retainAll(keysToRetain)
            if (updated.size != sizeBefore) deadlinesChanged = true
        }

        revalidateAfterMap = updated
        if (idsChanged || deadlinesChanged) {
            scope.launch {
                store(profileId).edit { prefs ->
                    prefs[KEY] = ids
                    prefs[REVALIDATE_KEY] = gson.toJson(updated)
                }
            }
        }
    }

    /**
     * Returns true if the given series does not yet need re-validation.
     */
    fun isSeriesValidationFresh(contentId: String): Boolean {
        if (isOngoingSeries(contentId)) return false
        if (contentId !in _fullyWatchedSeriesIds.value) return false
        val deadline = revalidateAfterMap[contentId] ?: return false
        if (deadline >= MAX_DEADLINE_THRESHOLD) return false
        return System.currentTimeMillis() < deadline
    }

    /**
     * Returns true if the series has ever been validated (has an entry in the map).
     */
    fun hasBeenValidated(contentId: String): Boolean {
        return contentId in revalidateAfterMap
    }

    /**
     * Clears all revalidation deadlines, forcing every series to be re-evaluated
     * on the next CW pipeline cycle. Called when the user manually clears the CW cache.
     */
    fun clearValidationState(profileId: Int = profileManager.activeProfileId.value) {
        revalidateAfterMap = emptyMap()
        scope.launch {
            store(profileId).edit { prefs -> prefs.remove(REVALIDATE_KEY) }
        }
    }

    /**
     * Filters the given set to only those series that need re-validation
     * (past their revalidation deadline or never validated).
     */
    fun filterStaleIds(ids: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        return ids.filter { id ->
            val deadline = revalidateAfterMap[id] ?: return@filter true
            now >= deadline
        }.toSet()
    }

    /**
     * Marks or unmarks a single series as ongoing/actively-airing.
     * Ongoing series are never locked out by caught-up deadlines and are
     * revalidated promptly.
     */
    fun markOngoing(
        contentId: String,
        isOngoing: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val current = _ongoingSeriesIds.value
        val updated = if (isOngoing) current + contentId else current - contentId
        if (updated == current) return
        _ongoingSeriesIds.value = updated
        scope.launch {
            store(profileId).edit { prefs -> prefs[ONGOING_KEY] = updated }
        }
    }

    /**
     * Batch marks or unmarks series as ongoing/actively-airing.
     */
    fun markOngoingBatch(
        contentIds: Set<String>,
        isOngoing: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val current = _ongoingSeriesIds.value
        val updated = if (isOngoing) current + contentIds else current - contentIds
        if (updated == current) return
        _ongoingSeriesIds.value = updated
        scope.launch {
            store(profileId).edit { prefs -> prefs[ONGOING_KEY] = updated }
        }
    }

    /** Returns true if the given series is currently marked as ongoing/actively-airing. */
    fun isOngoingSeries(contentId: String): Boolean = contentId in _ongoingSeriesIds.value

    private fun parseTimestamps(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson<Map<String, Long>>(json, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
