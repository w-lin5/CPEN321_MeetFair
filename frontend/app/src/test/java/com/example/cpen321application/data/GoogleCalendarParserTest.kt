package com.example.cpen321application.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarParserTest {

    @Test
    fun parseCalendarEvents_directJsonArray() {
        val json = """
            [
                {
                    "id": "evt-1",
                    "title": "Team Sync",
                    "description": "Weekly status meeting",
                    "startTime": "2026-10-15T10:00:00Z",
                    "endTime": "2026-10-15T11:00:00Z",
                    "location": "Room 101"
                },
                {
                    "id": "evt-2",
                    "summary": "Project Kickoff",
                    "start": "2026-10-16T14:00:00Z",
                    "end": "2026-10-16T15:00:00Z"
                }
            ]
        """.trimIndent()

        val events = parseCalendarEvents(json)

        assertEquals(2, events.size)
        assertEquals("evt-1", events[0].id)
        assertEquals("Team Sync", events[0].title)
        assertEquals("Weekly status meeting", events[0].description)
        assertEquals("2026-10-15T10:00:00Z", events[0].startTime)
        assertEquals("2026-10-15T11:00:00Z", events[0].endTime)
        assertEquals("Room 101", events[0].location)

        assertEquals("evt-2", events[1].id)
        assertEquals("Project Kickoff", events[1].title)
        assertEquals("2026-10-16T14:00:00Z", events[1].startTime)
        assertEquals("2026-10-16T15:00:00Z", events[1].endTime)
    }

    @Test
    fun parseCalendarEvents_nestedJsonObjectWithItems() {
        val json = """
            {
                "items": [
                    {
                        "id": "google-evt-123",
                        "summary": "1:1 with Manager",
                        "description": "Performance review",
                        "start": { "dateTime": "2026-10-20T09:00:00Z" },
                        "end": { "dateTime": "2026-10-20T09:30:00Z" },
                        "location": "Online"
                    }
                ]
            }
        """.trimIndent()

        val events = parseCalendarEvents(json)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("google-evt-123", event.id)
        assertEquals("1:1 with Manager", event.title)
        assertEquals("Performance review", event.description)
        assertEquals("2026-10-20T09:00:00Z", event.startTime)
        assertEquals("2026-10-20T09:30:00Z", event.endTime)
        assertEquals("Online", event.location)
    }

    @Test
    fun parseCalendarEvents_newBackendSchema() {
        val json = """
            [
                {
                    "id": "db-evt-1",
                    "externalId": "google-ext-99",
                    "source": "google",
                    "title": "Synced Standup",
                    "description": "Daily standup meeting",
                    "startTime": "2026-10-22T08:00:00Z",
                    "endTime": "2026-10-22T08:30:00Z",
                    "busy": true,
                    "syncedAt": "2026-10-22T08:31:00Z"
                }
            ]
        """.trimIndent()

        val events = parseCalendarEvents(json)

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("db-evt-1", event.id)
        assertEquals("google-ext-99", event.externalId)
        assertEquals("google", event.source)
        assertEquals("Synced Standup", event.title)
        assertTrue(event.busy)
        assertEquals("2026-10-22T08:31:00Z", event.syncedAt)
        assertTrue(event.isSyncedToExternalCalendar)
    }

    @Test
    fun parseCalendarEvents_emptyOrInvalid() {
        assertTrue(parseCalendarEvents("").isEmpty())
        assertTrue(parseCalendarEvents("{}").isEmpty())
        assertTrue(parseCalendarEvents("   ").isEmpty())
    }

    @Test
    fun parseSyncResponse_validMessage() {
        val json = """{"message": "Google Calendar synced successfully", "count": 5}"""
        val message = parseSyncResponse(json)
        assertEquals("Google Calendar synced successfully", message)
    }

    @Test
    fun parseSyncResponse_eventExportSuccessMessage() {
        val json = """{"message": "Event created and exported to Google Calendar successfully", "id": "created-123"}"""
        val message = parseSyncResponse(json)
        assertEquals("Event created and exported to Google Calendar successfully", message)
    }

    @Test
    fun parseSyncResponse_emptyResponse() {
        val message = parseSyncResponse("")
        assertEquals("Calendar synced successfully", message)
    }
}
