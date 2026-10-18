package com.example.cpen321application.data

import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.Participant
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import com.example.cpen321application.model.User
import com.example.cpen321application.model.Vote
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import com.example.cpen321application.model.CandidateVoteTotal
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

internal fun parseVotingSession(text: String): VotingSession {
    val o = JSONObject(text)

    val candidateIdsJson = o.getJSONArray("candidateIds")
    val candidateIds = List(candidateIdsJson.length()) { index ->
        candidateIdsJson.getString(index)
    }

    return VotingSession(
        id = o.getString("id"),
        candidateIds = candidateIds,
        createdBy = o.getString("createdBy"),
        status = o.getString("status"),
    )
}

internal fun parseVote(text: String): Vote {
    val o = JSONObject(text)

    return Vote(
        id = o.getString("id"),
        sessionId = o.getString("sessionId"),
        userId = o.getString("userId"),
        candidateId = o.getString("candidateId"),
        weight = o.getDouble("weight"),
    )
}

internal fun parseVotingResults(text: String): VotingResults {
    val o = JSONObject(text)
    val totalsJson = o.getJSONArray("totals")

    val totals = List(totalsJson.length()) { index ->
        val item = totalsJson.getJSONObject(index)

        CandidateVoteTotal(
            candidateId = item.getString("candidateId"),
            voteCount = item.getInt("voteCount"),
            totalWeight = item.getDouble("totalWeight"),
        )
    }

    return VotingResults(
        sessionId = o.getString("sessionId"),
        totals = totals,
    )
}