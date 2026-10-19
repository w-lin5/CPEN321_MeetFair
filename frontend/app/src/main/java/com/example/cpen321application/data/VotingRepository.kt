package com.example.cpen321application.data

import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.model.VotesSnapshot
import org.json.JSONArray
import org.json.JSONObject

class VotingRepository(private val api: ApiClient) {
    suspend fun recommendations(meetupId: String): ApiResult<Recommendation> =
        api.get("meetups/$meetupId/recommendations").parse { parseRecommendation(JSONObject(it)) }

    /**
     * Organizer only; moves the meetup to VOTING. TEMPORARY: [candidates] are supplied by the caller
     * (the server requires exactly three) until the recommendation engine generates them.
     */
    suspend fun generateRecommendations(meetupId: String, candidates: List<PlaceCandidate>): ApiResult<Recommendation> {
        val items = candidates.map {
            JSONObject()
                .put("placeId", it.placeId)
                .put("name", it.name)
                .put("address", it.address)
                .put("location", JSONObject().put("lat", it.lat).put("lng", it.lng))
        }
        val body = JSONObject().put("candidates", JSONArray(items))
        return api.post("meetups/$meetupId/recommendations", body).parse { parseRecommendation(JSONObject(it)) }
    }

    suspend fun votes(meetupId: String): ApiResult<VotesSnapshot> =
        api.get("meetups/$meetupId/votes").parse { parseVotesSnapshot(JSONObject(it)) }

    /** Creates or changes the caller's vote; the server answers 409 once it is locked. */
    suspend fun vote(meetupId: String, placeId: String): ApiResult<MyVote> =
        api.put("meetups/$meetupId/vote", JSONObject().put("placeId", placeId)).parse { parseMyVote(JSONObject(it)) }

    // No weight is sent: the backend snapshots it from the caller's Fair Credit
    suspend fun lock(meetupId: String): ApiResult<MyVote> =
        api.post("meetups/$meetupId/vote/lock").parse { parseMyVote(JSONObject(it)) }

    /** Organizer only; [placeId] is only needed to break a tie or when nobody locked a vote. */
    suspend fun finalize(meetupId: String, placeId: String? = null): ApiResult<Meetup> {
        val body = JSONObject()
        if (placeId != null) body.put("placeId", placeId)
        return api.post("meetups/$meetupId/finalize", body).parse { parseMeetup(JSONObject(it)) }
    }
}
