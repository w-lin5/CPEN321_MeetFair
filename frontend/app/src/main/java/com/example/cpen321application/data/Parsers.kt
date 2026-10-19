package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.CandidateTally
import com.example.cpen321application.model.CommuteSource
import com.example.cpen321application.model.FairnessEntry
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupFairness
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MemberCredit
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.Participant
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.ProfileGroup
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import com.example.cpen321application.model.User
import com.example.cpen321application.model.UserProfile
import com.example.cpen321application.model.VoteTally
import com.example.cpen321application.model.VotesSnapshot
import org.json.JSONArray
import org.json.JSONObject

data class AuthSession(val token: String, val user: User)

internal fun parseUser(o: JSONObject) = User(o.getString("id"), o.getString("email"), o.getString("name"))

internal fun parseAuth(text: String): AuthSession {
    val o = JSONObject(text)
    return AuthSession(o.getString("token"), parseUser(o.getJSONObject("user")))
}

// org.json's getString returns the text "null" for JSON null, so check has/isNull first
internal fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

internal fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

internal fun parseGroupMember(o: JSONObject) = GroupMember(
    userId = o.getString("userId"),
    name = o.stringOrNull("name"),
    role = GroupRole.fromWire(o.stringOrNull("role")),
)

internal fun parseGroup(o: JSONObject) = Group(
    id = o.getString("id"),
    name = o.getString("name"),
    inviteCode = o.getString("inviteCode"),
    organizerId = o.getString("organizerId"),
    members = o.getJSONArray("members").mapObjects(::parseGroupMember),
)

internal fun parseStartLocation(o: JSONObject) = StartLocation(
    lat = o.getDouble("lat"),
    lng = o.getDouble("lng"),
    label = o.stringOrNull("label").orEmpty(),
)

internal fun parseParticipant(o: JSONObject) = Participant(
    userId = o.getString("userId"),
    startLocation = parseStartLocation(o.getJSONObject("startLocation")),
    travelMode = TravelMode.fromWire(o.stringOrNull("travelMode")),
    preferences = o.getJSONArray("preferences").strings().mapNotNull { Preference.fromWire(it) },
    calendarEventId = o.stringOrNull("calendarEventId"),
)

internal fun parseMeetup(o: JSONObject) = Meetup(
    id = o.getString("id"),
    groupId = o.getString("groupId"),
    organizerId = o.getString("organizerId"),
    title = o.getString("title"),
    scheduledAt = o.getString("scheduledAt"),
    status = MeetupStatus.fromWire(o.stringOrNull("status")),
    participants = o.getJSONArray("participants").mapObjects(::parseParticipant),
    winner = o.optJSONObject("winner")?.let(::parsePlaceCandidate),
)

internal fun parseProfileGroup(o: JSONObject) = ProfileGroup(
    groupId = o.getString("groupId"),
    name = o.getString("name"),
    role = GroupRole.fromWire(o.stringOrNull("role")),
    fairCredit = o.optLong("fairCredit", 0),
)

internal fun parseUserProfile(o: JSONObject) = UserProfile(
    id = o.getString("id"),
    email = o.getString("email"),
    googleName = o.getString("googleName"),
    displayName = o.getString("displayName"),
    hasCustomDisplayName = o.optBoolean("hasCustomDisplayName"),
    photoUrl = o.stringOrNull("photoUrl"),
    groups = o.getJSONArray("groups").mapObjects(::parseProfileGroup),
)

internal fun parseFairnessEntry(o: JSONObject) = FairnessEntry(
    userId = o.getString("userId"),
    meetupId = o.getString("meetupId"),
    actualSeconds = o.getLong("actualSeconds"),
    normalizedBurden = o.getLong("normalizedBurden"),
    expectedBurden = o.getLong("expectedBurden"),
    delta = o.getLong("delta"),
    source = CommuteSource.fromWire(o.stringOrNull("source")),
    recordedAt = o.stringOrNull("createdAt").orEmpty(),
)

internal fun parseMemberCredit(o: JSONObject) = MemberCredit(
    userId = o.getString("userId"),
    name = o.stringOrNull("name"),
    balance = o.getLong("balance"),
)

internal fun parseGroupFairness(o: JSONObject) = GroupFairness(
    groupId = o.getString("groupId"),
    members = o.getJSONArray("members").mapObjects(::parseMemberCredit),
    ledger = o.getJSONArray("ledger").mapObjects(::parseFairnessEntry),
)

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
