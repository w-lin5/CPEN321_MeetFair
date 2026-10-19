package com.example.cpen321application.ui.voting

import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.model.CandidateTally
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.VoteTally
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingStateTest {
    private val candidates = listOf("p1", "p2", "p3").map { PlaceCandidate(it, "Place $it", "", 0.0, 0.0) }

    private val meetup = Meetup(
        id = "m1",
        groupId = "g1",
        organizerId = "u1",
        title = "Study",
        scheduledAt = "2026-11-05T22:30:00Z",
        status = MeetupStatus.VOTING,
        participants = emptyList(),
    )

    private val state = VotingState(meetup, candidates)

    private fun tally(vararg weights: Double, locked: Int = weights.count { it > 0 }): VoteTally {
        val total = weights.sum()
        return VoteTally(
            meetupId = "m1",
            candidates = weights.mapIndexed { i, w ->
                CandidateTally("p${i + 1}", if (w > 0) 1 else 0, w, if (total > 0) w / total else 0.0)
            },
            totalWeight = total,
            lockedCount = locked,
            participantCount = 3,
        )
    }

    @Test
    fun voteUpdated_replacesTallyAndMarksLive() {
        val next = applyEvent(state, MeetupEvent.VoteUpdated(tally(1.0, 0.0, 0.0)))

        assertTrue(next.live)
        assertEquals(1, next.tally?.lockedCount)
    }

    @Test
    fun joined_marksLive() {
        assertTrue(applyEvent(state, MeetupEvent.Joined).live)
    }

    @Test
    fun unavailable_dropsLiveButKeepsTally() {
        val live = state.copy(tally = tally(1.0, 0.0, 0.0), live = true)

        val next = applyEvent(live, MeetupEvent.Unavailable("offline"))

        assertFalse(next.live)
        assertEquals(live.tally, next.tally)
    }

    @Test
    fun statusChanged_finalizesWithWinnerAndKeepsMyVote() {
        val withVote = state.copy(myVote = MyVote("p2", locked = true, weight = 1.0), manualPickOptions = listOf("p1"))

        val next = applyEvent(withVote, MeetupEvent.StatusChanged(MeetupStatus.FINALIZED, candidates[0]))

        assertFalse(next.isOpen)
        assertEquals("p1", next.meetup.winner?.placeId)
        assertEquals("p2", next.myVote?.placeId)
        assertTrue(next.manualPickOptions.isEmpty())
    }

    @Test
    fun recommendationsReady_changesNothing() {
        assertSame(state, applyEvent(state, MeetupEvent.RecommendationsReady))
    }

    @Test
    fun locked_followsMyVote() {
        assertFalse(state.locked)
        assertFalse(state.copy(myVote = MyVote("p1", locked = false, weight = null)).locked)
        assertTrue(state.copy(myVote = MyVote("p1", locked = true, weight = 1.0)).locked)
    }

    @Test
    fun pickOptions_tieOffersOnlyTheLeaders() {
        assertEquals(listOf("p1", "p2"), pickOptions(state.copy(tally = tally(1.0, 1.0, 0.0))))
    }

    @Test
    fun pickOptions_nothingLockedOffersEveryCandidate() {
        assertEquals(listOf("p1", "p2", "p3"), pickOptions(state.copy(tally = tally(0.0, 0.0, 0.0, locked = 0))))
        assertEquals(listOf("p1", "p2", "p3"), pickOptions(state))
    }

    @Test
    fun leaders_toleratesFloatingPointDrift() {
        assertEquals(listOf("p1", "p2"), tally(0.1 + 0.2, 0.3, 0.0).leaders())
    }

    @Test
    fun everyoneLocked_comparesToParticipants() {
        assertFalse(tally(1.0, 0.0, 0.0).everyoneLocked)
        assertTrue(tally(1.0, 1.0, 1.0).everyoneLocked)
    }

    @Test
    fun votingRoute_roundTrips() {
        val route = Route.Voting("6523f0c2a1b2c3d4e5f60718")

        assertEquals(route, decodeRoute(route.encode()))
    }
}
