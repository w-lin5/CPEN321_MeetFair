package com.example.cpen321application.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarParsersTest {
    @Test
    fun parseCalendarEventResult_readsIdAndLink() {
        val result = parseCalendarEventResult(
            JSONObject("""{ "eventId": "g-123", "htmlLink": "https://calendar.google.com/event/123" }"""),
        )

        assertEquals("g-123", result.eventId)
        assertEquals("https://calendar.google.com/event/123", result.htmlLink)
    }

    @Test
    fun parseCalendarEventResult_nullLink() {
        assertNull(parseCalendarEventResult(JSONObject("""{ "eventId": "g-1", "htmlLink": null }""")).htmlLink)
    }

    @Test
    fun parseParticipant_readsCalendarEventId() {
        val base = """"userId": "u1", "startLocation": { "lat": 1, "lng": 2 }, "travelMode": "WALK", "preferences": []"""

        assertEquals("g-1", parseParticipant(JSONObject("""{ $base, "calendarEventId": "g-1" }""")).calendarEventId)
        assertNull(parseParticipant(JSONObject("{ $base }")).calendarEventId)
    }
}
