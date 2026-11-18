package com.example.cpen321application.ui.voting

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.AppContainer
import com.example.cpen321application.R
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.MeetupStatusNotifications
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.StatusClosed
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary

/** Live weighted vote on the meetup's candidate places; the organizer finalizes it. */
@Composable
fun VotingScreen(container: AppContainer, meetupId: String, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val vm: VotingViewModel = screenViewModel(VotingViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    MeetupStatusNotifications((state as? UiState.Success)?.data?.meetup)
    val deleted by vm.deleted.collectAsState()
    val notifyDeletion by vm.notifyDeletion.collectAsState()
    val notify = LocalNotify.current
    val userId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(deleted) {
        if (deleted) {
            if (notifyDeletion) notify("This meetup has been deleted by its organizer.")
            onDeleted()
        }
    }

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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VotingHeader(state, onRefresh = vm::refreshVotes)

        // Map Graphic Card using R.drawable.meetfair_map_meetup
        MeetFairCard(
            containerColor = TealContainer,
            borderWidth = 0.dp,
        ) {
            Image(
                painter = painterResource(R.drawable.meetfair_map_meetup),
                contentDescription = "Voting map graphic",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
        }

        // Candidate Cards
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (state.isOpen) StatusGreen else StatusClosed,
                modifier = Modifier.size(10.dp),
            ) {}

            Text(
                text = if (state.isOpen) "Voting is open" else "Voting is closed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
                modifier = Modifier.testTag(TestTags.VOTING_STATUS_TEXT),
            )
        }

        if (tally != null) {
            Text(
                text = "${tally.lockedCount} of ${tally.participantCount} participant(s) have locked their vote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.VOTER_COUNT_TEXT),
            )
        }

        if (state.isOpen) {
            if (state.live) {
                Text(
                    text = "Live: votes update automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = TealPrimary,
                    modifier = Modifier.testTag(TestTags.VOTING_LIVE_TEXT),
                )
            } else {
                OutlinedButton(
                    onClick = onRefresh,
                    shape = CircleShape,
                    modifier = Modifier.testTag(TestTags.REFRESH_RESULTS_BUTTON),
                ) {
                    Text("Refresh results")
                }
            }
        }
    }
}
