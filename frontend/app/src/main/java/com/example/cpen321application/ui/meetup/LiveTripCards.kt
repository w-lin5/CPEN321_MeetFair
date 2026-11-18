package com.example.cpen321application.ui.meetup

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import java.time.Instant

@Composable
internal fun DestinationCard(name: String) {
    MeetFairCard(shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = TealContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = RosePrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Destination: $name",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                )
                Text(
                    text = "Pan or zoom to find members. Locations older than $POSITION_MAX_AGE_SECONDS " +
                        "seconds are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SharingStatusCard(message: String) {
    val context = LocalContext.current
    MeetFairCard(
        containerColor = TealContainer,
        borderWidth = 0.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                )
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Text(
                    text = "Notifications are disabled. Enable them to see reminders that your location is " +
                        "being shared in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(settings)
                }) { Text("Enable sharing notifications", fontWeight = FontWeight.Bold, color = TealPrimary) }
            }
        }
    }
}

@Composable
internal fun ActiveTripCard(
    own: MeetupTrip,
    now: Instant,
    busy: Boolean,
    canPause: Boolean,
    onArrive: () -> Unit,
    onPause: () -> Unit,
) {
    val elapsed = maxOf(0, secondsBetween(own.startedAt, now) ?: 0)
    MeetFairCard(
        containerColor = TealContainer,
        borderWidth = 0.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Commute in progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
                Text(
                    text = "Time elapsed: ${formatTripDuration(elapsed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Pausing location sharing does not pause your commute timer. Tap I arrived to stop timing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TripActionButtons(busy, canPause, onArrive, onPause)
        }
    }
}

@Composable
private fun TripActionButtons(busy: Boolean, canPause: Boolean, onArrive: () -> Unit, onPause: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onArrive,
            enabled = !busy,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = StatusGreen,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Text("I arrived", fontWeight = FontWeight.ExtraBold)
        }

        if (canPause) {
            OutlinedButton(
                onClick = onPause,
                enabled = !busy,
                shape = CircleShape,
                border = BorderStroke(1.dp, TealPrimary),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Text("Pause sharing", fontWeight = FontWeight.Bold, color = TealPrimary)
            }
        }
    }
}

@Composable
internal fun ArrivedCard(actualSeconds: Long) {
    MeetFairCard(
        containerColor = StatusGreenLight,
        borderWidth = 0.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = StatusGreen,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "You arrived!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = StatusGreen,
                )
                Text(
                    text = "Actual commute: ${formatTripDuration(actualSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun MemberTripsCard(trips: List<MeetupTrip>) {
    MeetFairCard(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "MEMBER TRIPS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )

            if (trips.isEmpty()) {
                Text(
                    text = "Nobody has started a trip yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (trip in trips) MemberTripRow(trip)
            }
        }
    }
}

@Composable
private fun MemberTripRow(trip: MeetupTrip) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = trip.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )
        Surface(
            shape = CircleShape,
            color = when {
                trip.hasArrived -> StatusGreenLight
                trip.sharing -> TealContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = when {
                    trip.hasArrived -> "Arrived (${formatTripDuration(trip.actualSeconds ?: 0)})"
                    trip.sharing -> "On the way"
                    else -> "On the way · sharing paused"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    trip.hasArrived -> StatusGreen
                    trip.sharing -> TealPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun TripConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
