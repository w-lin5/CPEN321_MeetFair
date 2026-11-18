package com.example.cpen321application

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.location.LiveSharingNotifications
import com.example.cpen321application.notifications.REMINDER_MEETUP_ID
import com.example.cpen321application.ui.MeetFairApp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme

/** Single activity host; all UI is Compose, and navigation lives in [MeetFairApp]. */
class MainActivity : ComponentActivity() {
    // The meetup a tapped notification asks to open; cleared once the app has navigated to it
    private val meetupToOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        meetupToOpen.value = intent.getStringExtra(REMINDER_MEETUP_ID)

        // Dependencies are built once per process in MeetFairApplication so they outlive the activity.
        val container = (application as MeetFairApplication).container
        setContent {
            CPEN321ApplicationTheme {
                MeetFairApp(container, meetupToOpen.value, onReminderOpened = { meetupToOpen.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        meetupToOpen.value = intent.getStringExtra(REMINDER_MEETUP_ID)
    }

    override fun onStart() {
        super.onStart()
        LiveSharingNotifications(this).dismissBackground()
    }

    // Leaving the app while sharing a location shows a reminder that sharing continues in the background
    override fun onStop() {
        super.onStop()
        val sharingMeetupId = LiveLocationService.status.value.meetupId
        if (!isChangingConfigurations && sharingMeetupId != null) {
            LiveSharingNotifications(this).showBackground(sharingMeetupId)
        }
    }
}
