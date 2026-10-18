package com.example.cpen321application.ui.voting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.VotingEvent
import com.example.cpen321application.data.VotingLiveUpdates
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
import com.example.cpen321application.model.VotingStatus
import com.example.cpen321application.ui.common.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_CONFLICT = 409

/** Everything the voting screen renders once the session has loaded. */
data class VotingState(
    val session: VotingSession,
    val results: VotingResults? = null,
    // Candidate whose vote request is in flight; voting buttons are disabled meanwhile
    val pendingCandidateId: String? = null,
    val closing: Boolean = false,
    // True while the Socket.IO room is joined; false falls back to manual refresh
    val live: Boolean = false,
    val error: String? = null,
    // Non-empty when the server refused to close because of a tie or zero votes; the organizer picks from these
    val manualPickOptions: List<String> = emptyList(),
) {
    val isOpen: Boolean get() = session.status == VotingStatus.OPEN
}

class VotingViewModel(
    private val voting: VotingRepository,
    private val liveUpdates: VotingLiveUpdates,
    private val meetupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<VotingState>>(UiState.Loading)
    val state: StateFlow<UiState<VotingState>> = _state.asStateFlow()

    private var liveJob: Job? = null

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            when (val result = voting.getSessionForMeetup(meetupId)) {
                is ApiResult.Failure -> _state.value = UiState.Error(result.error.message)
                is ApiResult.Success -> {
                    _state.value = UiState.Success(VotingState(result.data))
                    refreshResults()
                    subscribe(result.data.id)
                }
            }
        }
    }

    fun refreshResults() {
        val sessionId = current()?.session?.id ?: return
        viewModelScope.launch {
            when (val result = voting.getResults(sessionId)) {
                is ApiResult.Success -> edit { it.copy(results = result.data) }
                is ApiResult.Failure -> edit { it.copy(error = result.error.message) }
            }
        }
    }

    fun vote(candidateId: String) {
        val s = current() ?: return
        if (!s.isOpen || s.pendingCandidateId != null || s.session.myCandidateId == candidateId) return

        edit { it.copy(pendingCandidateId = candidateId, error = null) }
        viewModelScope.launch {
            when (val result = voting.submitVote(s.session.id, candidateId)) {
                is ApiResult.Success -> {
                    edit { it.copy(pendingCandidateId = null, session = it.session.copy(myCandidateId = candidateId)) }
                    // While live, the broadcast brings the new totals; otherwise fetch them
                    if (current()?.live != true) refreshResults()
                }
                is ApiResult.Failure -> {
                    edit { it.copy(pendingCandidateId = null, error = result.error.message) }
                    // 409 means someone closed voting meanwhile; reload to show the final state
                    if (result.error.httpCode == HTTP_CONFLICT) reloadSession()
                }
            }
        }
    }

    /** [winnerCandidateId] is only passed after the server asked for a manual pick. */
    fun close(winnerCandidateId: String? = null) {
        val s = current() ?: return
        if (!s.isOpen || s.closing) return

        edit { it.copy(closing = true, error = null) }
        viewModelScope.launch {
            when (val result = voting.closeSession(s.session.id, winnerCandidateId)) {
                is ApiResult.Success -> {
                    edit { it.copy(closing = false, manualPickOptions = emptyList(), session = result.data) }
                    refreshResults()
                }
                is ApiResult.Failure -> edit {
                    it.copy(
                        closing = false,
                        error = result.error.message,
                        manualPickOptions = if (result.error.httpCode == HTTP_CONFLICT) pickOptions(it) else emptyList(),
                    )
                }
            }
        }
    }

    fun dismissManualPick() = edit { it.copy(manualPickOptions = emptyList()) }

    private fun reloadSession() {
        val sessionId = current()?.session?.id ?: return
        viewModelScope.launch {
            val result = voting.getSession(sessionId)
            if (result is ApiResult.Success) edit { it.copy(session = result.data) }
            refreshResults()
        }
    }

    // Restarted on every load so a retry after an error reconnects
    private fun subscribe(sessionId: String) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            liveUpdates.events(sessionId).collect { event -> edit { applyEvent(it, event) } }
        }
    }

    private fun current(): VotingState? = (_state.value as? UiState.Success)?.data

    private fun edit(transform: (VotingState) -> VotingState) {
        _state.update { state -> if (state is UiState.Success) UiState.Success(transform(state.data)) else state }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) = viewModelFactory {
            initializer { VotingViewModel(c.votingRepository, c.votingLiveUpdates, meetupId) }
        }
    }
}

/** Pure state transition for one live event; kept outside the ViewModel so it is unit-testable. */
internal fun applyEvent(state: VotingState, event: VotingEvent): VotingState = when (event) {
    is VotingEvent.Results -> state.copy(results = event.results, live = true)
    // Keep the caller's own vote: the broadcast session is the same for every member
    is VotingEvent.Closed -> state.copy(
        session = event.session.copy(myCandidateId = state.session.myCandidateId),
        results = event.results,
        manualPickOptions = emptyList(),
    )
    is VotingEvent.Unavailable -> state.copy(live = false)
}

/** Candidates the organizer may pick: everyone if nobody voted, otherwise those tied for first. */
internal fun pickOptions(state: VotingState): List<String> {
    val results = state.results
    return if (results == null || results.voterCount == 0) {
        state.session.candidates.map { it.id }
    } else {
        results.leaders()
    }
}
