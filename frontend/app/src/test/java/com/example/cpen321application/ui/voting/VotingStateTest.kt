package com.example.cpen321application.ui.voting

import com.example.cpen321application.data.VotingEvent
import com.example.cpen321application.model.Candidate
import com.example.cpen321application.model.CandidateVoteTotal
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import com.example.cpen321application.model.VotingStatus
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VotingStateTest {
    private val candidates = listOf("c1", "c2", "c3").map { Candidate(it, "p-$it", "Place $it", "", 0.0, 0.0) }

    private val session = VotingSession(
        id = "s1",
        meetupId = "m1",
        candidates = candidates,
        createdBy = "u1",
        status = VotingStatus.OPEN,
        winnerCandidateId = null,
        myCandidateId = "c2",
    )

    private fun results(vararg weights: Double, voters: Int = weights.count { it > 0 }) = VotingResults(
        sessionId = "s1",
        status = VotingStatus.OPEN,
        winnerCandidateId = null,
        voterCount = voters,
        eligibleVoterCount = 3,
        totals = weights.mapIndexed { i, w -> CandidateVoteTotal("c${i + 1}", if (w > 0) 1 else 0, w) },
    )

    @Test
    fun resultsEvent_updatesTotalsAndMarksLive() {
        val next = applyEvent(VotingState(session), VotingEvent.Results(results(1.0, 0.0, 0.0)))

        assertTrue(next.live)
        assertEquals(1, next.results!!.voterCount)
    }

    @Test
    fun unavailableEvent_dropsLiveButKeepsResults() {
        val live = VotingState(session, results = results(1.0, 0.0, 0.0), live = true)

        val next = applyEvent(live, VotingEvent.Unavailable("offline"))

        assertFalse(next.live)
        assertEquals(live.results, next.results)
    }

    @Test
    fun closedEvent_keepsMyVoteAndClearsManualPick() {
        val broadcast = session.copy(status = VotingStatus.CLOSED, winnerCandidateId = "c1", myCandidateId = null)
        val state = VotingState(session, manualPickOptions = listOf("c1", "c2"))

        val next = applyEvent(state, VotingEvent.Closed(broadcast, results(2.0, 1.0, 0.0)))

        assertFalse(next.isOpen)
        assertEquals("c1", next.session.winnerCandidateId)
        assertEquals("c2", next.session.myCandidateId)
        assertTrue(next.manualPickOptions.isEmpty())
    }

    @Test
    fun pickOptions_tieOffersOnlyTheLeaders() {
        val state = VotingState(session, results = results(1.0, 1.0, 0.0))

        assertEquals(listOf("c1", "c2"), pickOptions(state))
    }

    @Test
    fun pickOptions_zeroVotesOffersEveryCandidate() {
        val state = VotingState(session, results = results(0.0, 0.0, 0.0, voters = 0))

        assertEquals(listOf("c1", "c2", "c3"), pickOptions(state))
        assertEquals(listOf("c1", "c2", "c3"), pickOptions(VotingState(session)))
    }

    @Test
    fun leaders_toleratesFloatingPointDrift() {
        val drift = results(0.1 + 0.2, 0.3, 0.0)

        assertEquals(listOf("c1", "c2"), drift.leaders())
    }

    @Test
    fun votingRoute_roundTrips() {
        val route = Route.Voting("6523f0c2a1b2c3d4e5f60718")

        assertEquals(route, decodeRoute(route.encode()))
    }
}
