package com.example.cpen321application.ui.groups

import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.TripRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import java.time.Instant
import java.time.format.DateTimeParseException

private fun hasStarted(meetup: Meetup): Boolean = try {
    !Instant.now().isBefore(Instant.parse(meetup.scheduledAt))
} catch (_: DateTimeParseException) {
    false
}

/**
 * Whether live commute tracking is under way for any of these meetups: a finalized meetup whose time
 * has come, or one for which somebody already started a trip. Trips are only requested for finalized
 * meetups that are not due yet, and the check stops at the first match.
 */
internal suspend fun hasLiveTracking(meetups: List<Meetup>, trips: TripRepository): Boolean =
    meetups.filter { it.status == MeetupStatus.FINALIZED }.any { meetup ->
        hasStarted(meetup) || trips.list(meetup.id).let { it is ApiResult.Success && it.data.isNotEmpty() }
    }
