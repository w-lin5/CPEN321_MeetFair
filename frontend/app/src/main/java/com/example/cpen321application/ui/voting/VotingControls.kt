package com.example.cpen321application.ui.voting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary

// Locking makes the vote final and snapshots its Fair Credit weight; only locked votes count
@Composable
internal fun LockControls(state: VotingState, onLock: () -> Unit) {
    if (state.locked) {
        LockedVoteBanner(weight = state.myVote?.weight)
    } else if (state.myVote != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLock,
                enabled = !state.locking && state.pendingPlaceId == null,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RosePrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag(TestTags.LOCK_VOTE_BUTTON),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (state.locking) "Locking…" else "Submit vote",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    )
                }
            }
            Text(
                text = "Only locked votes count. You can still change your vote until you lock it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LockedVoteBanner(weight: Double?) {
    val suffix = if (weight != null) " (weight ${formatWeight(weight)})" else ""
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = TealContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TealPrimary,
            )
            Text(
                text = "Your vote is locked$suffix",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
                modifier = Modifier.testTag(TestTags.VOTE_LOCKED_TEXT),
            )
        }
    }
}

@Composable
internal fun FinalizeControls(state: VotingState, vm: VotingViewModel) {
    var confirmEarly by rememberSaveable { mutableStateOf(false) }

    Button(
        onClick = { if (state.tally?.everyoneLocked == false) confirmEarly = true else vm.finalize() },
        enabled = !state.finalizing,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TealPrimary,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(TestTags.FINALIZE_BUTTON),
    ) {
        Text(
            text = if (state.finalizing) "Finalizing…" else "Finalize meetup",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
        )
    }

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
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.pickWinnerButton(candidate.placeId)),
        ) { Text(candidate.name) }
    }
    TextButton(onClick = vm::dismissManualPick) { Text("Cancel") }
}
