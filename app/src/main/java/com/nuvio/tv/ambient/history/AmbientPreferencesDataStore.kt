package com.nuvio.tv.ambient.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// DataStore instance scoped to the application context for preference weights.
private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "ambient_preferences")

/**
 * Tracks user category preference weights and liked/disliked category sets.
 *
 * Weights are stored per category (e.g. liked category +1.0, disliked -1.0) and can be
 * adjusted incrementally. Liked/disliked sets are maintained alongside weights so callers
 * can query membership directly.
 */
@Singleton
class AmbientPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val likedCategoriesKey = stringSetPreferencesKey("ambient_liked_categories")
    private val dislikedCategoriesKey = stringSetPreferencesKey("ambient_disliked_categories")

    /** Builds the per-category weight preference key. */
    private fun weightKey(category: String) = doublePreferencesKey("ambient_weight_$category")

    /** Returns the current weight for a category (0.0 if unset). */
    suspend fun getCategoryWeight(category: String): Double =
        context.preferencesDataStore.data.map { it[weightKey(category)] ?: 0.0 }.first()

    /**
     * Adjusts a category's weight by [delta]. Positive deltas mark the category as liked,
     * negative deltas mark it as disliked, and the opposite set is cleared accordingly.
     */
    suspend fun adjustCategoryWeight(category: String, delta: Double) {
        context.preferencesDataStore.edit { prefs ->
            val key = weightKey(category)
            val updated = (prefs[key] ?: 0.0) + delta
            prefs[key] = updated

            val liked = prefs[likedCategoriesKey]?.toMutableSet() ?: mutableSetOf()
            val disliked = prefs[dislikedCategoriesKey]?.toMutableSet() ?: mutableSetOf()

            // A category cannot be both liked and disliked; the latest signal wins.
            when {
                delta > 0.0 -> {
                    liked.add(category)
                    disliked.remove(category)
                }
                delta < 0.0 -> {
                    disliked.add(category)
                    liked.remove(category)
                }
            }

            prefs[likedCategoriesKey] = liked
            prefs[dislikedCategoriesKey] = disliked
        }
    }

    /** Returns the set of categories currently marked as liked. */
    suspend fun getLikedCategories(): Set<String> =
        context.preferencesDataStore.data.map { it[likedCategoriesKey] ?: emptySet() }.first()

    /** Returns the set of categories currently marked as disliked. */
    suspend fun getDislikedCategories(): Set<String> =
        context.preferencesDataStore.data.map { it[dislikedCategoriesKey] ?: emptySet() }.first()

    /** Clears all stored preference weights and liked/disliked sets. */
    suspend fun resetPreferences() {
        context.preferencesDataStore.edit { prefs -> prefs.clear() }
    }
}
