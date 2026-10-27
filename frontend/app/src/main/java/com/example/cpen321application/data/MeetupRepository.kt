package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import org.json.JSONArray
import org.json.JSONObject

class MeetupRepository(private val api: ApiClient) {
    suspend fun delete(meetupId: String): ApiResult<Unit> = api.delete("meetups/$meetupId").parse { Unit }
    suspend fun listForGroup(groupId: String): ApiResult<List<Meetup>> =
        api.get("groups/$groupId/meetups").parse { JSONArray(it).mapObjects(::parseMeetup) }

    suspend fun get(meetupId: String): ApiResult<Meetup> =
        api.get("meetups/$meetupId").parse { parseMeetup(JSONObject(it)) }

    /** [scheduledAt] is an ISO-8601 instant. */
    suspend fun create(groupId: String, title: String, scheduledAt: String): ApiResult<Meetup> {
        val body = JSONObject().put("title", title).put("scheduledAt", scheduledAt)
        return api.post("groups/$groupId/meetups", body).parse { parseMeetup(JSONObject(it)) }
    }

    /** Adds or replaces the caller's entry; the server only accepts it while the meetup is in SETUP. */
    suspend fun submitParticipation(
        meetupId: String,
        startLocation: StartLocation,
        travelMode: TravelMode,
        preferences: Set<Preference>,
    ): ApiResult<Meetup> {
        val location = JSONObject()
            .put("lat", startLocation.lat)
            .put("lng", startLocation.lng)
            .put("label", startLocation.label)
        val body = JSONObject()
            .put("startLocation", location)
            .put("travelMode", travelMode.name)
            .put("preferences", JSONArray(preferences.map { it.name }))
        return api.put("meetups/$meetupId/participation", body).parse { parseMeetup(JSONObject(it)) }
    }

    /**
     * Adds a finalized meetup to the caller's own Google Calendar.
     * [accessToken] is a short-lived calendar.events token from [CalendarAuthorizer]; the server never stores it.
     */
    suspend fun addToCalendar(meetupId: String, accessToken: String): ApiResult<CalendarEventResult> =
        api.post("meetups/$meetupId/calendar-event", JSONObject().put("accessToken", accessToken))
            .parse { parseCalendarEventResult(JSONObject(it)) }
}
