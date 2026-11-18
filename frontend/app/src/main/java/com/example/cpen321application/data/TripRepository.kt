package com.example.cpen321application.data

import com.example.cpen321application.model.MeetupTrip
import org.json.JSONArray
import org.json.JSONObject

/** Live commute tracking: the caller's own trip to a finalized meetup and everyone's trips for the map. */
class TripRepository(private val api: ApiClient) {
    /** Every trip of the meetup; a location is only included for members who share theirs right now. */
    suspend fun list(meetupId: String): ApiResult<List<MeetupTrip>> =
        api.get("meetups/$meetupId/trips").parse { JSONArray(it).mapObjects(::parseTrip) }

    /** Starts the caller's trip, or resumes sharing on their active one; the server keeps the first start time. */
    suspend fun start(meetupId: String): ApiResult<MeetupTrip> =
        api.post("meetups/$meetupId/trip/start").parse { parseTrip(JSONObject(it)) }

    /** Ends the caller's trip; the server computes the actual commute time. */
    suspend fun arrive(meetupId: String): ApiResult<MeetupTrip> =
        api.post("meetups/$meetupId/trip/arrive").parse { parseTrip(JSONObject(it)) }

    /** Pauses location sharing without ending the trip. */
    suspend fun stopSharing(meetupId: String): ApiResult<Unit> =
        api.post("meetups/$meetupId/trip/stop").parse { }

    /** Reports the device's latest position while the caller shares their trip. */
    suspend fun updateLocation(meetupId: String, lat: Double, lng: Double, accuracyM: Float): ApiResult<MeetupTrip> {
        val body = JSONObject().put("lat", lat).put("lng", lng).put("accuracyM", accuracyM)
        return api.put("meetups/$meetupId/trip/location", body).parse { parseTrip(JSONObject(it)) }
    }
}
