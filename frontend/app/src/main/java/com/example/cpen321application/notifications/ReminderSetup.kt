package com.example.cpen321application.notifications

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val PREFS = "reminder_permission"
private const val KEY_EXPLAINED = "explained"

private const val EXPLANATION =
    "Allow notifications for confirmed meetups you joined. For reminders at the scheduled time, also allow " +
        "Alarms & reminders in Android settings. Without it, Android may delay reminders."

/**
 * Loads every meetup of the user's groups once; MeetupRepository sets or cancels each meetup's
 * reminder as it passes through, so reminders also exist for meetups whose page was never opened.
 */
private suspend fun refreshReminders(groups: GroupRepository, meetups: MeetupRepository) {
    val listed = groups.list()
    if (listed !is ApiResult.Success) return
    coroutineScope {
        listed.data.map { group -> async { meetups.listForGroup(group.id) } }.awaitAll()
    }
}

private fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

// Android 12+ only delivers reminders on time if the user allows "Alarms & reminders" for the app
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    if (context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
    )
}

/**
 * Keeps meetup reminders in step with the server whenever the signed-in app comes to the foreground,
 * and explains the permissions they need the first time. The alarms themselves live in the OS, so
 * they still fire when the app is closed.
 */
@Composable
fun ReminderSetup(container: AppContainer) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var explaining by remember { mutableStateOf(!prefs.getBoolean(KEY_EXPLAINED, false)) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openExactAlarmSettings(context)
        }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            refreshReminders(container.groupRepository, container.meetupRepository)
        }
    }

    if (explaining) {
        val dismiss = {
            explaining = false
            prefs.edit().putBoolean(KEY_EXPLAINED, true).apply()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Meetup reminders") },
            text = { Text(EXPLANATION) },
            confirmButton = {
                TextButton(onClick = {
                    dismiss()
                    if (canPostNotifications(context)) {
                        openExactAlarmSettings(context)
                    } else {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Enable reminders") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } },
        )
    }
}
