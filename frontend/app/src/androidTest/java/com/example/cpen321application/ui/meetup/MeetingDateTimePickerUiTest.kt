package com.example.cpen321application.ui.meetup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MeetingDateTimePickerUiTest {
    @get:Rule val compose = createComposeRule()
    private val initial = LocalDateTime.now().plusDays(1).withHour(14).withMinute(30).withSecond(0).withNano(0)

    @Test fun calendarThenWheelSelectionCommitsTheChosenTime() {
        val result = mutableStateOf<LocalDateTime?>(null)
        compose.setContent {
            MaterialTheme {
                if (result.value == null) {
                    MeetingDateTimePicker(initial, onDismiss = {}, onConfirm = { result.value = it })
                } else Text("Selected")
            }
        }
        compose.onNodeWithTag("meeting_date_next").performClick()
        compose.onNodeWithTag("meeting_hour_wheel").assertIsDisplayed()
        compose.onNodeWithTag("meeting_minute_wheel").assertIsDisplayed()
        compose.onNodeWithTag("meeting_hour_wheel").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithTag("meeting_time_confirm").performClick()
        compose.runOnIdle {
            assertEquals(initial.toLocalDate(), result.value?.toLocalDate())
            assertEquals(30, result.value?.minute)
            assertNotEquals(14, result.value?.hour)
        }
    }

    @Test fun cancellingAtTheTimeStepDoesNotCommitTheDraft() {
        var result: LocalDateTime? = null
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) MeetingDateTimePicker(initial,
                    onDismiss = { visible.value = false }, onConfirm = { result = it })
                else Text("Original time unchanged")
            }
        }
        compose.onNodeWithTag("meeting_date_next").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Original time unchanged").assertIsDisplayed()
        compose.runOnIdle { assertNull(result) }
    }
}
