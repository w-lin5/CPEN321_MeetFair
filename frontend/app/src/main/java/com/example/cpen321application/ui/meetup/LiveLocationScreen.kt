package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.AppContainer
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.model.MeetupTrip
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.SharedPosition
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.time.Instant

private const val MAP_ZOOM = 12f

// The map gets a little more room than the trip details below it
private const val MAP_WEIGHT = 1.2f

/** Map of everyone on their way to a finalized meetup, plus the user's own trip controls. */
@Composable
fun LiveLocationScreen(container: AppContainer, meetupId: String, onBack: () -> Unit, onDeleted: () -> Unit = onBack) {
    val vm: LiveLocationViewModel = screenViewModel(LiveLocationViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    val appContext = LocalContext.current.applicationContext
    // The foreground service belongs to this device, so the screen (not the ViewModel) stops it
    val stopSharingHere = {
        if (LiveLocationService.status.value.meetupId == meetupId) LiveLocationService.stop(appContext)
    }
    var confirmArrival by remember { mutableStateOf(false) }
    var confirmPause by remember { mutableStateOf(false) }
    LiveLocationEffects(vm, stopSharingHere, onDeleted)

    ScreenColumn("Live location", onBack) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = state.meetup?.title ?: "Loading meetup...",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.meetup?.winner?.let { LiveMapCard(it, state.trips, state.now, Modifier.weight(MAP_WEIGHT)) }
            LiveTripDetails(
                container = container,
                meetupId = meetupId,
                state = state,
                vm = vm,
                onArrive = { confirmArrival = true },
                onPause = { confirmPause = true },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (confirmPause) {
            TripConfirmDialog(
                title = "Pause location sharing?",
                text = "Your location updates will stop, but your commute time will continue to be counted until " +
                    "you tap I arrived. Resuming sharing will not reset the timer.",
                confirmLabel = "Pause sharing",
                onDismiss = { confirmPause = false },
                onConfirm = {
                    stopSharingHere()
                    vm.pauseSharing()
                },
            )
        }
        if (confirmArrival) {
            TripConfirmDialog(
                title = "Have you arrived?",
                text = "This ends your trip and location sharing, and saves the actual travel time. " +
                    "You cannot restart this trip.",
                confirmLabel = "I arrived",
                onDismiss = { confirmArrival = false },
                onConfirm = {
                    stopSharingHere()
                    vm.arrive()
                },
            )
        }
    }
}

// Refreshes only while the screen is visible, and reacts to the ViewModel's one-shot outcomes
@Composable
private fun LiveLocationEffects(vm: LiveLocationViewModel, stopSharingHere: () -> Unit, onDeleted: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val notify by rememberUpdatedState(LocalNotify.current)
    val leave by rememberUpdatedState(onDeleted)
    val stopSharing by rememberUpdatedState(stopSharingHere)

    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.observe() }
    }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            stopSharing()
            if (effect is LiveLocationEffect.Deleted) {
                if (effect.notifyParticipant) notify("This meetup has been deleted by its organizer.")
                leave()
            }
        }
    }
}

@Composable
private fun LiveMapCard(destination: PlaceCandidate, trips: List<MeetupTrip>, now: Instant, modifier: Modifier) {
    val context = LocalContext.current
    val playServicesAvailable = remember(context) {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val destinationPoint = LatLng(destination.lat, destination.lng)

    MeetFairCard(modifier = modifier, containerColor = TealContainer, borderColor = TealContainer) {
        if (playServicesAvailable) {
            val camera = rememberCameraPositionState {
                position = CameraPosition.fromLatLngZoom(destinationPoint, MAP_ZOOM)
            }
            GoogleMap(Modifier.fillMaxWidth(), cameraPositionState = camera) {
                Marker(state = rememberMarkerState(position = destinationPoint), title = destination.name)
                for (trip in trips) {
                    val position = freshSharedPosition(trip, now) ?: continue
                    key(trip.userId) {
                        AvatarMarker(trip.userId, trip.name, trip.photoUrl, LatLng(position.lat, position.lng))
                    }
                }
            }
        } else {
            // Devices without Google Play services cannot show the map; the destination is still named
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

// A member's position is shown only while their trip is active, shared, and recently updated
private fun freshSharedPosition(trip: MeetupTrip, now: Instant): SharedPosition? {
    val position = trip.location ?: return null
    val ageSeconds = secondsBetween(position.updatedAt, now) ?: return null
    return position.takeIf { trip.sharing && trip.isActive && ageSeconds < POSITION_MAX_AGE_SECONDS }
}

@Composable
private fun LiveTripDetails(
    container: AppContainer,
    meetupId: String,
    state: LiveLocationState,
    vm: LiveLocationViewModel,
    onArrive: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier,
) {
    val sharing by LiveLocationService.status.collectAsState()
    val sharingHere = sharing.meetupId == meetupId
    val ownTrip = state.tripOf(vm.userId)

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.meetup?.winner?.let { DestinationCard(it.name) }
        if (sharingHere) SharingStatusCard(sharing.message)

        if (ownTrip?.isActive == true) {
            val canPause = ownTrip.sharing || sharingHere
            ActiveTripCard(ownTrip, state.now, state.busy, canPause, onArrive, onPause)
        } else if (ownTrip?.hasArrived == true) {
            ArrivedCard(ownTrip.actualSeconds ?: 0)
        }

        if (state.canStartTrip(vm.userId) && !sharingHere) {
            val label = if (ownTrip == null) "I'm leaving" else "Resume sharing"
            StartTripButton(container, meetupId, label = label, onStarted = vm::reload)
        }

        MemberTripsCard(state.trips)
    }
}
