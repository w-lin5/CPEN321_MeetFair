package com.example.cpen321application.notifications

import com.example.cpen321application.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MeetupRemindersTest {
    private val meetup = Meetup("meet", "group", "organizer", "Lunch", "2030-01-01T12:00:00Z",
        MeetupStatus.FINALIZED, listOf(Participant("member", StartLocation(49.0, -123.0, ""),
            TravelMode.WALK, listOf(Preference.FOOD))),
        PlaceCandidate("place", "Cafe", "", 49.0, -123.0))

    @Test fun onlyJoinedMembersWithConfirmedDestinationReceiveReminder() {
        assertEquals(Instant.parse(meetup.scheduledAt).toEpochMilli(), reminderTime(meetup, "member"))
        assertNull(reminderTime(meetup, "outsider"))
        assertNull(reminderTime(meetup, null))
        assertNull(reminderTime(meetup.copy(winner = null), "member"))
        for (status in listOf(MeetupStatus.SETUP, MeetupStatus.VOTING, MeetupStatus.SETTLED)) {
            assertNull(reminderTime(meetup.copy(status = status), "member"))
        }
    }

    @Test fun alarmCanBeRegisteredBeforeOrganizerFinalizesWhileAppIsClosed() {
        val pending = meetup.copy(status = MeetupStatus.SETUP, winner = null)
        assertNotNull(scheduledReminderTime(pending, "member"))
        assertNull(reminderTime(pending, "member"))
        assertNull(scheduledReminderTime(pending.copy(participants = emptyList()), "member"))
        assertNull(scheduledReminderTime(meetup.copy(status = MeetupStatus.SETTLED), "member"))
    }

    @Test fun malformedDateIsIgnored() {
        assertNull(reminderTime(meetup.copy(scheduledAt = "invalid"), "member"))
        assertNull(scheduledReminderTime(meetup.copy(scheduledAt = "invalid"), "member"))
    }
}
