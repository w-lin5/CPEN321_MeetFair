package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetupStatusNotifications
import com.example.cpen321application.ui.common.DeleteConfirmation
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
    private val _deletion = MutableStateFlow(FormState<Unit>())
    val deletion: StateFlow<FormState<Unit>> = _deletion.asStateFlow()

    fun onStatusChanged(event: MeetupEvent.StatusChanged) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        _state.value = UiState.Success(current.copy(status = event.status, winner = event.winner ?: current.winner))
    }

    fun deleteMeetup() {
        if (_deletion.value.submitting || _deletion.value.result != null) return
        _deletion.value = FormState(submitting = true)
        viewModelScope.launch { _deletion.value = meetups.delete(meetupId).toFormState() }
    }

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
    onStartVoting: () -> Unit,
    onOpenVoting: () -> Unit,
) {
    val vm: MeetupViewModel = screenViewModel(MeetupViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    MeetupStatusNotifications((state as? UiState.Success)?.data)
    val calendar by vm.calendar.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val notify = LocalNotify.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var exited by rememberSaveable { mutableStateOf(false) }
    val userId = container.sessionStore.currentUserId()
    val actions = MeetupActions(onEnterDetails, onStartVoting, onOpenVoting)
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(container, meetupId) {
        container.realtimeClient.meetupEvents(meetupId).collect { event ->
            if (event is MeetupEvent.StatusChanged) vm.onStatusChanged(event)
            if (event is MeetupEvent.Deleted && !vm.deletion.value.submitting &&
                vm.deletion.value.result == null && !exited) {
                exited = true
                if (event.notifyParticipant) notify("This meetup has been deleted by its organizer.")
                onBack()
            }
        }
    }
    LaunchedEffect(deletion.result) {
        if (deletion.result != null && !exited) { exited = true; notify("Meetup deleted."); onBack() }
    }

    ScreenColumn("Meetup", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { meetup ->
            if (confirmDelete) {
                DeleteConfirmation(
                    title = "Delete meetup?",
                    description = "Delete ${meetup.title}, its participation details, candidates and votes? This cannot be undone. Settled Fair Credit and external calendar events will remain.",
                    votingWarning = if (meetup.status == MeetupStatus.VOTING)
                        "Warning: this meetup currently has voting in progress. Deleting it will end the vote." else null,
                    onDismiss = { confirmDelete = false },
                    onDelete = { confirmDelete = false; vm.deleteMeetup() },
                )
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            MeetupInfo(meetup, userId, actions) {
                val alreadyAdded = meetup.participants.any { it.userId == userId && it.calendarEventId != null }
                AddToCalendarButton(
                    container.calendarAuthorizer,
                    calendar,
                    alreadyAdded,
                    onToken = vm::addToCalendar,
                    onError = vm::setCalendarError,
                )
            }
            if (meetup.organizerId == userId) {
                TextButton(onClick = { confirmDelete = true }, enabled = !deletion.submitting,
                    modifier = Modifier.testTag("delete_meetup")) {
                    Text(if (deletion.submitting) "Deleting..." else "Delete meetup", color = MaterialTheme.colorScheme.error)
                }
            }
            deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Navigation callbacks for the next steps the meetup screen can offer. */
private data class MeetupActions(
    val onEnterDetails: () -> Unit,
    val onStartVoting: () -> Unit,
    val onOpenVoting: () -> Unit,
)

@Composable
private fun MeetupInfo(
    meetup: Meetup,
    userId: String?,
    actions: MeetupActions,
    calendarSection: @Composable () -> Unit,
) {
    val submitted = meetup.participants.any { it.userId == userId }
    val setup = meetup.status == MeetupStatus.SETUP
    val isOrganizer = meetup.organizerId == userId
    val decided = meetup.status == MeetupStatus.FINALIZED || meetup.status == MeetupStatus.SETTLED

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(
            meetup.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TestTags.MEETUP_TITLE_TEXT),
        )
        Text(isoToDisplay(meetup.scheduledAt))
        Text("Status: ${meetup.status.name}", modifier = Modifier.testTag(TestTags.MEETUP_STATUS_TEXT))
        meetup.winner?.let { winner ->
            Text("Meeting at: ${winner.name}", modifier = Modifier.testTag(TestTags.MEETUP_WINNER_TEXT))
        }
        Text(
            if (submitted) "You have submitted your details." else "You have not submitted your details yet.",
            modifier = Modifier.testTag(TestTags.PARTICIPATION_STATUS_TEXT),
        )
        Text("${meetup.participants.size} member(s) have submitted their details.")
        if (setup) {
            Button(onClick = actions.onEnterDetails, modifier = Modifier.testTag(TestTags.PARTICIPATION_BUTTON)) {
                Text(if (submitted) "Edit my details" else "Enter my details")
            }
        } else {
            Text("Details can no longer be changed.")
        }
        // Only the organizer can close SETUP and open the vote (backend answers 403 otherwise)
        if (setup && isOrganizer) {
            Button(onClick = actions.onStartVoting, modifier = Modifier.testTag(TestTags.START_VOTING_BUTTON)) {
                Text("Start voting")
            }
        }
        // A voting session exists from VOTING onwards; once finalized the screen shows the winner
        if (meetup.status == MeetupStatus.VOTING || meetup.status == MeetupStatus.FINALIZED) {
            Button(onClick = actions.onOpenVoting, modifier = Modifier.testTag(TestTags.OPEN_VOTING_BUTTON)) {
                Text(if (meetup.status == MeetupStatus.VOTING) "Vote on a spot" else "See voting results")
            }
        }
        // Only participants have an entry to record the calendar event on (backend answers 403 otherwise)
        if (decided && submitted) calendarSection()
    }
}
