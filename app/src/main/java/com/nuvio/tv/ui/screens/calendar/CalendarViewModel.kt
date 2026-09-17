package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.simkl.calendar.CalendarCategory
import com.nuvio.tv.data.simkl.calendar.CalendarDayGroup
import com.nuvio.tv.data.simkl.calendar.CalendarItemType
import com.nuvio.tv.data.simkl.calendar.CalendarMediaItem
import com.nuvio.tv.data.simkl.calendar.SimklCalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel backing the Calendar screen.
 *
 * Responsible for loading calendar data from [SimklCalendarRepository], tracking the
 * currently selected day/category, and exposing a single [CalendarUiState] stream to the UI.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: SimklCalendarRepository
) : ViewModel() {

    /**
     * Immutable snapshot of the Calendar screen state.
     */
    data class CalendarUiState(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val days: List<CalendarDayGroup> = emptyList(),
        val selectedDayIndex: Int = 0,
        val selectedCategory: CalendarCategory = CalendarCategory.ALL,
        val focusedItem: CalendarMediaItem? = null,
        val totalAvailableToStream: Int = 0
    ) {
        /** The day group currently selected, or null if no days are loaded. */
        val currentDayGroup: CalendarDayGroup?
            get() = days.getOrNull(selectedDayIndex)

        /** Items of the current day filtered by the selected category. */
        val filteredItems: List<CalendarMediaItem>
            get() {
                val dayItems = currentDayGroup?.items ?: emptyList()
                return when (selectedCategory) {
                    CalendarCategory.ALL -> dayItems
                    CalendarCategory.TV_EPISODES ->
                        dayItems.filter { it.type == CalendarItemType.TV_EPISODE }
                    CalendarCategory.DIGITAL_STREAMING ->
                        dayItems.filter { it.isAvailableToStream || it.type == CalendarItemType.DIGITAL_MOVIE }
                    CalendarCategory.MOVIES ->
                        dayItems.filter {
                            it.type == CalendarItemType.DIGITAL_MOVIE ||
                                it.type == CalendarItemType.THEATRICAL_MOVIE
                        }
                }
            }
    }

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        loadCalendar()
    }

    /**
     * Loads calendar data from the repository.
     *
     * @param forceRefresh when true, bypasses any cached data in the repository.
     */
    fun loadCalendar(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getCalendarData(forceRefresh)
                .onSuccess { data ->
                    _uiState.update { current ->
                        val updated = current.copy(
                            isLoading = false,
                            errorMessage = null,
                            days = data.days,
                            totalAvailableToStream = data.availableToStreamCount
                        )
                        // Reset selection if the previously selected index is now out of bounds.
                        val safeIndex = updated.selectedDayIndex
                            .coerceIn(0, (updated.days.size - 1).coerceAtLeast(0))
                        val withIndex = updated.copy(selectedDayIndex = safeIndex)
                        withIndex.copy(focusedItem = withIndex.filteredItems.firstOrNull())
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Failed to load calendar"
                        )
                    }
                }
        }
    }

    /**
     * Selects the day at [index] and focuses the first item in the filtered list.
     */
    fun selectDay(index: Int) {
        _uiState.update { current ->
            if (index !in current.days.indices) return@update current
            val updated = current.copy(selectedDayIndex = index)
            updated.copy(focusedItem = updated.filteredItems.firstOrNull())
        }
    }

    /**
     * Selects the given [category] and focuses the first item in the filtered list.
     */
    fun selectCategory(category: CalendarCategory) {
        _uiState.update { current ->
            val updated = current.copy(selectedCategory = category)
            updated.copy(focusedItem = updated.filteredItems.firstOrNull())
        }
    }

    /**
     * Records the item that currently has focus in the UI.
     */
    fun onItemFocused(item: CalendarMediaItem) {
        _uiState.update { it.copy(focusedItem = item) }
    }

    /**
     * Retries loading the calendar, forcing a refresh from the repository.
     */
    fun retry() {
        loadCalendar(forceRefresh = true)
    }
}
