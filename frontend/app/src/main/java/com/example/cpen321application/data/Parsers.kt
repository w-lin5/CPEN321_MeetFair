package com.example.cpen321application.data

import com.example.cpen321application.model.User
import org.json.JSONObject

import com.example.cpen321application.model.VotingSession
import com.example.cpen321application.model.Vote
import com.example.cpen321application.model.CandidateVoteTotal
import com.example.cpen321application.model.VotingResults

data class AuthSession(val token: String, val user: User)

internal fun parseUser(o: JSONObject) = User(o.getString("id"), o.getString("email"), o.getString("name"))

internal fun parseAuth(text: String): AuthSession {
    val o = JSONObject(text)
    return AuthSession(o.getString("token"), parseUser(o.getJSONObject("user")))
}


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