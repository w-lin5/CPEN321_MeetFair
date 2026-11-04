package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.CandidateTally
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.model.VoteTally
import com.example.cpen321application.model.VotesSnapshot
import org.json.JSONObject

internal fun parsePlaceCandidate(o: JSONObject): PlaceCandidate {
    val location = o.getJSONObject("location")
    return PlaceCandidate(
        placeId = o.getString("placeId"),
        name = o.getString("name"),
        address = o.stringOrNull("address").orEmpty(),
        lat = location.getDouble("lat"),
        lng = location.getDouble("lng"),
    )
}

internal fun parseRecommendation(o: JSONObject) = Recommendation(
    meetupId = o.getString("meetupId"),
    candidates = o.getJSONArray("candidates").mapObjects(::parsePlaceCandidate),
)

internal fun parseMyVote(o: JSONObject) = MyVote(
    placeId = o.getString("placeId"),
    locked = o.optBoolean("locked"),
    weight = if (o.has("weight") && !o.isNull("weight")) o.getDouble("weight") else null,
)

internal fun parseCandidateTally(o: JSONObject) = CandidateTally(
    placeId = o.getString("placeId"),
    voteCount = o.getInt("voteCount"),
    weight = o.getDouble("weight"),
    share = o.getDouble("share"),
)

internal fun parseVoteTally(o: JSONObject) = VoteTally(
    meetupId = o.getString("meetupId"),
    candidates = o.getJSONArray("candidates").mapObjects(::parseCandidateTally),
    totalWeight = o.getDouble("totalWeight"),
    lockedCount = o.getInt("lockedCount"),
    participantCount = o.getInt("participantCount"),
)

internal fun parseVotesSnapshot(o: JSONObject) = VotesSnapshot(
    tally = parseVoteTally(o),
    myVote = o.optJSONObject("myVote")?.let(::parseMyVote),
)

internal fun parseCalendarEventResult(o: JSONObject) = CalendarEventResult(
    eventId = o.getString("eventId"),
    htmlLink = o.stringOrNull("htmlLink"),
)
