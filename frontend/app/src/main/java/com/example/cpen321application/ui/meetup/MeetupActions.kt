package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealPrimary

@Composable
internal fun MeetupBottomBar(
    ui: MeetupUi,
    running: MeetupAction?,
    navigation: MeetupNavigation,
    onSettle: () -> Unit,
    startTrip: @Composable () -> Unit,
) {
    val trip = ui.ownTrip
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        when (ui.meetup.status) {
            MeetupStatus.SETUP -> MeetupSetupActionButtons(
                isOrganizer = ui.isOrganizer,
                submitted = ui.submitted,
                onEnterDetails = navigation.onEnterDetails,
                onStartVoting = navigation.onStartVoting,
            )
            MeetupStatus.VOTING -> MeetupVotingActionButton(navigation.onOpenVoting)
            MeetupStatus.FINALIZED -> {
                if (trip == null) startTrip() else MeetupTripStatusCard(trip)
                if (ui.isOrganizer) {
                    SettleFairCreditButton(
                        submitting = running == MeetupAction.SETTLE,
                        enabled = running == null,
                        isPrimary = trip?.hasArrived == true,
                        onClick = onSettle,
                    )
                }
            }
            MeetupStatus.SETTLED -> if (trip != null) MeetupTripStatusCard(trip)
        }
    }
}

@Composable
private fun MeetupSetupActionButtons(
    isOrganizer: Boolean,
    submitted: Boolean,
    onEnterDetails: () -> Unit,
    onStartVoting: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onEnterDetails,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.PARTICIPATION_BUTTON),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (submitted) Icons.Default.Edit else Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (submitted) "Edit my details" else "Enter my details",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                )
            }
        }
        if (isOrganizer) {
            Button(
                onClick = onStartVoting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.START_VOTING_BUTTON),
            ) {
                Text(
                    text = "Start voting",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                )
            }
        }
    }
}

@Composable
private fun MeetupVotingActionButton(onOpenVoting: () -> Unit) {
    Button(
        onClick = onOpenVoting,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.OPEN_VOTING_BUTTON),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = BarChartIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Vote on a spot",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SettleFairCreditButton(
    submitting: Boolean,
    enabled: Boolean,
    isPrimary: Boolean,
    onClick: () -> Unit,
) {
    val buttonModifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.SETTLE_FAIR_CREDIT_BUTTON)
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = SwapHorizontalIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = if (submitting) "Settling..." else "Settle Fair Credit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
    if (isPrimary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RosePrimary,
                contentColor = Color.White,
                disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
            modifier = buttonModifier,
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(1.dp, RosePrimary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RosePrimary),
            modifier = buttonModifier,
        ) { label() }
    }
}
