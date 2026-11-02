package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupTrip
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealPrimary
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
fun LiveLocationScreen(container: AppContainer, meetupId: String, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    var meetup by remember { mutableStateOf<Meetup?>(null) }
    var trips by remember { mutableStateOf<List<MeetupTrip>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmArrival by remember { mutableStateOf(false) }
    var confirmPause by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val sharing by LiveLocationService.status.collectAsState()
    val notify = LocalNotify.current
    val userId = container.sessionStore.currentUserId()
    val own = trips.find { it.userId == userId }
    val reload: suspend () -> Unit = {
        mutex.withLock {
            when (val result = container.tripRepository.list(meetupId)) {
                is ApiResult.Success -> { trips = result.data; error = null }
                is ApiResult.Failure -> {
                    error = result.error.message
                    if (result.error.httpCode in listOf(401, 403, 404)) {
                        trips = emptyList()
                        meetup = null
                        if (sharing.meetupId == meetupId) LiveLocationService.stop(context)
                    }
                }
            }
        }
    }
    LaunchedEffect(meetupId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (val result = container.meetupRepository.get(meetupId)) {
                is ApiResult.Success -> meetup = result.data
                is ApiResult.Failure -> error = result.error.message
            }
            launch { while (true) { reload(); delay(15000) } }
            launch { while (true) { now = Instant.now(); delay(1000) } }
            container.realtimeClient.meetupEvents(meetupId).collect { event ->
                when (event) {
                    MeetupEvent.Joined, MeetupEvent.TripUpdated -> reload()
                    is MeetupEvent.Deleted -> {
                        if (sharing.meetupId == meetupId) LiveLocationService.stop(context)
                        if (event.notifyParticipant) notify("This meetup has been deleted by its organizer.")
                        onDeleted()
                    }
                    is MeetupEvent.Unavailable -> error = "Live connection unavailable. Refreshing periodically."
                    else -> Unit
                }
            }
        }
    }
    ScreenColumn("Live location", onBack) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = meetup?.title ?: "Loading meetup...",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val destination = meetup?.winner
            if (destination != null) {
                MeetFairCard(
                    modifier = Modifier.weight(1.2f),
                    containerColor = TealContainer,
                    borderColor = TealContainer,
                ) {
                    val camera = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(LatLng(destination.lat, destination.lng), 12f)
                    }
                    GoogleMap(Modifier.fillMaxWidth(), cameraPositionState = camera) {
                        Marker(state = rememberMarkerState(position = LatLng(destination.lat, destination.lng)), title = destination.name)
                        for (trip in trips) {
                            val point = trip.location
                            val recent = point != null && runCatching { now.epochSecond - Instant.parse(point.updatedAt).epochSecond < 90 }.getOrDefault(false)
                            if (trip.sharing && trip.status == "ACTIVE" && point != null && recent) key(trip.userId) {
                                AvatarMarker(trip.userId, trip.name, trip.photoUrl, LatLng(point.lat, point.lng))
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (destination != null) {
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
                                    text = "Destination: ${destination.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TealPrimary,
                                )
                                Text(
                                    text = "Pan or zoom to find members. Locations older than 90 seconds are hidden.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (sharing.meetupId == meetupId) {
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
                                    text = sharing.message,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TealPrimary,
                                )
                            }
                            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                Text(
                                    text = "Notifications are disabled. Enable them to see reminders that your location is being shared in the background.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                                }) { Text("Enable sharing notifications", fontWeight = FontWeight.Bold, color = TealPrimary) }
                            }
                        }
                    }
                }

                if (own?.status == "ACTIVE") {
                    val elapsed = runCatching { maxOf(0, now.epochSecond - Instant.parse(own.startedAt).epochSecond) }.getOrDefault(0)
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = { confirmArrival = true },
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

                                if (own.sharing || sharing.meetupId == meetupId) {
                                    OutlinedButton(
                                        onClick = { confirmPause = true },
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
                    }
                } else if (own?.status == "ARRIVED") {
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
                                    text = "Actual commute: ${formatTripDuration(own.actualSeconds ?: 0)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                val joined = meetup?.participants?.any { it.userId == userId } == true
                val due = meetup?.let { it.status == com.example.cpen321application.model.MeetupStatus.FINALIZED &&
                    runCatching { !Instant.parse(it.scheduledAt).isAfter(now) }.getOrDefault(false) } == true
                if (joined && due && own?.status != "ARRIVED" && sharing.meetupId != meetupId) {
                    StartTripButton(container, meetupId, label = if (own == null) "I'm leaving" else "Resume sharing") {
                        scope.launch { reload() }
                    }
                }

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
                            for (trip in trips) {
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
                                            trip.status == "ARRIVED" -> StatusGreenLight
                                            trip.sharing -> TealContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ) {
                                        Text(
                                            text = when {
                                                trip.status == "ARRIVED" -> "Arrived (${formatTripDuration(trip.actualSeconds ?: 0)})"
                                                trip.sharing -> "On the way"
                                                else -> "On the way · sharing paused"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                trip.status == "ARRIVED" -> StatusGreen
                                                trip.sharing -> TealPrimary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (confirmPause) AlertDialog(
            onDismissRequest = { confirmPause = false },
            title = { Text("Pause location sharing?") },
            text = { Text("Your location updates will stop, but your commute time will continue to be counted until you tap I arrived. Resuming sharing will not reset the timer.") },
            confirmButton = { TextButton(onClick = {
                confirmPause = false
                if (sharing.meetupId == meetupId) LiveLocationService.stop(context)
                scope.launch {
                    busy = true
                    when (val response = container.tripRepository.stop(meetupId)) {
                        is ApiResult.Failure -> error = response.error.message
                        is ApiResult.Success -> reload()
                    }
                    busy = false
                }
            }) { Text("Pause sharing") } },
            dismissButton = { TextButton(onClick = { confirmPause = false }) { Text("Cancel") } },
        )
        if (confirmArrival) AlertDialog(
            onDismissRequest = { confirmArrival = false },
            title = { Text("Have you arrived?") },
            text = { Text("This ends your trip and location sharing, and saves the actual travel time. You cannot restart this trip.") },
            confirmButton = { TextButton(onClick = {
                confirmArrival = false
                if (sharing.meetupId == meetupId) LiveLocationService.stop(context)
                scope.launch {
                    busy = true
                    when (val response = container.tripRepository.arrive(meetupId)) {
                        is ApiResult.Success -> reload()
                        is ApiResult.Failure -> error = "Arrival was not saved: ${response.error.message}. Tap I arrived to retry."
                    }
                    busy = false
                }
            }) { Text("I arrived") } },
            dismissButton = { TextButton(onClick = { confirmArrival = false }) { Text("Cancel") } },
        )
    }
}
