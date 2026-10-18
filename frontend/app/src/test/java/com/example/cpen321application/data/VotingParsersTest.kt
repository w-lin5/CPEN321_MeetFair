package com.example.cpen321application.data

import com.example.cpen321application.model.VotingStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingParsersTest {
    private val sessionJson = """
        {
          "id": "s1", "meetupId": "m1", "createdBy": "u1", "status": "open",
          "winnerCandidateId": null, "myCandidateId": "c2",
          "candidates": [
            { "id": "c1", "placeId": "p1", "name": "Koerner Library", "address": "1958 Main Mall",
              "location": { "lat": 49.2667, "lng": -123.2551 } },
            { "id": "c2", "placeId": "p2", "name": "Loafe Cafe", "location": { "lat": 49.26, "lng": -123.24 } }
          ]
        }
    """.trimIndent()

    private val resultsJson = """
        {
          "sessionId": "s1", "status": "closed", "winnerCandidateId": "c1",
          "voterCount": 2, "eligibleVoterCount": 3,
          "totals": [
            { "candidateId": "c1", "voteCount": 2, "totalWeight": 2.5 },
            { "candidateId": "c2", "voteCount": 0, "totalWeight": 0 }
          ]
        }
    """.trimIndent()

    @Test
    fun parseVotingSession_readsCandidatesAndNullableFields() {
        val session = parseVotingSession(JSONObject(sessionJson))

        assertEquals("m1", session.meetupId)
        assertEquals(VotingStatus.OPEN, session.status)
        assertNull(session.winnerCandidateId)
        assertEquals("c2", session.myCandidateId)
        assertEquals(listOf("Koerner Library", "Loafe Cafe"), session.candidates.map { it.name })
        assertEquals("1958 Main Mall", session.candidates[0].address)
        // A missing address degrades to empty rather than failing the whole session
        assertEquals("", session.candidates[1].address)
        assertEquals(49.2667, session.candidates[0].lat, 0.0)
    }

    @Test
    fun parseVotingResults_readsTotalsAndCounts() {
        val results = parseVotingResults(JSONObject(resultsJson))

        assertEquals(VotingStatus.CLOSED, results.status)
        assertEquals("c1", results.winnerCandidateId)
        assertEquals(2, results.voterCount)
        assertEquals(3, results.eligibleVoterCount)
        assertEquals(2.5, results.totalFor("c1")!!.totalWeight, 0.0)
        assertEquals(0, results.totalFor("c2")!!.voteCount)
        assertEquals(false, results.everyoneVoted)
    }

    @Test
    fun parseVote_readsWeight() {
        val vote = parseVote(
            JSONObject("""{"id":"v1","sessionId":"s1","userId":"u1","candidateId":"c1","weight":1}"""),
        )

        assertEquals("c1", vote.candidateId)
        assertEquals(1.0, vote.weight, 0.0)
    }

    @Test
    fun joinAck_successCarriesResults() {
        val event = joinAckToEvent(arrayOf(JSONObject().put("ok", true).put("results", JSONObject(resultsJson))))

        assertTrue(event is VotingEvent.Results)
        assertEquals(2, (event as VotingEvent.Results).results.voterCount)
    }

    @Test
    fun joinAck_failureAndMalformedPayloadsBecomeUnavailable() {
        val denied = joinAckToEvent(arrayOf(JSONObject().put("ok", false).put("error", "Not a member of this group")))
        val empty = joinAckToEvent(arrayOf())
        val broken = joinAckToEvent(arrayOf(JSONObject().put("ok", true).put("results", JSONObject())))

        assertEquals(VotingEvent.Unavailable("Not a member of this group"), denied)
        assertTrue(empty is VotingEvent.Unavailable)
        assertTrue(broken is VotingEvent.Unavailable)
    }
}
