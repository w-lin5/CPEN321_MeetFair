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

    class Factory(private val repository: GoogleCalendarRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GoogleCalendarViewModel(repository) as T
        }
    }
}
