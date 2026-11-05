package com.example.cpen321application.ui.meetup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.data.CalendarAuthResult
import com.example.cpen321application.data.CalendarAuthorizer
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

// Icons

private fun PathBuilder.calendarBody() {
    moveTo(19f, 4f); horizontalLineTo(18f); verticalLineTo(2f); horizontalLineTo(16f); verticalLineTo(4f)
    horizontalLineTo(8f); verticalLineTo(2f); horizontalLineTo(6f); verticalLineTo(4f); horizontalLineTo(5f)
    curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f); lineTo(3f, 20f); curveTo(3f, 21.1f, 3.89f, 22f, 5f, 22f)
    horizontalLineTo(19f); curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f); verticalLineTo(6f)
    curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f); close()
    moveTo(19f, 20f); horizontalLineTo(5f); verticalLineTo(10f); horizontalLineTo(19f); verticalLineTo(20f); close()
}

private val CalendarIcon = icon("Calendar") { calendarBody() }

private val CalendarAddIcon = icon("CalendarAdd") {
    calendarBody()
    moveTo(11f, 12f); horizontalLineTo(13f); verticalLineTo(14f); horizontalLineTo(15f); verticalLineTo(16f)
    horizontalLineTo(13f); verticalLineTo(18f); horizontalLineTo(11f); verticalLineTo(16f); horizontalLineTo(9f)
    verticalLineTo(14f); horizontalLineTo(11f); verticalLineTo(12f); close()
}

// Date formatting

private data class FormattedDateParts(val date: String, val time: String)

private fun parseFormattedDateParts(iso: String): FormattedDateParts = try {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    FormattedDateParts(
        date = zdt.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)),
        time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
    )
} catch (_: Exception) {
    FormattedDateParts(date = iso, time = "Time unavailable")
}

// Date and calendar

@Composable
internal fun MeetupDateTimeCard(
    scheduledAt: String,
    calendarSection: @Composable () -> Unit,
) {
    val dateParts = parseFormattedDateParts(scheduledAt)
    MeetFairCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = TealContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = CalendarIcon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(24.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = dateParts.date, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = TealPrimary)
                    Text(text = dateParts.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            calendarSection()
        }
    }
}

@Composable
private fun rememberCalendarConsentLauncher(
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
): ActivityResultLauncher<IntentSenderRequest> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val intent = activityResult.data
        if (activityResult.resultCode == Activity.RESULT_OK && intent != null) {
            try {
                val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
                val token = result.accessToken
                if (token != null) onToken(token) else onError("Google Calendar did not return an access token.")
            } catch (_: ApiException) {
                onError("Google Calendar authorization failed. Please try again.")
            }
        } else {
            onError("Google Calendar permission request was canceled.")
        }
    }
}

@Composable
internal fun SmallCalendarButton(
    authorizer: CalendarAuthorizer,
    submitting: Boolean,
    added: Boolean,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consentLauncher = rememberCalendarConsentLauncher(onToken, onError)

    if (added) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag(TestTags.CALENDAR_ADDED_TEXT),
        ) {
            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(16.dp))
            Text(text = "In calendar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = StatusGreen)
        }
        return
    }

    Surface(
        onClick = {
            scope.launch {
                when (val auth = authorizer.requestCalendarAuthorization(context)) {
                    is CalendarAuthResult.Success -> onToken(auth.accessToken)
                    is CalendarAuthResult.NeedsConsent -> auth.result.pendingIntent?.let { pendingIntent ->
                        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } ?: onError("Google Calendar permission request could not be started.")
                    is CalendarAuthResult.Failure -> {
                        val cause = auth.error.message ?: "Please grant Calendar permissions."
                        onError("Google Calendar authorization failed: $cause")
                    }
                }
            }
        },
        enabled = !submitting,
        shape = CircleShape,
        color = TealContainer,
        modifier = Modifier.testTag(TestTags.ADD_TO_CALENDAR_BUTTON),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.semantics { contentDescription = "Add to Google Calendar" }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(imageVector = CalendarAddIcon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(16.dp))
            Text(text = if (submitting) "..." else "Add", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = TealPrimary)
        }
    }
}
