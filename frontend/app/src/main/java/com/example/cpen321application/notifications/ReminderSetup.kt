package com.example.cpen321application.notifications

import android.Manifest
import android.app.AlarmManager
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
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

/** Reconcile while signed in; the OS alarm remains active when this composition is gone. */
@Composable
fun ReminderSetup(container: AppContainer) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences("reminder_permission", 0) }
    var explain by remember { mutableStateOf(false) }
    val openAlarmSettings = {
        if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openAlarmSettings()
    }
    LaunchedEffect(lifecycle) {
        if (!prefs.getBoolean("explained", false)) explain = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val groups = container.groupRepository.list()
            if (groups is ApiResult.Success) {
                for (group in groups.data) container.meetupRepository.listForGroup(group.id)
            }
            awaitCancellation()
        }
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false; prefs.edit().putBoolean("explained", true).apply() },
        title = { Text("Meetup reminders") },
        text = { Text("Allow notifications for confirmed meetups you joined. For reminders at the scheduled time, also allow Alarms & reminders in Android settings. Without it, Android may delay reminders.") },
        confirmButton = { TextButton(onClick = {
            explain = false
            prefs.edit().putBoolean("explained", true).apply()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openAlarmSettings()
        }) { Text("Enable reminders") } },
        dismissButton = { TextButton(onClick = {
            explain = false; prefs.edit().putBoolean("explained", true).apply()
        }) { Text("Not now") } },
    )
}
