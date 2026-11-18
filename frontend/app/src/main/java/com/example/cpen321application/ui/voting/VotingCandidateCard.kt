package com.example.cpen321application.ui.voting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import java.util.Locale

@Composable
internal fun CandidateCard(candidate: PlaceCandidate, state: VotingState, canVote: Boolean, onVote: () -> Unit) {
    val context = LocalContext.current
    val notify = LocalNotify.current
    val openMap: () -> Unit = { openCandidateInMaps(context, candidate, notify) }
    val total = state.tally?.totalFor(candidate.placeId)
    val mine = state.myVote?.placeId == candidate.placeId
    val winner = state.meetup.winner?.placeId == candidate.placeId

    MeetFairCard(
        modifier = Modifier.testTag(TestTags.candidateItem(candidate.placeId)),
        containerColor = TealContainer,
        borderWidth = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CandidateHeader(candidate, picks = total?.voteCount ?: 0, onOpenMap = openMap)

            if (total != null) {
                val weight = "${formatWeight(total.weight)} (${formatShare(total.share)})"
                Text(
                    text = "Locked weight: $weight",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (winner) {
                Text(
                    text = "Winner",
                    color = RosePrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag(TestTags.WINNER_TEXT),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canVote && state.canChangeVote) {
                    VoteButton(state, candidate.placeId, mine, onVote)
                } else if (mine) {
                    MyVoteBadge(locked = state.locked)
                }
            }
        }
    }
}

private fun openCandidateInMaps(context: Context, candidate: PlaceCandidate, notify: (String) -> Unit) {
    val query = listOf(candidate.name, candidate.address).filter { it.isNotBlank() }.joinToString(", ")
        .ifBlank { "${candidate.lat},${candidate.lng}" }
    val uri = Uri.parse("https://www.google.com/maps/search/").buildUpon()
        .appendQueryParameter("api", "1")
        .appendQueryParameter("query", query)
        .apply {
            if (candidate.placeId.isNotBlank()) appendQueryParameter("query_place_id", candidate.placeId)
        }
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        notify("No map app or browser is available to open this location.")
    }
}

@Composable
private fun CandidateHeader(candidate: PlaceCandidate, picks: Int, onOpenMap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = candidate.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = TealPrimary,
            modifier = Modifier
                .testTag(TestTags.candidateMapLink(candidate.placeId))
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open in Google Maps", onClick = onOpenMap),
        )

        Text(
            text = "Picks: $picks",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (candidate.address.isNotBlank()) {
        Text(
            text = candidate.address,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(
                role = Role.Button,
                onClickLabel = "Open in Google Maps",
                onClick = onOpenMap,
            ),
        )
    }
}

@Composable
private fun VoteButton(state: VotingState, placeId: String, mine: Boolean, onVote: () -> Unit) {
    Button(
        onClick = onVote,
        enabled = !mine && state.pendingPlaceId == null,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TealPrimary,
            contentColor = Color.White,
        ),
        modifier = Modifier.testTag(TestTags.voteButton(placeId)),
    ) {
        Text(
            text = voteLabel(state, placeId, mine),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MyVoteBadge(locked: Boolean) {
    Surface(
        shape = CircleShape,
        color = TealPrimary.copy(alpha = 0.15f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = if (locked) "Your locked vote" else "Your vote",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
        }
    }
}

private fun voteLabel(state: VotingState, placeId: String, mine: Boolean): String = when {
    mine -> "Your vote"
    state.pendingPlaceId == placeId -> "Voting…"
    state.myVote != null -> "Change vote"
    else -> "Vote"
}

// Fair Credit weights may be fractional; show at most two decimals
internal fun formatWeight(weight: Double): String =
    if (weight % 1.0 == 0.0) weight.toLong().toString() else String.format(Locale.US, "%.2f", weight)

private const val PERCENT = 100

private fun formatShare(share: Double): String = String.format(Locale.US, "%.0f%%", share * PERCENT)
