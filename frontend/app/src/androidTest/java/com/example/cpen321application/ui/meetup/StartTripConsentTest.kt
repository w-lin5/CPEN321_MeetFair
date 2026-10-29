package com.example.cpen321application.ui.meetup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.AppContainer
import com.example.cpen321application.location.LiveLocationService
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class StartTripConsentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancellingConsentDoesNotStartLocationSharingOrNavigate() {
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        var opened = false
        compose.setContent { MaterialTheme { StartTripButton(container, "test-meetup") { opened = true } } }
        compose.onNodeWithText("I am leaving").performClick()
        compose.onNodeWithText("Share your live location?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Share your live location?").assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertFalse(opened)
            assertNull(LiveLocationService.status.value.meetupId)
        }
    }
}
