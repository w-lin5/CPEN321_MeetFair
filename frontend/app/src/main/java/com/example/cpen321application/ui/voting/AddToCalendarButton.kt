package com.example.cpen321application.ui.voting

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.cpen321application.data.CalendarAuthResult
import com.example.cpen321application.data.CalendarAuthorizer
import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.Dimens
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

/**
 * "Add to Google Calendar" for a finalized meetup: gets a calendar.events access token on the device
 * (asking for consent the first time) and hands it to [onToken], which sends it to the backend.
 */
@Composable
fun AddToCalendarButton(
    authorizer: CalendarAuthorizer,
    state: FormState<CalendarEventResult>,
    alreadyAdded: Boolean,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val consentLauncher = rememberLauncherForActivityResult(
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

    if (alreadyAdded || state.result != null) {
        Text(
            "Added to your Google Calendar",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(TestTags.CALENDAR_ADDED_TEXT),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Button(
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
            enabled = !state.submitting,
            modifier = Modifier.testTag(TestTags.ADD_TO_CALENDAR_BUTTON),
        ) { Text(if (state.submitting) "Adding..." else "Add to Google Calendar") }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
    }
}
