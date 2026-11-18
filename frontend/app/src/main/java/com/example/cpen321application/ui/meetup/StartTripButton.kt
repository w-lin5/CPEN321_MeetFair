package com.example.cpen321application.ui.meetup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cpen321application.AppContainer
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.ui.common.TestTags
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

private val LOCATION_PERMISSIONS =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

// Before Android 13 notifications need no runtime permission
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/**
 * "I'm leaving" / "Resume sharing": asks for consent and the permissions it needs (notifications first,
 * so the sharing notice can be shown, then location), and then starts sharing the user's trip.
 */
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
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val start: () -> Unit = {
        if (!busy) {
            scope.launch {
                busy = true
                error = container.tripStarter.start(
                    meetupId,
                    appInForeground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                )
                busy = false
                if (error == null) onStarted()
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LiveLocationService.hasLocationPermission(context)) {
            start()
        } else {
            error = "Location permission is required to share your trip. No trip was started."
        }
    }
    val startWithLocation = {
        if (LiveLocationService.hasLocationPermission(context)) {
            start()
        } else {
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }
    // Sharing works without notifications too, so the answer does not matter here
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startWithLocation()
    }

    StartTripActionView(busy = busy, label = label, error = error, modifier = modifier, onClick = { confirming = true })

    if (confirming) {
        StartTripConfirmationDialog(
            onConfirm = {
                confirming = false
                if (needsNotificationPermission(context)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startWithLocation()
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun StartTripActionView(
    busy: Boolean,
    label: String,
    error: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = modifier) {
        Button(
            onClick = onClick,
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
                .testTag(TestTags.START_TRIP_BUTTON),
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
}

@Composable
private fun StartTripConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share your live location?") },
        text = {
            Text(
                "Members of this group can see your latest location and profile picture during this trip. " +
                    "Sharing continues with a system notification when the app is in the background. " +
                    "Tap I arrived to stop sharing and save your commute duration. Pause sharing stops location " +
                    "updates but keeps your commute timer running until you tap I arrived.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Share and start trip")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
