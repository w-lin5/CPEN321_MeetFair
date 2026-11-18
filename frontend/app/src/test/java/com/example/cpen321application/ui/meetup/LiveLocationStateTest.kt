package com.example.cpen321application.ui.meetup

import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.model.Participant
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
import com.example.cpen321application.model.TripStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationStateTest {
    private val meetupTime = Instant.parse("2030-01-01T12:00:00Z")
    private val meetup = Meetup(
        id = "meet",
        groupId = "group",
        organizerId = "organizer",
        title = "Lunch",
        scheduledAt = meetupTime.toString(),
        status = MeetupStatus.FINALIZED,
        participants = listOf(Participant("member", StartLocation(49.0, -123.0, ""), TravelMode.WALK, emptyList())),
    )

    private fun trip(status: TripStatus) = MeetupTrip(
        userId = "member",
        name = "Ada",
        photoUrl = null,
        status = status,
        sharing = status == TripStatus.ACTIVE,
        startedAt = meetupTime.toString(),
        arrivedAt = null,
        actualSeconds = null,
        location = null,
    )

    private fun stateAt(now: Instant, trips: List<MeetupTrip> = emptyList(), current: Meetup? = meetup) =
        LiveLocationState(meetup = current, trips = trips, now = now)

    @Test fun participantCanStartFromTheMeetupTimeOn() {
        assertFalse(stateAt(meetupTime.minusSeconds(1)).canStartTrip("member"))
        assertTrue(stateAt(meetupTime).canStartTrip("member"))
        assertTrue(stateAt(meetupTime.plusSeconds(3600)).canStartTrip("member"))
    }

    @Test fun onlyParticipantsOfAFinalizedMeetupCanStart() {
        assertFalse(stateAt(meetupTime).canStartTrip("outsider"))
        assertFalse(stateAt(meetupTime).canStartTrip(null))
        assertFalse(stateAt(meetupTime, current = null).canStartTrip("member"))
        for (status in listOf(MeetupStatus.SETUP, MeetupStatus.VOTING, MeetupStatus.SETTLED)) {
            assertFalse(stateAt(meetupTime, current = meetup.copy(status = status)).canStartTrip("member"))
        }
    }

    @Test fun activeTripCanBeResumedButArrivalIsFinal() {
        assertTrue(stateAt(meetupTime, listOf(trip(TripStatus.ACTIVE))).canStartTrip("member"))
        assertFalse(stateAt(meetupTime, listOf(trip(TripStatus.ARRIVED))).canStartTrip("member"))
    }

    @Test fun unreadableMeetupTimeNeverAllowsATrip() {
        assertFalse(stateAt(meetupTime, current = meetup.copy(scheduledAt = "invalid")).canStartTrip("member"))
    }

    @Test fun secondsBetweenMeasuresFromAnIsoInstant() {
        assertEquals(125L, secondsBetween("2030-01-01T12:00:00Z", Instant.parse("2030-01-01T12:02:05Z")))
        assertEquals(-60L, secondsBetween("2030-01-01T12:01:00Z", meetupTime))
        assertNull(secondsBetween("invalid", meetupTime))
    }
}
