package com.example.cpen321application.data

import org.json.JSONArray
import org.json.JSONObject

data class SharedPosition(val lat: Double, val lng: Double, val accuracyM: Double, val updatedAt: String)
data class MeetupTrip(val userId: String, val name: String, val photoUrl: String?, val status: String,
    val sharing: Boolean, val startedAt: String, val arrivedAt: String?, val actualSeconds: Long?,
    val location: SharedPosition?)

internal fun parseTrip(o: JSONObject) = MeetupTrip(
    o.getString("userId"), o.optString("name", "You"), o.stringOrNull("photoUrl"), o.getString("status"),
    o.getBoolean("sharing"), o.getString("startedAt"), o.stringOrNull("arrivedAt"),
    if (o.isNull("actualSeconds")) null else o.getLong("actualSeconds"),
    o.optJSONObject("location")?.let { SharedPosition(it.getDouble("lat"), it.getDouble("lng"),
        it.getDouble("accuracyM"), it.getString("updatedAt")) },
)

class TripRepository(private val api: ApiClient) {
    suspend fun list(id: String): ApiResult<List<MeetupTrip>> = api.get("meetups/$id/trips")
        .parse { JSONArray(it).mapObjects(::parseTrip) }
    suspend fun start(id: String): ApiResult<MeetupTrip> = api.post("meetups/$id/trip/start").parse { parseTrip(JSONObject(it)) }
    suspend fun arrive(id: String): ApiResult<MeetupTrip> = api.post("meetups/$id/trip/arrive").parse { parseTrip(JSONObject(it)) }
    suspend fun stop(id: String): ApiResult<Unit> = api.post("meetups/$id/trip/stop").parse { Unit }
    suspend fun location(id: String, lat: Double, lng: Double, accuracy: Float): ApiResult<MeetupTrip> =
        api.put("meetups/$id/trip/location", JSONObject().put("lat", lat).put("lng", lng).put("accuracyM", accuracy))
            .parse { parseTrip(JSONObject(it)) }
}
