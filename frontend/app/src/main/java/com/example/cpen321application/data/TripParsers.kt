package com.example.cpen321application.data

import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.model.SharedPosition
import com.example.cpen321application.model.TripStatus
import org.json.JSONObject

// Shown for a trip whose traveller the server could not name
private const val UNKNOWN_TRAVELLER = "Member"

internal fun parseSharedPosition(o: JSONObject) = SharedPosition(
    lat = o.getDouble("lat"),
    lng = o.getDouble("lng"),
    accuracyM = o.getDouble("accuracyM"),
    updatedAt = o.getString("updatedAt"),
)

internal fun parseTrip(o: JSONObject) = MeetupTrip(
    userId = o.getString("userId"),
    name = o.stringOrNull("name") ?: UNKNOWN_TRAVELLER,
    photoUrl = o.stringOrNull("photoUrl"),
    status = TripStatus.fromWire(o.stringOrNull("status")),
    sharing = o.getBoolean("sharing"),
    startedAt = o.getString("startedAt"),
    arrivedAt = o.stringOrNull("arrivedAt"),
    actualSeconds = if (o.isNull("actualSeconds")) null else o.getLong("actualSeconds"),
    location = o.optJSONObject("location")?.let(::parseSharedPosition),
)
