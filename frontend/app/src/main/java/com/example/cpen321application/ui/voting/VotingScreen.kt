package com.example.cpen321application.ui.voting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_CONFLICT = 409
private const val PERCENT = 100

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
}

class VotingViewModel(
    private val voting: VotingRepository,
    private val meetups: MeetupRepository,
    private val realtime: RealtimeClient,
    private val meetupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<VotingState>>(UiState.Loading)
    val state: StateFlow<UiState<VotingState>> = _state.asStateFlow()

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
        if (!s.isOpen || s.locked || s.pendingPlaceId != null || s.myVote?.placeId == placeId) return

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
        if (!s.isOpen || s.locked || s.locking || s.myVote == null) return

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
                edit { applyEvent(it, event) }
                // The tally may have changed before the room was joined
                if (event is MeetupEvent.Joined) {
                    refreshVotes()
                    reloadMeetup()
                }
            }
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
    MeetupEvent.Joined -> state.copy(live = true)
    is MeetupEvent.VoteUpdated -> state.copy(tally = event.tally, live = true)
    is MeetupEvent.StatusChanged -> state.copy(
        meetup = state.meetup.copy(status = event.status, winner = event.winner ?: state.meetup.winner),
        manualPickOptions = emptyList(),
    )
    // The candidates are already loaded once this screen is open
    MeetupEvent.RecommendationsReady -> state
    is MeetupEvent.Unavailable -> state.copy(live = false)
}

/** Candidates the organizer may pick: everyone if nothing is locked, otherwise those tied for first. */
internal fun pickOptions(state: VotingState): List<String> {
    val tally = state.tally
    return if (tally == null || tally.lockedCount == 0) state.candidates.map { it.placeId } else tally.leaders()
}

/** Live weighted vote on the meetup's candidate places; the organizer finalizes it. */
@Composable
fun VotingScreen(container: AppContainer, meetupId: String, onBack: () -> Unit) {
    val vm: VotingViewModel = screenViewModel(VotingViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    val userId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Vote on a spot", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { voting ->
            VotingContent(voting, userId, vm)
        }
    }
}

@Composable
private fun VotingContent(state: VotingState, userId: String?, vm: VotingViewModel) {
    val isOrganizer = state.meetup.organizerId == userId
    val isParticipant = state.meetup.participants.any { it.userId == userId }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        VotingHeader(state, onRefresh = vm::refreshVotes)

        state.candidates.forEach { candidate ->
            CandidateCard(candidate, state, canVote = isParticipant, onVote = { vm.vote(candidate.placeId) })
        }

        if (isParticipant && state.isOpen) LockControls(state, onLock = vm::lock)
        if (!isParticipant && state.isOpen) Text("Only members who entered their details can vote.")

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }

        if (isOrganizer && state.isOpen) FinalizeControls(state, vm)
    }
}

@Composable
private fun VotingHeader(state: VotingState, onRefresh: () -> Unit) {
    val tally = state.tally
    Text(
        if (state.isOpen) "Voting is open" else "Voting is closed",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(TestTags.VOTING_STATUS_TEXT),
    )
    if (tally != null) {
        Text(
            "${tally.lockedCount} of ${tally.participantCount} participant(s) have locked their vote",
            modifier = Modifier.testTag(TestTags.VOTER_COUNT_TEXT),
        )
    }
    if (state.isOpen) {
        if (state.live) {
            Text("Live: votes update automatically", modifier = Modifier.testTag(TestTags.VOTING_LIVE_TEXT))
        } else {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag(TestTags.REFRESH_RESULTS_BUTTON)) {
                Text("Refresh results")
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: PlaceCandidate, state: VotingState, canVote: Boolean, onVote: () -> Unit) {
    val context = LocalContext.current
    val total = state.tally?.totalFor(candidate.placeId)
    val mine = state.myVote?.placeId == candidate.placeId
    val winner = state.meetup.winner?.placeId == candidate.placeId

    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.candidateItem(candidate.placeId))) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            TextButton(
                onClick = { openPlaceInMaps(context, candidate) },
                modifier = Modifier.testTag("candidate_map_${candidate.placeId}"),
            ) {
                Text(candidate.name, style = MaterialTheme.typography.titleMedium)
            }
            if (candidate.address.isNotBlank()) Text(candidate.address)
            if (total != null) {
                val weight = "${formatWeight(total.weight)} (${formatShare(total.share)})"
                Text("Picks: ${total.voteCount} · Locked weight: $weight")
            }
            if (winner) {
                Text(
                    "Winner",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TestTags.WINNER_TEXT),
                )
            }
            if (state.isOpen && canVote && !state.locked) {
                Button(
                    onClick = onVote,
                    enabled = !mine && state.pendingPlaceId == null,
                    modifier = Modifier.testTag(TestTags.voteButton(candidate.placeId)),
                ) { Text(voteLabel(state, candidate.placeId, mine)) }
            } else if (mine) {
                Text(if (state.locked) "Your locked vote" else "Your vote")
            }
        }
    }
}

