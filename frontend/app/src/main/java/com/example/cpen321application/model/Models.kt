package com.example.cpen321application.model

data class User(val id: String, val email: String, val name: String)

data class CalendarEvent(
    val id: String,
    val externalId: String? = null,
    val source: String = "google",
    val title: String,
    val description: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val timezone: String = "UTC",
    val location: String? = null,
    val allDay: Boolean = false,
    val recurrence: List<String> = emptyList(),
    val attendees: List<CalendarAttendee> = emptyList(),
    val busy: Boolean = true,
    val syncedAt: String? = null,
) {
    val isSyncedToExternalCalendar: Boolean
        get() = !externalId.isNullOrEmpty() || source == "google" || !syncedAt.isNullOrEmpty()
}

data class CalendarAttendee(
    val email: String,
    val displayName: String? = null,
    val responseStatus: String = "needsAction",
)
