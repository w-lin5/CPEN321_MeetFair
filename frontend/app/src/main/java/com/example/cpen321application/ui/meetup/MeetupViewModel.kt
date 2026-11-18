package com.example.cpen321application.ui.meetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.model.VotesSnapshot
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.toUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Long-running actions the screen can trigger. Only one runs at a time. */
enum class MeetupAction { CALENDAR, DELETE, LEAVE, SETTLE }

/** State of the single in-flight (or last failed) action. */
data class ActionState(
    val running: MeetupAction? = null,
    val error: String? = null,
    val calendarAdded: Boolean = false,
)

/** Data loaded alongside the meetup itself. */
data class MeetupExtras(
    val organizerName: String? = null,
    val votes: VotesSnapshot? = null,
    val ownTrip: MeetupTrip? = null,
)

/** One-shot UI effects emitted by the ViewModel. */
sealed interface MeetupEffect {
    data class Notify(val message: String) : MeetupEffect
    object Exit : MeetupEffect
}

/** Loads one meetup for the status hub, keeps it live via realtime events, and runs its actions. */
class MeetupViewModel(
    private val meetups: MeetupRepository,
    private val groups: GroupRepository,
    private val voting: VotingRepository,
    private val meetupId: String,
    val userId: String?,
    private val events: () -> Flow<MeetupEvent>,
    /** Returns every trip of this meetup, or null if the request failed. */
    private val loadTrips: suspend () -> List<MeetupTrip>?,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Meetup>>(UiState.Loading)
    val state: StateFlow<UiState<Meetup>> = _state.asStateFlow()

    private val _extras = MutableStateFlow(MeetupExtras())
    val extras: StateFlow<MeetupExtras> = _extras.asStateFlow()

    private val _action = MutableStateFlow(ActionState())
    val action: StateFlow<ActionState> = _action.asStateFlow()

    private val _effects = Channel<MeetupEffect>(Channel.BUFFERED)
    val effects: Flow<MeetupEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var exited = false

    fun load() {
        refresh(showLoading = true)
        viewModelScope.launch { refreshTrip() }
    }

    private fun refresh(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) _state.value = UiState.Loading
            val result = meetups.get(meetupId)
            if (result is ApiResult.Success) {
                _state.value = result.toUiState()
                loadOrganizer(result.data)
                if (result.data.status == MeetupStatus.VOTING) {
                    val votesResult = voting.votes(meetupId)
                    if (votesResult is ApiResult.Success) _extras.update { it.copy(votes = votesResult.data) }
                }
            } else if (_state.value !is UiState.Success) {
                _state.value = result.toUiState()
            }
        }
    }

    private fun loadOrganizer(meetup: Meetup) {
        viewModelScope.launch {
            val detail = groups.detail(meetup.groupId)
            if (detail is ApiResult.Success) {
                val name = detail.data.members.find { it.userId == meetup.organizerId }?.name
                _extras.update { it.copy(organizerName = name) }
            }
        }
    }

    private suspend fun refreshTrip() {
        val trips = loadTrips() ?: return
        _extras.update { it.copy(ownTrip = trips.find { trip -> trip.userId == userId }) }
    }

    /** Collects realtime events until cancelled; call from a lifecycle-bound effect. */
    suspend fun listen() {
        events().collect { event ->
            when (event) {
                is MeetupEvent.Joined -> { refresh(showLoading = false); refreshTrip() }
                is MeetupEvent.RecommendationsReady -> refresh(showLoading = false)
                is MeetupEvent.StatusChanged -> applyStatusChange(event)
                is MeetupEvent.TripUpdated -> refreshTrip()
                is MeetupEvent.Deleted ->
                    if (_action.value.running != MeetupAction.DELETE) {
                        exit(if (event.notifyParticipant) "This meetup has been deleted by its organizer." else null)
                    }
                else -> Unit
            }
        }
    }

    private fun applyStatusChange(event: MeetupEvent.StatusChanged) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        loadJob?.cancel()
        _state.value = UiState.Success(current.copy(status = event.status, winner = event.winner ?: current.winner))
    }

    private suspend fun exit(message: String?) {
        if (exited) return
        exited = true
        if (message != null) _effects.send(MeetupEffect.Notify(message))
        _effects.send(MeetupEffect.Exit)
    }

    private fun <T> runAction(
        kind: MeetupAction,
        call: suspend () -> ApiResult<T>,
        onSuccess: suspend (T) -> Unit,
    ) {
        if (_action.value.running != null) return
        _action.update { it.copy(running = kind, error = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    onSuccess(result.data)
                    _action.update { it.copy(running = null) }
                }
                is ApiResult.Failure -> _action.update { it.copy(running = null, error = result.error.message) }
            }
        }
    }

    fun settle() = runAction(MeetupAction.SETTLE, call = { meetups.settle(meetupId) }) { settled ->
        loadJob?.cancel()
        _state.value = UiState.Success(settled)
        _effects.send(MeetupEffect.Notify("Fair Credit updated. View balances in the group's Fair Credit page."))
    }

    fun leaveMeetup() = runAction(MeetupAction.LEAVE, call = { meetups.withdrawParticipation(meetupId) }) {
        refresh(showLoading = false)
    }

    fun deleteMeetup() {
        if (exited) return
        runAction(MeetupAction.DELETE, call = { meetups.delete(meetupId) }) { exit("Meetup deleted.") }
    }

    /** [accessToken] is the short-lived calendar.events token from CalendarAuthorizer. */
    fun addToCalendar(accessToken: String) =
        runAction(MeetupAction.CALENDAR, call = { meetups.addToCalendar(meetupId, accessToken) }) {
            _action.update { it.copy(calendarAdded = true) }
        }

    fun setCalendarError(message: String) {
        _action.update { it.copy(error = message) }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) = viewModelFactory {
            initializer {
                MeetupViewModel(
                    meetups = c.meetupRepository,
                    groups = c.groupRepository,
                    voting = c.votingRepository,
                    meetupId = meetupId,
                    userId = c.sessionStore.currentUserId(),
                    events = { c.realtimeClient.meetupEvents(meetupId) },
                    loadTrips = {
                        val result = c.tripRepository.list(meetupId)
                        if (result is ApiResult.Success) result.data else null
                    },
                )
            }
        }
    }
}
