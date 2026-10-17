package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarAttendee
import com.example.cpen321application.model.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarRepositoryTest {

    @Test
    fun calendarEvent_isSyncedToExternalCalendar_checksExternalIdAndSourceAndSyncedAt() {
        val googleEvent = CalendarEvent(
            id = "1",
            externalId = "g-123",
            source = "google",
            title = "Imported Google Meeting",
            syncedAt = "2026-10-20T10:00:00Z"
        )
        assertTrue(googleEvent.isSyncedToExternalCalendar)

        val localUnsyncedEvent = CalendarEvent(
            id = "2",
            externalId = null,
            source = "meetfair",
            title = "Local Generated Event",
            syncedAt = null
        )
        assertFalse(localUnsyncedEvent.isSyncedToExternalCalendar)
    }

    @Test
    fun calendarEvent_defaultsAndAttendees() {
        val attendee = CalendarAttendee(email = "alex@example.com", displayName = "Alex")
        val event = CalendarEvent(
            id = "3",
            title = "Sprint Review",
            description = "Bi-weekly sprint review",
            startTime = "2026-10-20T10:00:00-07:00",
            endTime = "2026-10-20T11:00:00-07:00",
            timezone = "America/Vancouver",
            location = "Room 204",
            attendees = listOf(attendee)
        )

        assertEquals("Sprint Review", event.title)
        assertEquals("America/Vancouver", event.timezone)
        assertEquals(1, event.attendees.size)
        assertEquals("alex@example.com", event.attendees[0].email)
        assertEquals("Alex", event.attendees[0].displayName)
        assertNull(event.externalId)
    }
}
