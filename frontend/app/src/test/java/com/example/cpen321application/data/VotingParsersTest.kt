package com.example.cpen321application.data

import com.example.cpen321application.model.MeetupStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingParsersTest {
    private val candidateJson = """
        { "placeId": "p1", "name": "Koerner Library", "address": "1958 Main Mall",
          "location": { "lat": 49.2667, "lng": -123.2551 } }
    """.trimIndent()

    private val tallyJson = """
        {
          "meetupId": "m1", "totalWeight": 2.5, "lockedCount": 2, "participantCount": 3,
          "candidates": [
            { "placeId": "p1", "voteCount": 2, "weight": 1.5, "share": 0.6 },
            { "placeId": "p2", "voteCount": 1, "weight": 1, "share": 0.4 }
          ]
        }
    """.trimIndent()

    @Test
    fun parseRecommendation_readsCandidates() {
        val recommendation = parseRecommendation(JSONObject("""{ "meetupId": "m1", "candidates": [$candidateJson] }"""))

        assertEquals("m1", recommendation.meetupId)
        assertEquals("Koerner Library", recommendation.candidates.single().name)
        assertEquals(-123.2551, recommendation.candidates.single().lng, 0.0)
    }

    @Test
    fun parseVotesSnapshot_readsTallyAndMyVote() {
        val withVote = JSONObject(tallyJson).put("myVote", JSONObject("""{ "placeId": "p2", "locked": true, "weight": 1 }"""))

        val snapshot = parseVotesSnapshot(withVote)

        assertEquals(2.5, snapshot.tally.totalWeight, 0.0)
        assertEquals(listOf("p1", "p2"), snapshot.tally.candidates.map { it.placeId })
        assertEquals(0.6, snapshot.tally.totalFor("p1")!!.share, 0.0)
        assertTrue(snapshot.myVote!!.locked)
        assertEquals(1.0, snapshot.myVote!!.weight!!, 0.0)
    }

    @Test
    fun parseVotesSnapshot_nullMyVote() {
        assertNull(parseVotesSnapshot(JSONObject(tallyJson).put("myVote", JSONObject.NULL)).myVote)
    }

    @Test
    fun parseMyVote_unlockedHasNoWeight() {
        val vote = parseMyVote(JSONObject("""{ "placeId": "p1", "locked": false, "weight": null }"""))

        assertFalse(vote.locked)
        assertNull(vote.weight)
    }

    @Test
    fun parseMeetup_readsWinner() {
        val meetup = parseMeetup(
            JSONObject(
                """
                { "id": "m1", "groupId": "g1", "organizerId": "u1", "title": "Study",
                  "scheduledAt": "2026-11-05T22:30:00.000Z", "status": "FINALIZED", "participants": [],
                  "winner": $candidateJson }
                """.trimIndent(),
            ),
        )

        assertEquals(MeetupStatus.FINALIZED, meetup.status)
        assertEquals("p1", meetup.winner?.placeId)
    }

    @Test
    fun statusEvent_readsStatusAndWinner() {
        val event = parseStatusEvent(JSONObject("""{ "meetupId": "m1", "status": "FINALIZED", "winner": $candidateJson }"""))

        assertEquals(MeetupStatus.FINALIZED, event.status)
        assertEquals("p1", event.winner?.placeId)
        assertNull(parseStatusEvent(JSONObject("""{ "meetupId": "m1", "status": "VOTING" }""")).winner)
    }

    @Test
    fun joinAck_okJoinsAndErrorsAreUnavailable() {
        assertEquals(MeetupEvent.Joined, joinAckToEvent(arrayOf(JSONObject("""{ "ok": true }"""))))
        assertEquals(
            MeetupEvent.Unavailable("Not a member of this group"),
            joinAckToEvent(arrayOf(JSONObject("""{ "ok": false, "error": "Not a member of this group" }"""))),
        )
        assertTrue(joinAckToEvent(arrayOf("not json")) is MeetupEvent.Unavailable)
    }
}