private fun voteLabel(state: VotingState, placeId: String, mine: Boolean): String = when {
    mine -> "Your vote"
    state.pendingPlaceId == placeId -> "Voting…"
    state.myVote != null -> "Change vote"
    else -> "Vote"
}

// Locking makes the vote final and snapshots its Fair Credit weight; only locked votes count
@Composable
private fun LockControls(state: VotingState, onLock: () -> Unit) {
    val weight = state.myVote?.weight
    if (state.locked) {
        val suffix = if (weight != null) " (weight ${formatWeight(weight)})" else ""
        Text("Your vote is locked$suffix", modifier = Modifier.testTag(TestTags.VOTE_LOCKED_TEXT))
    } else if (state.myVote != null) {
        Button(
            onClick = onLock,
            enabled = !state.locking && state.pendingPlaceId == null,
            modifier = Modifier.testTag(TestTags.LOCK_VOTE_BUTTON),
        ) { Text(if (state.locking) "Locking…" else "Lock my vote") }
        Text("Only locked votes count. You can still change your vote until you lock it.")
    }
}

@Composable
private fun FinalizeControls(state: VotingState, vm: VotingViewModel) {
    var confirmEarly by rememberSaveable { mutableStateOf(false) }

    Button(
        // Warn before finalizing while some participants have not locked their vote yet
        onClick = { if (state.tally?.everyoneLocked == false) confirmEarly = true else vm.finalize() },
        enabled = !state.finalizing,
        modifier = Modifier.testTag(TestTags.FINALIZE_BUTTON),
    ) { Text(if (state.finalizing) "Finalizing…" else "Finalize meetup") }

    if (confirmEarly) {
        AlertDialog(
            onDismissRequest = { confirmEarly = false },
            title = { Text("Finalize early?") },
            text = { Text("Not every participant has locked their vote, and unlocked votes do not count.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEarly = false
                        vm.finalize()
                    },
                    modifier = Modifier.testTag(TestTags.CONFIRM_FINALIZE_BUTTON),
                ) { Text("Finalize anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmEarly = false }) { Text("Keep voting") } },
        )
    }

    if (state.manualPickOptions.isNotEmpty()) ManualPick(state, vm)
}

// Shown when the server could not decide (tie or no locked votes): the organizer chooses the winner
@Composable
private fun ManualPick(state: VotingState, vm: VotingViewModel) {
    val options = state.candidates.filter { it.placeId in state.manualPickOptions }
    Text("Pick the winner", style = MaterialTheme.typography.titleMedium)
    options.forEach { candidate ->
        OutlinedButton(
            onClick = { vm.finalize(candidate.placeId) },
            enabled = !state.finalizing,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.pickWinnerButton(candidate.placeId)),
        ) { Text(candidate.name) }
    }
    TextButton(onClick = vm::dismissManualPick) { Text("Cancel") }
}

// Fair Credit weights may be fractional; show at most two decimals
private fun formatWeight(weight: Double): String =
    if (weight % 1.0 == 0.0) weight.toLong().toString() else String.format(Locale.US, "%.2f", weight)

private fun formatShare(share: Double): String = String.format(Locale.US, "%.0f%%", share * PERCENT)
