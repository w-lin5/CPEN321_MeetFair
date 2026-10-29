package com.example.cpen321application.ui.meetup

import android.Manifest
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.location.LiveLocationService
import kotlinx.coroutines.launch

@Composable
internal fun StartTripButton(container: AppContainer, meetupId: String, label: String = "I am leaving", onStarted: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val start = {
        if (!busy) scope.launch {
            busy = true
            error = null
            if (LiveLocationService.status.value.meetupId?.let { it != meetupId } == true) {
                error = "Stop sharing your current trip before starting another one."
            } else if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
                error = "Turn on Location in Android settings, then try again."
            } else when (val result = container.tripRepository.start(meetupId)) {
                is ApiResult.Failure -> error = result.error.message
                is ApiResult.Success -> {
                    try {
                        check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        LiveLocationService.start(context, meetupId)
                        onStarted()
                    } catch (_: IllegalStateException) {
                        container.tripRepository.stop(meetupId)
                        error = "Keep the app open and try resuming location sharing."
                    } catch (_: SecurityException) {
                        container.tripRepository.stop(meetupId)
                        error = "Allow location access while using the app, then try again."
                    }
                }
            }
            busy = false
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (LiveLocationService.hasLocationPermission(context)) start()
        else error = "Location permission is required to share your trip. No trip was started."
    }
    Button(onClick = { confirm = true }, enabled = !busy) { Text(if (busy) "Starting..." else label) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Share your live location?") },
        text = { Text("Members of this group can see your latest location and profile picture during this trip. Sharing continues with a system notification when the app is in the background. Tap I arrived to stop sharing and save your commute duration, or Stop sharing to pause location updates.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (LiveLocationService.hasLocationPermission(context)) start()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Share and start trip") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
