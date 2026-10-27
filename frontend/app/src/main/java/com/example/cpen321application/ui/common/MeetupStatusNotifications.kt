package com.example.cpen321application.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus

internal fun meetupStatusNotice(previous: String?, current: MeetupStatus, placeName: String?): String? = when {
    previous == null || previous == current.name -> null
    previous == MeetupStatus.SETUP.name && current == MeetupStatus.VOTING -> "Voting has started."
    previous == MeetupStatus.VOTING.name && current == MeetupStatus.FINALIZED ->
        if (placeName.isNullOrBlank()) "Meetup finalized." else "Meetup finalized: $placeName."
    else -> null
}

/** Observe transitions, not initial loads; REST responses and socket events share this deduplication. */
@Composable
fun MeetupStatusNotifications(meetup: Meetup?) {
    var previous by rememberSaveable { mutableStateOf<String?>(null) }
    val notify = LocalNotify.current
    LaunchedEffect(meetup?.status) {
        if (meetup != null) {
            meetupStatusNotice(previous, meetup.status, meetup.winner?.name)?.let(notify)
            previous = meetup.status.name
        }
    }
}
