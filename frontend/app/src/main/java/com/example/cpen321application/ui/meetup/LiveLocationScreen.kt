package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.AppContainer
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupTrip
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.ScreenColumn
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(meetup?.title ?: "Loading meetup...", style = MaterialTheme.typography.titleLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val destination = meetup?.winner
            if (BuildConfig.MAPS_CONFIGURED && destination != null) {
                val camera = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(LatLng(destination.lat, destination.lng), 12f)
                }
                GoogleMap(Modifier.fillMaxWidth().height(360.dp), cameraPositionState = camera) {
                    Marker(state = rememberMarkerState(position = LatLng(destination.lat, destination.lng)), title = destination.name)
                    for (trip in trips) {
                        val point = trip.location
                        val recent = point != null && runCatching { now.epochSecond - Instant.parse(point.updatedAt).epochSecond < 90 }.getOrDefault(false)
                        if (trip.sharing && trip.status == "ACTIVE" && point != null && recent) key(trip.userId) {
                            AvatarMarker(trip.userId, trip.name, trip.photoUrl, LatLng(point.lat, point.lng))
                        }
                    }
                }
                Text("Destination: ${destination.name}. Pan or zoom to find members. Locations older than 90 seconds are hidden.")
            } else if (!BuildConfig.MAPS_CONFIGURED) Text("Configure the Android Maps key to display the live map.")
            if (sharing.meetupId == meetupId) Text(sharing.message)
            else if (own?.status == "ACTIVE") Text("This device is not sharing. Resume to share your location; the original trip start time is preserved.")
            if (own?.status == "ARRIVED") Text("You arrived. Actual commute: ${own.actualSeconds ?: 0} seconds.")
            if (own?.status == "ACTIVE") {
                val elapsed = runCatching { maxOf(0, now.epochSecond - Instant.parse(own.startedAt).epochSecond) }.getOrDefault(0)
                Text("Time since departure: ${elapsed / 60} min ${elapsed % 60} sec")
                Button(onClick = { confirmArrival = true }, enabled = !busy) { Text("I arrived") }
                TextButton(onClick = {
                    LiveLocationService.stop(context)
                    scope.launch {
                        busy = true
                        when (val response = container.tripRepository.stop(meetupId)) {
                            is ApiResult.Failure -> error = response.error.message
                            is ApiResult.Success -> reload()
                        }
                        busy = false
                    }
                }, enabled = !busy) { Text("Stop sharing") }
            }
            val joined = meetup?.participants?.any { it.userId == userId } == true
            val due = meetup?.let { it.status == com.example.cpen321application.model.MeetupStatus.FINALIZED &&
                runCatching { !Instant.parse(it.scheduledAt).isAfter(now) }.getOrDefault(false) } == true
            if (joined && due && own?.status != "ARRIVED" && sharing.meetupId != meetupId) {
                StartTripButton(container, meetupId, if (own == null) "I am leaving" else "Resume sharing") {
                    scope.launch { reload() }
                }
            }
            for (trip in trips) Text("${trip.name}: " + when {
                trip.status == "ARRIVED" -> "Arrived (${trip.actualSeconds ?: 0} seconds)"
                trip.sharing -> "On the way"
                else -> "On the way · sharing paused"
            })
            if (trips.isEmpty()) Text("Nobody has started a trip yet.")
            Text("Tap I arrived when you reach the destination. This stops location sharing and records your travel time.")
        }
        if (confirmArrival) AlertDialog(onDismissRequest = { confirmArrival = false },
            title = { Text("Have you arrived?") }, text = { Text("This ends your trip and location sharing, and saves the actual travel time. You cannot restart this trip.") },
            confirmButton = { TextButton(onClick = {
                confirmArrival = false
                LiveLocationService.stop(context)
                scope.launch {
                    busy = true
                    when (val response = container.tripRepository.arrive(meetupId)) {
                        is ApiResult.Success -> reload()
                        is ApiResult.Failure -> error = "Arrival was not saved: ${response.error.message}. Tap I arrived to retry."
                    }
                    busy = false
                }
            }) { Text("I arrived") } },
            dismissButton = { TextButton(onClick = { confirmArrival = false }) { Text("Cancel") } })
    }
}
