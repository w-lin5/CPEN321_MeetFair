package com.example.cpen321application.ui.meetup

import android.Manifest
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.ui.theme.RosePrimary
import kotlinx.coroutines.launch

private val ArrowRightIcon: ImageVector
    get() = ImageVector.Builder(
        name = "ArrowRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White)) {
        moveTo(12f, 4f)
        lineTo(10.59f, 5.41f)
        lineTo(16.17f, 11f)
        horizontalLineTo(4f)
        verticalLineTo(13f)
        horizontalLineTo(16.17f)
        lineTo(10.59f, 18.59f)
        lineTo(12f, 20f)
        lineTo(20f, 12f)
        lineTo(12f, 4f)
        close()
    }.build()

@Composable
internal fun StartTripButton(
    container: AppContainer,
    meetupId: String,
    modifier: Modifier = Modifier,
    label: String = "I'm leaving",
    onStarted: () -> Unit,
) {
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
    val requestLocation = {
        if (LiveLocationService.hasLocationPermission(context)) start()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestLocation()
    }
    Column(modifier = modifier) {
        Button(
            onClick = { confirm = true },
            enabled = !busy,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RosePrimary,
                contentColor = Color.White,
                disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("start_trip_button"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ArrowRightIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (busy) "Starting..." else label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Share your live location?") },
        text = { Text("Members of this group can see your latest location and profile picture during this trip. Sharing continues with a system notification when the app is in the background. Tap I arrived to stop sharing and save your commute duration. Pause sharing stops location updates but keeps your commute timer running until you tap I arrived.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestLocation()
        }) { Text("Share and start trip") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
