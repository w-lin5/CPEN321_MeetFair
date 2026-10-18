package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Candidate
import com.example.cpen321application.model.CandidateVoteTotal
import com.example.cpen321application.model.CommuteSource
import com.example.cpen321application.model.FairnessEntry
import com.example.cpen321application.model.FairnessHistory
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.Participant
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.ProfileGroup
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import com.example.cpen321application.model.User
import com.example.cpen321application.model.UserProfile
import com.example.cpen321application.model.Vote
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import com.example.cpen321application.model.VotingStatus
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
)

internal fun parseProfileGroup(o: JSONObject) = ProfileGroup(
    groupId = o.getString("groupId"),
    name = o.getString("name"),
    role = GroupRole.fromWire(o.stringOrNull("role")),
    fairnessDebt = o.optDouble("fairnessDebt", 0.0),
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
    meetupId = o.getString("meetupId"),
    commuteMinutes = o.getDouble("commuteMinutes"),
    groupAverageMinutes = o.getDouble("groupAverageMinutes"),
    extraMinutes = o.getDouble("extraMinutes"),
    source = CommuteSource.fromWire(o.stringOrNull("source")),
    recordedAt = o.stringOrNull("createdAt").orEmpty(),
)

internal fun parseFairnessHistory(o: JSONObject) = FairnessHistory(
    groupId = o.getString("groupId"),
    fairnessDebt = o.getDouble("fairnessDebt"),
    history = o.getJSONArray("history").mapObjects(::parseFairnessEntry),
)

internal fun parseCandidate(o: JSONObject): Candidate {
    val location = o.getJSONObject("location")
    return Candidate(
        id = o.getString("id"),
        placeId = o.getString("placeId"),
        name = o.getString("name"),
        address = o.stringOrNull("address").orEmpty(),
        lat = location.getDouble("lat"),
        lng = location.getDouble("lng"),
    )
}

internal fun parseVotingSession(o: JSONObject) = VotingSession(
    id = o.getString("id"),
    meetupId = o.getString("meetupId"),
    candidates = o.getJSONArray("candidates").mapObjects(::parseCandidate),
    createdBy = o.getString("createdBy"),
    status = VotingStatus.fromWire(o.stringOrNull("status")),
    winnerCandidateId = o.stringOrNull("winnerCandidateId"),
    myCandidateId = o.stringOrNull("myCandidateId"),
)

internal fun parseVote(o: JSONObject) = Vote(
    id = o.getString("id"),
    sessionId = o.getString("sessionId"),
    userId = o.getString("userId"),
    candidateId = o.getString("candidateId"),
    weight = o.getDouble("weight"),
)

internal fun parseVoteTotal(o: JSONObject) = CandidateVoteTotal(
    candidateId = o.getString("candidateId"),
    voteCount = o.getInt("voteCount"),
    totalWeight = o.getDouble("totalWeight"),
)

internal fun parseVotingResults(o: JSONObject) = VotingResults(
    sessionId = o.getString("sessionId"),
    status = VotingStatus.fromWire(o.stringOrNull("status")),
    winnerCandidateId = o.stringOrNull("winnerCandidateId"),
    voterCount = o.getInt("voterCount"),
    eligibleVoterCount = o.getInt("eligibleVoterCount"),
    totals = o.getJSONArray("totals").mapObjects(::parseVoteTotal),
)

internal fun parseCalendarEventResult(o: JSONObject) = CalendarEventResult(
    eventId = o.getString("eventId"),
    htmlLink = o.stringOrNull("htmlLink"),
)
