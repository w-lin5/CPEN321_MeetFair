package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import com.example.cpen321application.notifications.MeetupReminders
import org.json.JSONArray
import org.json.JSONObject

/**
 * Meetups and the caller's participation in them. Every meetup that passes through here also updates
 * the device's start-time reminder, so reminders follow what the server says without extra requests.
 * [reminders] and [session] are left out by the alarm receiver's read-only instance.
 */
class MeetupRepository(
    private val api: ApiClient,
    private val reminders: MeetupReminders? = null,
    private val session: SessionStore? = null,
) {
    suspend fun listForGroup(groupId: String): ApiResult<List<Meetup>> =
        api.get("groups/$groupId/meetups").parse { JSONArray(it).mapObjects(::parseMeetup) }
            .also { result -> if (result is ApiResult.Success) result.data.forEach(::updateReminder) }

    suspend fun get(meetupId: String): ApiResult<Meetup> =
        api.get("meetups/$meetupId").parse { parseMeetup(JSONObject(it)) }.alsoUpdateReminder()

    /** [scheduledAt] is an ISO-8601 instant. */
    suspend fun create(groupId: String, title: String, scheduledAt: String): ApiResult<Meetup> {
        val body = JSONObject().put("title", title).put("scheduledAt", scheduledAt)
        return api.post("groups/$groupId/meetups", body).parse { parseMeetup(JSONObject(it)) }
    }

    /** Meetup organizer only. */
    suspend fun delete(meetupId: String): ApiResult<Unit> =
        api.delete("meetups/$meetupId").parse { }.alsoCancelReminder(meetupId)

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
            .alsoUpdateReminder()
    }

    /** Withdraws the caller's entry; only possible before voting starts. */
    suspend fun withdrawParticipation(meetupId: String): ApiResult<Unit> =
        api.delete("meetups/$meetupId/participation").parse { }.alsoCancelReminder(meetupId)

    /**
     * Adds a finalized meetup to the caller's own Google Calendar.
     * [accessToken] is a short-lived calendar.events token from [CalendarAuthorizer]; the server never stores it.
     */
    suspend fun addToCalendar(meetupId: String, accessToken: String): ApiResult<CalendarEventResult> =
        api.post("meetups/$meetupId/calendar-event", JSONObject().put("accessToken", accessToken))
            .parse { parseCalendarEventResult(JSONObject(it)) }

    /** Meetup organizer only: turns everyone's commute into Fair Credit and moves the meetup to SETTLED. */
    suspend fun settle(meetupId: String): ApiResult<Meetup> =
        api.post("meetups/$meetupId/settle").parse { parseMeetup(JSONObject(it)) }

    private fun updateReminder(meetup: Meetup) {
        reminders?.update(meetup, session?.currentUserId())
    }

    private fun ApiResult<Meetup>.alsoUpdateReminder(): ApiResult<Meetup> =
        also { if (it is ApiResult.Success) updateReminder(it.data) }

    private fun ApiResult<Unit>.alsoCancelReminder(meetupId: String): ApiResult<Unit> =
        also { if (it is ApiResult.Success) reminders?.cancel(meetupId) }
}
