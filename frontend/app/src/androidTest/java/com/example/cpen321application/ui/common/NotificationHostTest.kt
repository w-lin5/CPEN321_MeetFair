package com.example.cpen321application.ui.common

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NotificationHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletionNoticeSurvivesNavigationAndDismissesAutomatically() {
        val page = mutableStateOf("Group details")
        compose.setContent {
            MaterialTheme {
                NotificationHost {
                    val notify = LocalNotify.current
                    Button(onClick = {
                        notify("Group deleted.")
                        page.value = "Your groups"
                    }) { Text(page.value) }
                }
            }
        }
        compose.onNodeWithText("Group details").performClick()
        compose.onNodeWithText("Your groups").assertIsDisplayed()
        compose.onNodeWithText("Group deleted.").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Group deleted.").fetchSemanticsNodes().isEmpty()
        }
        // An identical later notification must appear again, rather than staying consumed forever.
        compose.onNodeWithText("Your groups").performClick()
        compose.onNodeWithText("Group deleted.").assertIsDisplayed()
    }
}
