package com.example.cpen321application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.cpen321application.ui.MeetFairApp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme

/** Single activity host; all UI is Compose, and navigation lives in [MeetFairApp]. */
class MainActivity : ComponentActivity() {
    private val reminderMeetup = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        com.example.cpen321application.location.LiveSharingNotifications(this).dismissBackground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            com.example.cpen321application.location.LiveLocationService.status.value.meetupId?.let {
                com.example.cpen321application.location.LiveSharingNotifications(this).showBackground(it)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderMeetup.value = intent.getStringExtra(com.example.cpen321application.notifications.REMINDER_MEETUP_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        reminderMeetup.value = intent.getStringExtra(com.example.cpen321application.notifications.REMINDER_MEETUP_ID)
        
        // Dependencies are built once per process in MeetFairApplication so they outlive the activity.
        val container = (application as MeetFairApplication).container
        setContent {
            CPEN321ApplicationTheme {
                MeetFairApp(container, reminderMeetup.value) { reminderMeetup.value = null }
            }
        }
    }
}
