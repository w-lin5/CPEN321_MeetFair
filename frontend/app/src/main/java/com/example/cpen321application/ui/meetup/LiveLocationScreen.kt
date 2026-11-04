package com.example.cpen321application.ui.meetup

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.cpen321application.data.SharedPosition
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.PlaceCandidate
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

/** Meetup, member trips and transient UI state of the live-location screen, plus the calls that refresh them. */
private class LiveTripsState(
    private val container: AppContainer,
    val meetupId: String,
    private val appContext: Context,
) {
    private val mutex = Mutex()
    var meetup by mutableStateOf<Meetup?>(null)
        private set
    var trips by mutableStateOf<List<MeetupTrip>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var now by mutableStateOf(Instant.now())
        private set

    fun stopSharingHere() {
        if (LiveLocationService.status.value.meetupId == meetupId) LiveLocationService.stop(appContext)
    }

    suspend fun loadMeetup() {
        when (val result = container.meetupRepository.get(meetupId)) {
            is ApiResult.Success -> meetup = result.data
            is ApiResult.Failure -> error = result.error.message
        }
    }

    suspend fun reload() {
        mutex.withLock {
            when (val result = container.tripRepository.list(meetupId)) {
                is ApiResult.Success -> { trips = result.data; error = null }
                is ApiResult.Failure -> {
                    error = result.error.message
                    if (result.error.httpCode in listOf(401, 403, 404)) {
                        trips = emptyList()
                        meetup = null
                        stopSharingHere()
                    }
                }
            }
        }
    }

    suspend fun refreshPeriodically() {
        while (true) { reload(); delay(15000) }
    }

    suspend fun runClock() {
        while (true) { now = Instant.now(); delay(1000) }
    }

    suspend fun pauseSharing() {
        busy = true
        when (val response = container.tripRepository.stop(meetupId)) {
            is ApiResult.Failure -> error = response.error.message
            is ApiResult.Success -> reload()
        }
        busy = false
    }

    suspend fun arrive() {
        busy = true
        when (val response = container.tripRepository.arrive(meetupId)) {
            is ApiResult.Success -> reload()
            is ApiResult.Failure -> error = "Arrival was not saved: ${response.error.message}. Tap I arrived to retry."
        }
        busy = false
    }

    /** The user joined this finalized meetup, its start time has come, and they have not arrived yet. */
    fun canStartTrip(userId: String?, own: MeetupTrip?): Boolean {
        val current = meetup ?: return false
        val joined = current.participants.any { it.userId == userId }
        val due = current.status == MeetupStatus.FINALIZED &&
            runCatching { !Instant.parse(current.scheduledAt).isAfter(now) }.getOrDefault(false)
        return joined && due && own?.status != "ARRIVED"
    }
}

@Composable
fun LiveLocationScreen(container: AppContainer, meetupId: String, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val live = remember(meetupId) { LiveTripsState(container, meetupId, appContext) }
    var confirmArrival by remember { mutableStateOf(false) }
    var confirmPause by remember { mutableStateOf(false) }
    LiveTripUpdates(container, live, onDeleted)

    ScreenColumn("Live location", onBack) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = live.meetup?.title ?: "Loading meetup...",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
            live.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val destination = live.meetup?.winner
            if (destination != null) LiveMapCard(destination, live.trips, live.now, Modifier.weight(1.2f))

            LiveTripDetails(
                container = container,
                live = live,
                onArrive = { confirmArrival = true },
                onPause = { confirmPause = true },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (confirmPause) TripConfirmDialog(
            title = "Pause location sharing?",
            text = "Your location updates will stop, but your commute time will continue to be counted until you tap I arrived. Resuming sharing will not reset the timer.",
            confirmLabel = "Pause sharing",
            onDismiss = { confirmPause = false },
            onConfirm = { live.stopSharingHere(); scope.launch { live.pauseSharing() } },
        )
        if (confirmArrival) TripConfirmDialog(
            title = "Have you arrived?",
            text = "This ends your trip and location sharing, and saves the actual travel time. You cannot restart this trip.",
            confirmLabel = "I arrived",
            onDismiss = { confirmArrival = false },
            onConfirm = { live.stopSharingHere(); scope.launch { live.arrive() } },
        )
    }
}

