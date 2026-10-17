package com.example.cpen321application.ui.calendar

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.CalendarAuthResult
import com.example.cpen321application.model.CalendarEvent
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

@Composable
fun GoogleCalendarScreen(
    container: AppContainer,
    onBack: () -> Unit,
    viewModel: GoogleCalendarViewModel = screenViewModel(GoogleCalendarViewModel.Factory(container.googleCalendarRepository)),
) {
    val eventsState by viewModel.eventsState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val exportStates by viewModel.exportStates.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExportEvent by remember { mutableStateOf<CalendarEvent?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val intent = activityResult.data
            if (intent != null) {
                try {
                    val authResult = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
                    val token = authResult.accessToken
                    val exportEvent = pendingExportEvent
                    pendingExportEvent = null
                    if (exportEvent != null) viewModel.exportEvent(exportEvent, token)
                    else viewModel.syncCalendar(accessToken = token)
                } catch (_: Exception) {
                    pendingExportEvent = null
                    viewModel.syncCalendar()
                }
            } else {
                pendingExportEvent = null
                viewModel.syncCalendar()
            }
        } else {
            pendingExportEvent = null
            viewModel.setSyncError("Google Calendar permission request was canceled.")
        }
    }

    ScreenColumn(title = "Calendar", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            when (val auth = container.googleSignInHelper.requestCalendarAuthorization(context)) {
                                is CalendarAuthResult.Success -> {
                                    viewModel.syncCalendar(accessToken = auth.accessToken)
                                }
                                is CalendarAuthResult.NeedsConsent -> {
                                    val pendingIntent = auth.result.pendingIntent
                                    if (pendingIntent != null) {
                                        consentLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        )
                                    } else {
                                        viewModel.syncCalendar()
                                    }
                                }
                                is CalendarAuthResult.Failure -> {
                                    val cause = auth.error.message?.ifBlank { null } ?: "Google Calendar permission or account setup is required."
                                    viewModel.setSyncError("Google Calendar authorization failed: $cause")
                                }
                            }
                        }
                    },
                    enabled = !syncState.submitting,
                    modifier = Modifier.testTag(TestTags.GOOGLE_CALENDAR_SYNC_BUTTON)
                ) {
                    if (syncState.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Text("Syncing...")
                    } else {
                        Text("Sync Calendar")
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.addSampleEvent() },
                    enabled = !syncState.submitting
                ) {
                    Text("+ Create Test Event")
                }
            }

            if (syncState.error != null) {
                Text(
                    text = syncState.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.ERROR_TEXT)
                )
            }

            if (syncState.result != null) {
                Text(
                    text = syncState.result ?: "Calendar synced successfully",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.GOOGLE_CALENDAR_SYNC_SUCCESS_TEXT)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Calendar Events", style = MaterialTheme.typography.titleMedium)

            UiStateContent(
                state = eventsState,
                onRetry = { viewModel.loadEvents() },
                emptyMessage = "No calendar events found. Tap 'Sync Calendar' or '+ Create Test Event' above."
            ) { events ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.GOOGLE_CALENDAR_EVENTS_LIST),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(events, key = { it.id.ifEmpty { it.hashCode().toString() } }) { event ->
                        CalendarEventCard(
                            event = event,
                            exportState = exportStates[event.id],
                            onExport = {
                                scope.launch {
                                    when (val auth = container.googleSignInHelper.requestCalendarAuthorization(context)) {
                                        is CalendarAuthResult.Success -> viewModel.exportEvent(event, auth.accessToken)
                                        is CalendarAuthResult.NeedsConsent -> auth.result.pendingIntent?.let { pendingIntent ->
                                            pendingExportEvent = event
                                            consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                                        } ?: viewModel.setSyncError("Google Calendar permission request could not be started.")
                                        is CalendarAuthResult.Failure -> viewModel.setSyncError(
                                            "Google Calendar authorization failed: ${auth.error.message ?: "Please grant Calendar permissions."}"
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarEventCard(
    event: CalendarEvent,
    exportState: com.example.cpen321application.ui.common.FormState<String>? = null,
    onExport: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.GOOGLE_CALENDAR_EVENT_ITEM),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!event.startTime.isNullOrBlank() || !event.endTime.isNullOrBlank()) {
                val timeStr = buildString {
                    if (!event.startTime.isNullOrBlank()) append(event.startTime)
                    if (!event.endTime.isNullOrBlank()) {
                        if (isNotEmpty()) append(" - ")
                        append(event.endTime)
                    }
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (!event.location.isNullOrBlank()) {
                Text(
                    text = "Location: ${event.location}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!event.description.isNullOrBlank()) {
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!event.isSyncedToExternalCalendar && onExport != null) {
                Button(onClick = onExport, enabled = exportState?.submitting != true) {
                    Text(if (exportState?.submitting == true) "Adding..." else "Add to Google Calendar")
                }
                exportState?.result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                exportState?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                Text(
                    text = "Synced to Google Calendar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
