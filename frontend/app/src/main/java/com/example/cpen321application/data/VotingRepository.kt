package com.example.cpen321application.data

import com.example.cpen321application.model.Candidate
import com.example.cpen321application.model.Vote
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import org.json.JSONArray
import org.json.JSONObject

class VotingRepository(private val api: ApiClient) {
    /** Organizer only; [candidates] are the recommender's top places (the server requires exactly three). */
    suspend fun startVoting(meetupId: String, candidates: List<Candidate>): ApiResult<VotingSession> {
        val items = candidates.map {
            JSONObject()
                .put("placeId", it.placeId)
                .put("name", it.name)
                .put("address", it.address)
                .put("location", JSONObject().put("lat", it.lat).put("lng", it.lng))
        }
        val body = JSONObject().put("candidates", JSONArray(items))
        return api.post("meetups/$meetupId/voting-session", body).parse { parseVotingSession(JSONObject(it)) }
    }

    suspend fun getSessionForMeetup(meetupId: String): ApiResult<VotingSession> =
        api.get("meetups/$meetupId/voting-session").parse { parseVotingSession(JSONObject(it)) }

    suspend fun getSession(sessionId: String): ApiResult<VotingSession> =
        api.get("voting-sessions/$sessionId").parse { parseVotingSession(JSONObject(it)) }

    // No weight is sent: the backend derives it from the caller's Fair Credit
    suspend fun submitVote(sessionId: String, candidateId: String): ApiResult<Vote> =
        api.post("voting-sessions/$sessionId/vote", JSONObject().put("candidateId", candidateId))
            .parse { parseVote(JSONObject(it)) }

    suspend fun getResults(sessionId: String): ApiResult<VotingResults> =
        api.get("voting-sessions/$sessionId/votes").parse { parseVotingResults(JSONObject(it)) }

    /** [winnerCandidateId] is only needed to break a tie or when nobody voted. */
    suspend fun closeSession(sessionId: String, winnerCandidateId: String? = null): ApiResult<VotingSession> {
        val body = JSONObject()
        if (winnerCandidateId != null) body.put("winnerCandidateId", winnerCandidateId)
        return api.patch("voting-sessions/$sessionId/close", body).parse { parseVotingSession(JSONObject(it)) }
    }
}
