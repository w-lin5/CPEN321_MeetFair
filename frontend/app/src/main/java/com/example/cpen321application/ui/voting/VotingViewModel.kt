package com.example.cpen321application.ui.voting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.RealtimeClient
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.VoteTally
import com.example.cpen321application.ui.common.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_CONFLICT = 409

/** Everything the voting screen renders once the meetup and its candidates have loaded. */
data class VotingState(
    val meetup: Meetup,
    val candidates: List<PlaceCandidate>,
    val tally: VoteTally? = null,
    val myVote: MyVote? = null,
    // Candidate whose vote request is in flight; voting buttons are disabled meanwhile
    val pendingPlaceId: String? = null,
    val locking: Boolean = false,
    val finalizing: Boolean = false,
    // True while the Socket.IO room is joined; false falls back to manual refresh
    val live: Boolean = false,
    val error: String? = null,
    // Non-empty when the server refused to finalize because of a tie or no locked votes; the organizer picks
    val manualPickOptions: List<String> = emptyList(),
) {
    val isOpen: Boolean get() = meetup.status == MeetupStatus.VOTING
    val locked: Boolean get() = myVote?.locked == true

    /** Voting is open and this user's vote is not locked yet, so it can still change. */
    val canChangeVote: Boolean get() = isOpen && !locked
}

class VotingViewModel(
    private val voting: VotingRepository,
    private val meetups: MeetupRepository,
    private val realtime: RealtimeClient,
    private val meetupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<VotingState>>(UiState.Loading)
    val state: StateFlow<UiState<VotingState>> = _state.asStateFlow()
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()
    private val _notifyDeletion = MutableStateFlow(false)
    val notifyDeletion: StateFlow<Boolean> = _notifyDeletion.asStateFlow()

    private var liveJob: Job? = null

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            val meetup = meetups.get(meetupId)
            val recommendation = voting.recommendations(meetupId)
            _state.value = when {
                meetup is ApiResult.Failure -> UiState.Error(meetup.error.message)
                recommendation is ApiResult.Failure -> UiState.Error(recommendation.error.message)
                meetup is ApiResult.Success && recommendation is ApiResult.Success ->
                    UiState.Success(VotingState(meetup.data, recommendation.data.candidates))
                else -> UiState.Error("Could not load voting")
            }
            if (_state.value is UiState.Success) {
                refreshVotes()
                subscribe()
            }
        }
    }

    fun refreshVotes() {
        if (current() == null) return
        viewModelScope.launch {
            when (val result = voting.votes(meetupId)) {
                is ApiResult.Success -> edit { it.copy(tally = result.data.tally, myVote = result.data.myVote) }
                is ApiResult.Failure -> edit { it.copy(error = result.error.message) }
            }
        }
    }

    fun vote(placeId: String) {
        val s = current() ?: return
        if (!s.canChangeVote || s.pendingPlaceId != null || s.myVote?.placeId == placeId) return

        edit { it.copy(pendingPlaceId = placeId, error = null) }
        viewModelScope.launch {
            when (val result = voting.vote(meetupId, placeId)) {
                is ApiResult.Success -> {
                    edit { it.copy(pendingPlaceId = null, myVote = result.data) }
                    // While live, the broadcast brings the new tally; otherwise fetch it
                    if (current()?.live != true) refreshVotes()
                }
                is ApiResult.Failure -> {
                    edit { it.copy(pendingPlaceId = null, error = result.error.message) }
                    // 409 means the vote got locked or voting ended meanwhile; reload to show the real state
                    if (result.error.httpCode == HTTP_CONFLICT) reloadMeetup()
                }
            }
        }
    }

    fun lock() {
        val s = current() ?: return
        if (!s.canChangeVote || s.locking || s.myVote == null) return

        edit { it.copy(locking = true, error = null) }
        viewModelScope.launch {
            when (val result = voting.lock(meetupId)) {
                is ApiResult.Success -> {
                    edit { it.copy(locking = false, myVote = result.data) }
                    if (current()?.live != true) refreshVotes()
                }
                is ApiResult.Failure -> edit { it.copy(locking = false, error = result.error.message) }
            }
        }
    }

    /** [placeId] is only passed after the server asked for a manual pick. */
    fun finalize(placeId: String? = null) {
        val s = current() ?: return
        if (!s.isOpen || s.finalizing) return

        edit { it.copy(finalizing = true, error = null) }
        viewModelScope.launch {
            when (val result = voting.finalize(meetupId, placeId)) {
                is ApiResult.Success -> {
                    edit { it.copy(finalizing = false, manualPickOptions = emptyList(), meetup = result.data) }
                    refreshVotes()
                }
                is ApiResult.Failure -> edit {
                    val conflict = result.error.httpCode == HTTP_CONFLICT
                    it.copy(
                        finalizing = false,
                        error = result.error.message,
                        manualPickOptions = if (conflict) pickOptions(it) else emptyList(),
                    )
                }
            }
        }
    }

    fun dismissManualPick() = edit { it.copy(manualPickOptions = emptyList()) }

    private fun reloadMeetup() {
        viewModelScope.launch {
            val result = meetups.get(meetupId)
            if (result is ApiResult.Success) edit { it.copy(meetup = result.data) }
            refreshVotes()
        }
    }

    // Restarted on every load so a retry after an error reconnects
    private fun subscribe() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            realtime.meetupEvents(meetupId).collect { event ->
                if (event is MeetupEvent.Deleted) onMeetupDeleted(event) else onLiveEvent(event)
            }
        }
    }

    private fun onMeetupDeleted(event: MeetupEvent.Deleted) {
        _notifyDeletion.value = event.notifyParticipant
        _deleted.value = true
        _state.value = if (event.notifyParticipant)
            UiState.Error("This meetup has been deleted by its organizer.") else UiState.Empty
    }

    private fun onLiveEvent(event: MeetupEvent) {
        edit { applyEvent(it, event) }
        // The tally may have changed before the room was joined
        if (event is MeetupEvent.Joined) {
            refreshVotes()
            reloadMeetup()
        }
    }

    private fun current(): VotingState? = (_state.value as? UiState.Success)?.data

    private fun edit(transform: (VotingState) -> VotingState) {
        _state.update { state -> if (state is UiState.Success) UiState.Success(transform(state.data)) else state }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) = viewModelFactory {
            initializer { VotingViewModel(c.votingRepository, c.meetupRepository, c.realtimeClient, meetupId) }
        }
    }
}

/** Pure state transition for one live event; kept outside the ViewModel so it is unit-testable. */
internal fun applyEvent(state: VotingState, event: MeetupEvent): VotingState = when (event) {
    is MeetupEvent.Deleted -> state.copy(live = false,
        error = if (event.notifyParticipant) "This meetup has been deleted by its organizer." else null)
    MeetupEvent.Joined -> state.copy(live = true)
    is MeetupEvent.VoteUpdated -> state.copy(tally = event.tally, live = true)
    is MeetupEvent.StatusChanged -> state.copy(
        meetup = state.meetup.copy(status = event.status, winner = event.winner ?: state.meetup.winner),
        manualPickOptions = emptyList(),
    )
    // The candidates are already loaded once this screen is open
    MeetupEvent.RecommendationsReady -> state
    MeetupEvent.TripUpdated -> state
    is MeetupEvent.Unavailable -> state.copy(live = false)
}

/** Candidates the organizer may pick: everyone if nothing is locked, otherwise those tied for first. */
internal fun pickOptions(state: VotingState): List<String> {
    val tally = state.tally
    return if (tally == null || tally.lockedCount == 0) state.candidates.map { it.placeId } else tally.leaders()
}