// While the screen is started: loads the meetup, polls trips, ticks the clock and reacts to realtime events
@Composable
private fun LiveTripUpdates(container: AppContainer, live: LiveTripsState, onDeleted: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notify = LocalNotify.current
    LaunchedEffect(live.meetupId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            live.loadMeetup()
            launch { live.refreshPeriodically() }
            launch { live.runClock() }
            container.realtimeClient.meetupEvents(live.meetupId).collect { event ->
                when (event) {
                    MeetupEvent.Joined, MeetupEvent.TripUpdated -> live.reload()
                    is MeetupEvent.Deleted -> {
                        live.stopSharingHere()
                        if (event.notifyParticipant) notify("This meetup has been deleted by its organizer.")
                        onDeleted()
                    }
                    is MeetupEvent.Unavailable -> live.error = "Live connection unavailable. Refreshing periodically."
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LiveMapCard(destination: PlaceCandidate, trips: List<MeetupTrip>, now: Instant, modifier: Modifier) {
    val context = LocalContext.current
    val playServicesAvailable = remember(context) {
        try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Throwable) {
            false
        }
    }
    MeetFairCard(
        modifier = modifier,
        containerColor = TealContainer,
        borderColor = TealContainer,
    ) {
        val camera = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(destination.lat, destination.lng), 12f)
        }
        if (playServicesAvailable) {
            GoogleMap(Modifier.fillMaxWidth(), cameraPositionState = camera) {
                Marker(state = rememberMarkerState(position = LatLng(destination.lat, destination.lng)), title = destination.name)
                for (trip in trips) {
                    val point = freshSharedPosition(trip, now)
                    if (point != null) key(trip.userId) {
                        AvatarMarker(trip.userId, trip.name, trip.photoUrl, LatLng(point.lat, point.lng))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Destination: ${destination.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                )
            }
        }
    }
}

// A member's position is shown only while their trip is active, shared, and updated in the last 90 seconds
private fun freshSharedPosition(trip: MeetupTrip, now: Instant): SharedPosition? {
    val point = trip.location ?: return null
    val recent = runCatching { now.epochSecond - Instant.parse(point.updatedAt).epochSecond < 90 }.getOrDefault(false)
    return point.takeIf { trip.sharing && trip.status == "ACTIVE" && recent }
}

@Composable
private fun LiveTripDetails(
    container: AppContainer,
    live: LiveTripsState,
    onArrive: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier,
) {
    val sharing by LiveLocationService.status.collectAsState()
    val scope = rememberCoroutineScope()
    val userId = container.sessionStore.currentUserId()
    val own = live.trips.find { it.userId == userId }
    val sharingHere = sharing.meetupId == live.meetupId
    val destination = live.meetup?.winner

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (destination != null) DestinationCard(destination.name)
        if (sharingHere) SharingStatusCard(sharing.message)

        if (own?.status == "ACTIVE") {
            ActiveTripCard(own, live.now, live.busy, canPause = own.sharing || sharingHere, onArrive, onPause)
        } else if (own?.status == "ARRIVED") {
            ArrivedCard(own.actualSeconds ?: 0)
        }

        if (live.canStartTrip(userId, own) && !sharingHere) {
            StartTripButton(container, live.meetupId, label = if (own == null) "I'm leaving" else "Resume sharing") {
                scope.launch { live.reload() }
            }
        }

        MemberTripsCard(live.trips)
    }
}

@Composable
private fun DestinationCard(name: String) {
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
                    text = "Pan or zoom to find members. Locations older than 90 seconds are hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SharingStatusCard(message: String) {
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

@Composable
private fun ActiveTripCard(
    own: MeetupTrip,
    now: Instant,
    busy: Boolean,
    canPause: Boolean,
    onArrive: () -> Unit,
    onPause: () -> Unit,
) {
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
private fun ArrivedCard(actualSeconds: Long) {
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
private fun MemberTripsCard(trips: List<MeetupTrip>) {
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

@Composable
private fun TripConfirmDialog(
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
