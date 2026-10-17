package com.example.cpen321application.data

import com.example.cpen321application.model.Vote
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import org.json.JSONArray
import org.json.JSONObject


class VotingRepository(private val api: ApiClient) {

    suspend fun createSession(candidateIds: List<String>): ApiResult<VotingSession> {
        val body = JSONObject()
            .put("candidateIds", JSONArray(candidateIds))

        return api
            .post("voting-sessions", body)
            .parse(::parseVotingSession)
    }

    // No weight parameter is needed for the vote submission, as the weight is determined by the backend based on the user's voting power.
    suspend fun submitVote(
        sessionId: String,
        candidateId: String,
    ): ApiResult<Vote> {
        val body = JSONObject()
            .put("candidateId", candidateId)

        return api
            .post("voting-sessions/$sessionId/vote", body)
            .parse(::parseVote)
    }

    suspend fun getResults(sessionId: String): ApiResult<VotingResults> {
        return api
            .get("voting-sessions/$sessionId/votes")
            .parse(::parseVotingResults)
    }

    suspend fun closeSession(sessionId: String): ApiResult<VotingSession> {
        return api
            .patch("voting-sessions/$sessionId/close")
            .parse(::parseVotingSession)
    }

    suspend fun getSession(sessionId: String): ApiResult<VotingSession> {
        return api
            .get("voting-sessions/$sessionId")
            .parse(::parseVotingSession)
    }
}