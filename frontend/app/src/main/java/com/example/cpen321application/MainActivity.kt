package com.example.cpen321application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.cpen321application.ui.MeetFairApp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme

/** Single activity host; all UI is Compose, and navigation lives in [MeetFairApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Dependencies are built once per process in MeetFairApplication so they outlive the activity.
        val container = (application as MeetFairApplication).container
        setContent {
            CPEN321ApplicationTheme {
                MeetFairApp(container)
            }
        }
    }
}
