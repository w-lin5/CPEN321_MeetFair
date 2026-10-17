package com.example.cpen321application.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GoogleCalendarRepository
import com.example.cpen321application.model.CalendarEvent
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.toListUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GoogleCalendarViewModel(
    private val repository: GoogleCalendarRepository,
) : ViewModel() {

    private val _eventsState = MutableStateFlow<UiState<List<CalendarEvent>>>(UiState.Loading)
    val eventsState: StateFlow<UiState<List<CalendarEvent>>> = _eventsState.asStateFlow()

    private val _syncState = MutableStateFlow(FormState<String>())
    val syncState: StateFlow<FormState<String>> = _syncState.asStateFlow()

    private val _exportStates = MutableStateFlow<Map<String, FormState<String>>>(emptyMap())
    val exportStates: StateFlow<Map<String, FormState<String>>> = _exportStates.asStateFlow()

    init {
        loadEvents()
    }

    fun loadEvents() {
        _eventsState.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.getCalendarEvents()
            _eventsState.value = result.toListUiState()
        }
    }

    fun setSyncError(errorMessage: String) {
        _syncState.value = FormState(submitting = false, error = errorMessage)
    }

    fun syncCalendar(accessToken: String? = null) {
        _syncState.value = FormState(submitting = true)
        viewModelScope.launch {
            val result = repository.syncGoogleCalendar(accessToken)
            _syncState.value = when (result) {
                is ApiResult.Success -> FormState(submitting = false, result = result.data)
                is ApiResult.Failure -> {
                    val msg = if (result.error.message.contains("Invalid Request", ignoreCase = true)) {
                        "Calendar sync failed: Google Calendar authorization token is required. Please grant Google Calendar permissions."
                    } else {
                        result.error.message
                    }
                    FormState(submitting = false, error = msg)
                }
            }
            if (result is ApiResult.Success) {
                loadEvents()
            }
        }
    }

    fun exportEvent(event: CalendarEvent, accessToken: String? = null) {
        val eventKey = event.id.ifEmpty { event.hashCode().toString() }
        _exportStates.value = _exportStates.value + (eventKey to FormState(submitting = true))
        viewModelScope.launch {
            when (val result = repository.exportCalendarEvent(event, accessToken)) {
                is ApiResult.Success -> {
                    _exportStates.value = _exportStates.value + (eventKey to FormState(result = result.data))
                    loadEvents()
                }
                is ApiResult.Failure -> _exportStates.value = _exportStates.value +
                    (eventKey to FormState(error = result.error.message))
            }
        }
    }

    /**
     * TODO: Remove this when recommendation feature implemented.file
     * Adds an unsynced sample event to the current list to test the "Add to Google Calendar" feature.
     */
    fun addSampleEvent() {
        val sampleId = "sample-" + System.currentTimeMillis()
        val sampleEvent = CalendarEvent(
            id = sampleId,
            externalId = null,
            source = "app",
            title = "MeetFair Sample Study Session",
            description = "Auto-generated sample event to test exporting to Google Calendar",
            startTime = "2026-10-25T14:00:00Z",
            endTime = "2026-10-25T15:00:00Z",
            location = "Kaiser Building Room 2020",
            timezone = "UTC",
            syncedAt = null,
        )
        val currentList = when (val state = _eventsState.value) {
            is UiState.Success -> state.data
            else -> emptyList()
        }
        _eventsState.value = UiState.Success(listOf(sampleEvent) + currentList)
    }

    /**
     * Programmatically creates and exports an event to the backend database and external Google Calendar.
     * Designed for automated event generation without manual entry.
     */
    fun createAndExportEvent(
        title: String,
        description: String? = null,
        startTime: String,
        endTime: String,
        location: String? = null,
        timezone: String = "UTC",
        accessToken: String? = null,
    ) {
        val autoEvent = CalendarEvent(
            id = "",
            title = title,
            description = description,
            startTime = startTime,
            endTime = endTime,
            location = location,
            timezone = timezone,
        )
        exportEvent(autoEvent, accessToken)
    }

    class Factory(private val repository: GoogleCalendarRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoogleCalendarViewModel(repository) as T
        }
    }
}
