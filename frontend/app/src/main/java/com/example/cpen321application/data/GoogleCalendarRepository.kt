package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEvent
import org.json.JSONArray
import org.json.JSONObject

class GoogleCalendarRepository(private val api: ApiClient) {

    /**
     * Triggers backend import/sync of Google Calendar events into database.
     * POST /api/integrations/google-calendar/sync
     * Accepts a Google OAuth access token with calendar.events scope.
     */
    suspend fun syncGoogleCalendar(accessToken: String? = null): ApiResult<String> {
        val body = if (accessToken.isNullOrEmpty()) {
            JSONObject()
        } else {
            JSONObject()
                .put("accessToken", accessToken)
        }
        return api.post("integrations/google-calendar/sync", body).parse(::parseSyncResponse)
    }

    /**
     * Fetches all imported calendar events from backend database.
     * GET /api/integrations/google-calendar/events
     */
    suspend fun getCalendarEvents(): ApiResult<List<CalendarEvent>> {
        return api.get("integrations/google-calendar/events").parse(::parseCalendarEvents)
    }

    /**
     * Adds an event to the calendar in the backend database and external Google Calendar.
     * POST /api/integrations/google-calendar/events
     */
    suspend fun exportCalendarEvent(event: CalendarEvent, accessToken: String? = null): ApiResult<String> {
        val attendeeEmailsArray = JSONArray()
        event.attendees.forEach { attendee ->
            attendeeEmailsArray.put(attendee.email)
        }

        val body = JSONObject()
            .put("title", event.title)
            .put("description", event.description.orEmpty())
            .put("startTime", event.startTime.orEmpty())
            .put("endTime", event.endTime.orEmpty())
            .put("timezone", event.timezone)
            .put("location", event.location.orEmpty())
            .put("attendeeEmails", attendeeEmailsArray)

        if (!accessToken.isNullOrEmpty()) {
            body.put("accessToken", accessToken)
        }

        return api.post("integrations/google-calendar/events", body).parse(::parseSyncResponse)
    }
}
