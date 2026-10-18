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
import androidx.compose.ui.platform.testTag
import com.example.cpen321application.AppContainer
import com.example.cpen321application.model.Candidate
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import java.util.Locale

/** Live weighted vote on the meetup's candidate places; the organizer closes it to finalize the meetup. */
@Composable
fun VotingScreen(container: AppContainer, meetupId: String, onBack: () -> Unit) {
    val vm: VotingViewModel = screenViewModel(VotingViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    val userId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Vote on a spot", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { voting ->
            VotingContent(voting, isOrganizer = voting.session.createdBy == userId, vm)
        }
    }
}

@Composable
private fun VotingContent(state: VotingState, isOrganizer: Boolean, vm: VotingViewModel) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        VotingHeader(state, onRefresh = vm::refreshResults)

        state.session.candidates.forEach { candidate ->
            CandidateCard(candidate, state, onVote = { vm.vote(candidate.id) })
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }

        if (isOrganizer && state.isOpen) CloseVotingControls(state, vm)
    }
}

@Composable
private fun VotingHeader(state: VotingState, onRefresh: () -> Unit) {
    val results = state.results
    Text(
        if (state.isOpen) "Voting is open" else "Voting is closed",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(TestTags.VOTING_STATUS_TEXT),
    )
    if (results != null) {
        Text(
            "${results.voterCount} of ${results.eligibleVoterCount} member(s) have voted",
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
private fun CandidateCard(candidate: Candidate, state: VotingState, onVote: () -> Unit) {
    val total = state.results?.totalFor(candidate.id)
    val mine = state.session.myCandidateId == candidate.id
    val winner = state.session.winnerCandidateId == candidate.id

    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.candidateItem(candidate.id))) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            Text(candidate.name, style = MaterialTheme.typography.titleMedium)
            if (candidate.address.isNotBlank()) Text(candidate.address)
            if (total != null) {
                Text("Votes: ${total.voteCount} · Weighted: ${formatWeight(total.totalWeight)}")
            }
            if (winner) {
                Text(
                    "Winner",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TestTags.WINNER_TEXT),
                )
            }
            if (state.isOpen) {
                Button(
                    onClick = onVote,
                    enabled = !mine && state.pendingCandidateId == null,
                    modifier = Modifier.testTag(TestTags.voteButton(candidate.id)),
                ) {
                    Text(
                        when {
                            mine -> "Your vote"
                            state.pendingCandidateId == candidate.id -> "Voting…"
                            state.session.myCandidateId != null -> "Change vote"
                            else -> "Vote"
                        },
                    )
                }
            } else if (mine) {
                Text("Your vote")
            }
        }
    }
}

@Composable
private fun CloseVotingControls(state: VotingState, vm: VotingViewModel) {
    var confirmEarlyClose by rememberSaveable { mutableStateOf(false) }

    Button(
        // Warn before force-closing while some members have not voted yet
        onClick = { if (state.results?.everyoneVoted == false) confirmEarlyClose = true else vm.close() },
        enabled = !state.closing,
        modifier = Modifier.testTag(TestTags.CLOSE_VOTING_BUTTON),
    ) { Text(if (state.closing) "Closing…" else "Close voting") }

    if (confirmEarlyClose) {
        AlertDialog(
            onDismissRequest = { confirmEarlyClose = false },
            title = { Text("Close voting early?") },
            text = { Text("Not every member has voted yet. Close the poll anyway?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEarlyClose = false
                        vm.close()
                    },
                    modifier = Modifier.testTag(TestTags.CONFIRM_CLOSE_BUTTON),
                ) { Text("Close anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmEarlyClose = false }) { Text("Keep voting") } },
        )
    }

    if (state.manualPickOptions.isNotEmpty()) ManualPick(state, vm)
}

// Shown when the server could not decide (tie or zero votes): the organizer chooses the winner
@Composable
private fun ManualPick(state: VotingState, vm: VotingViewModel) {
    val options = state.session.candidates.filter { it.id in state.manualPickOptions }
    Text("Pick the winner", style = MaterialTheme.typography.titleMedium)
    options.forEach { candidate ->
        OutlinedButton(
            onClick = { vm.close(candidate.id) },
            enabled = !state.closing,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.pickWinnerButton(candidate.id)),
        ) { Text(candidate.name) }
    }
    TextButton(onClick = vm::dismissManualPick) { Text("Cancel") }
}

// Fair Credit weights may be fractional; show at most two decimals
private fun formatWeight(weight: Double): String =
    if (weight % 1.0 == 0.0) weight.toLong().toString() else String.format(Locale.US, "%.2f", weight)
