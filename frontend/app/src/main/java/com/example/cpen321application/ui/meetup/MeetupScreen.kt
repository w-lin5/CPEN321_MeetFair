package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.voting.AddToCalendarButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads one meetup for the status hub and adds it to the user's Google Calendar once finalized. */
class MeetupViewModel(private val meetups: MeetupRepository, private val meetupId: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Meetup>>(UiState.Loading)
    val state: StateFlow<UiState<Meetup>> = _state.asStateFlow()

    private val _calendar = MutableStateFlow(FormState<CalendarEventResult>())
    val calendar: StateFlow<FormState<CalendarEventResult>> = _calendar.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = meetups.get(meetupId).toUiState()
        }
    }

    /** [accessToken] is the short-lived calendar.events token from CalendarAuthorizer. */
    fun addToCalendar(accessToken: String) {
        if (_calendar.value.submitting) return
        _calendar.value = FormState(submitting = true)
        viewModelScope.launch { _calendar.value = meetups.addToCalendar(meetupId, accessToken).toFormState() }
    }

    fun setCalendarError(message: String) {
        _calendar.value = FormState(error = message)
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) =
            viewModelFactory { initializer { MeetupViewModel(c.meetupRepository, meetupId) } }
    }
}

/** Status hub: shows the meetup and whatever the current status lets the user do next. */
@Composable
fun MeetupScreen(
    container: AppContainer,
    meetupId: String,
    onBack: () -> Unit,
    onEnterDetails: () -> Unit,
    onOpenVoting: () -> Unit,
) {
    val vm: MeetupViewModel = screenViewModel(MeetupViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    val calendar by vm.calendar.collectAsState()
    val userId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Meetup", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { meetup ->
            MeetupInfo(meetup, userId, onEnterDetails, onOpenVoting) {
                val alreadyAdded = meetup.participants.any { it.userId == userId && it.calendarEventId != null }
                AddToCalendarButton(
                    container.calendarAuthorizer,
                    calendar,
                    alreadyAdded,
                    onToken = vm::addToCalendar,
                    onError = vm::setCalendarError,
                )
            }
        }
    }
}

@Composable
private fun MeetupInfo(
    meetup: Meetup,
    userId: String?,
    onEnterDetails: () -> Unit,
    onOpenVoting: () -> Unit,
    calendarSection: @Composable () -> Unit,
) {
    val submitted = meetup.participants.any { it.userId == userId }
    val setup = meetup.status == MeetupStatus.SETUP
    val decided = meetup.status == MeetupStatus.FINALIZED || meetup.status == MeetupStatus.SETTLED

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(
            meetup.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TestTags.MEETUP_TITLE_TEXT),
        )
        Text(isoToDisplay(meetup.scheduledAt))
        Text("Status: ${meetup.status.name}", modifier = Modifier.testTag(TestTags.MEETUP_STATUS_TEXT))
        Text(
            if (submitted) "You have submitted your details." else "You have not submitted your details yet.",
            modifier = Modifier.testTag(TestTags.PARTICIPATION_STATUS_TEXT),
        )
        Text("${meetup.participants.size} member(s) have submitted their details.")
        if (setup) {
            Button(onClick = onEnterDetails, modifier = Modifier.testTag(TestTags.PARTICIPATION_BUTTON)) {
                Text(if (submitted) "Edit my details" else "Enter my details")
            }
        } else {
            Text("Details can no longer be changed.")
        }
        // A voting session exists from VOTING onwards; once finalized the screen shows the winner
        if (meetup.status == MeetupStatus.VOTING || meetup.status == MeetupStatus.FINALIZED) {
            Button(onClick = onOpenVoting, modifier = Modifier.testTag(TestTags.OPEN_VOTING_BUTTON)) {
                Text(if (meetup.status == MeetupStatus.VOTING) "Vote on a spot" else "See voting results")
            }
        }
        // Only participants have an entry to record the calendar event on (backend answers 403 otherwise)
        if (decided && submitted) calendarSection()
    }
}
