package com.example.cpen321application.ui.common

import com.example.cpen321application.model.MeetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetupStatusNotificationsTest {
    @Test fun enteringAnExistingMeetupDoesNotReplayNotifications() {
        assertNull(meetupStatusNotice(null, MeetupStatus.VOTING, null))
        assertNull(meetupStatusNotice(null, MeetupStatus.FINALIZED, "Cafe"))
    }

    @Test fun startingVotingShowsOneNotification() {
        assertEquals("Voting has started.", meetupStatusNotice("SETUP", MeetupStatus.VOTING, null))
        assertNull(meetupStatusNotice("VOTING", MeetupStatus.VOTING, null))
    }

    @Test fun finalizingShowsWinnerAndDoesNotRepeatForTheSocketEcho() {
        assertEquals("Meetup finalized: Cafe.", meetupStatusNotice("VOTING", MeetupStatus.FINALIZED, "Cafe"))
        assertNull(meetupStatusNotice("FINALIZED", MeetupStatus.FINALIZED, "Cafe"))
    }

    @Test fun finalizingWithoutAPlaceNameStillShowsConfirmation() {
        assertEquals("Meetup finalized.", meetupStatusNotice("VOTING", MeetupStatus.FINALIZED, null))
    }

    @Test fun settlementIsNotReportedAsAnotherFinalization() {
        assertNull(meetupStatusNotice("FINALIZED", MeetupStatus.SETTLED, "Cafe"))
    }
}
