package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.example.cpen321application.testutil.BaseE2ETest
import com.example.cpen321application.testutil.NETWORK_TIMEOUT_MS
import com.example.cpen321application.testutil.assumeMultiUserRun
import com.example.cpen321application.testutil.dismissSnackbarNotices
import com.example.cpen321application.testutil.candidatePlaceIds
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.manualStep
import com.example.cpen321application.testutil.requireCreatedInviteCode
import com.example.cpen321application.testutil.textOf
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.ui.common.TestTags
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * End-to-end tests for Use Case 5 "Share Live Location" against the back end.
 * Each test starts signed in on the group list, creates its own group and meetup with a scheduled time,
 * completes participation, voting and finalization, and tests live location sharing.
 */
class ShareLiveLocationTest : BaseE2ETest() {
    private var createdInviteCode: String? = null

    // Main success: leave, share and arrive
    @Test
    fun shareLocation_startTripArrive() {
        openFinalizedMeetup(leadMinutes = 1)

        // 1. Wait until scheduled time arrives and "I am leaving" is offered.
        waitForLeavingButton()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()

        // 2. Explanation dialog is shown; confirm "Share and start trip".
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithText("Share your live location?").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()

        // 3-4. Grant location permission in system dialog if requested.
        grantLocationPermissionIfRequested()

        // 5. System records commute start time and begins receiving location updates.
        openLiveLocationIfOnMeetupScreen()

        compose.waitFor(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))
        compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.TRIP_STATUS_TEXT).assertIsDisplayed()

        // 8. Select "I arrived"; system records arrival time, calculates duration, and stops sharing.
        compose.waitFor(hasTestTag(TestTags.ARRIVED_BUTTON))
        compose.onNodeWithTag(TestTags.ARRIVED_BUTTON).performScrollTo().performClick()

        val confirmArrivalPrompt = hasText("Have you arrived?")
        if (compose.exists(confirmArrivalPrompt)) {
            compose.onNode(hasText("I arrived") and !hasTestTag(TestTags.ARRIVED_BUTTON)).performClick()
        }

        compose.waitFor(hasTestTag(TestTags.TRIP_STATUS_TEXT) and hasText("arrived", substring = true, ignoreCase = true))
        compose.onNodeWithTag(TestTags.TRIP_STATUS_TEXT).assertTextContains("arrived", substring = true, ignoreCase = true)
        compose.onNodeWithTag(TestTags.COMMUTE_DURATION_TEXT).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertDoesNotExist()
    }

    // Failure 1a: before scheduled time
    @Test
    fun shareLocation_beforeMeetupTime() {
        openFinalizedMeetup(leadMinutes = 10)

        // Scheduled time has not arrived, so "I am leaving" is not offered.
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).assertDoesNotExist()
    }

    // Failure 4a: deny permission, verify no trip starts, then grant and retry successfully.
    @Test
    fun shareLocation_deniedPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Start from a known permission state so Android shows the location prompt in this test.
        device.executeShellCommand("pm revoke ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        device.executeShellCommand("pm revoke ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        // Avoid the Android 13+ notification prompt obscuring the location permission prompt.
        runCatching { device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS") }
        openFinalizedMeetup(leadMinutes = 1)

        waitForLeavingButton()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithText("Share your live location?").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()
        waitForLocationPermissionPrompt(device)
        denyLocationPermissionIfRequested(device)

        compose.waitFor(hasText("Location permission is required to share your trip. No trip was started."))
        compose.onNodeWithTag(TestTags.ERROR_TEXT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Location permission is required to share your trip. No trip was started.")
        compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()
        grantLocationPermissionIfRequested()
        openLiveLocationIfOnMeetupScreen()
        compose.waitFor(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))
        compose.onNodeWithTag(TestTags.TRIP_STATUS_TEXT).assertIsDisplayed()
    }

    // Failures 6a/6b: airplane mode drops realtime and location requests; restore service afterwards.
    @Test
    fun shareLocation_networkLoss() {
        openFinalizedMeetup(leadMinutes = 1)
        waitForLeavingButton()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()
        grantLocationPermissionIfRequested()
        openLiveLocationIfOnMeetupScreen()
        compose.waitFor(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        try {
            compose.waitFor(hasText("Live connection unavailable. Refreshing periodically."), timeoutMs = 60_000)
            compose.onNodeWithText("Live connection unavailable. Refreshing periodically.").assertIsDisplayed()
            compose.waitFor(hasText("Location update failed. Retrying..."), timeoutMs = 60_000)
            compose.onNodeWithText("Location update failed. Retrying...").assertIsDisplayed()
            compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertIsDisplayed()
        } finally {
            restoreNetwork()
        }

        compose.waitFor(
            hasText("Sharing your live location") or hasText("Waiting for a fresh location fix..."),
            timeoutMs = 60_000,
        )
        compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertIsDisplayed()
    }

    // Multi-user: User B opens the live map and verifies User A is listed as on the way.
    @Test
    fun shareLocation_anotherMemberSeesTripStatus() {
        assumeMultiUserRun()
        val title = openFinalizedMeetup(leadMinutes = 1)
        waitForLeavingButton()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()
        grantLocationPermissionIfRequested()
        openLiveLocationIfOnMeetupScreen()
        compose.waitFor(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))

        val inviteCode = requireCreatedInviteCode(createdInviteCode)
        compose.activity.manualStep("On Device B, join with invite code $inviteCode, open meetup '$title', and select 'View live locations'. Verify User A is listed as 'On the way'. Then tap Continue here on Device A.")
    }

    private fun openFinalizedMeetup(leadMinutes: Int): String {
        val meetupTitle = createScheduledMeetup(leadMinutes)
        submitParticipationDetails()
        startVoting()
        finalizeFirstCandidate()
        return navigateBackToMeetup(meetupTitle)
    }

    private fun createScheduledMeetup(leadMinutes: Int): String {
        val groupName = uniqueName("E2E Location Group")
        createdGroupNames += groupName
        val meetupTitle = uniqueName("E2E Location Meetup")

        // Create group
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        createdInviteCode = compose.textOf(TestTags.INVITE_CODE_TEXT)

        // Create meetup with scheduled time
        compose.onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
        scheduleMeetupTime(leadMinutes)
        compose.onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
        return meetupTitle
    }

    private fun submitParticipationDetails() {
        compose.waitFor(hasTestTag(TestTags.PARTICIPATION_BUTTON))
        compose.onNodeWithTag(TestTags.PARTICIPATION_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.LOCATION_PICKER_BUTTON))
        compose.onNodeWithTag(TestTags.LOCATION_PICKER_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.LOCATION_CONFIRM))
        compose.onNodeWithTag(TestTags.LOCATION_CONFIRM).performClick()
        compose.waitFor(hasTestTag(TestTags.PARTICIPATION_SUBMIT) and isEnabled())
        compose.onNodeWithTag(TestTags.PARTICIPATION_SUBMIT).performClick()
    }

    private fun startVoting() {
        compose.waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
        compose.onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT), timeoutMs = 30_000)
        compose.dismissSnackbarNotices()
    }

    private fun finalizeFirstCandidate() {
        val placeId = compose.candidatePlaceIds(timeoutMs = 30_000).first()
        compose.onNodeWithTag(TestTags.voteButton(placeId)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.VOTE_LOCKED_TEXT))

        // Finalize meetup
        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        val confirmPrompt = hasTestTag(TestTags.CONFIRM_FINALIZE_BUTTON)
        if (compose.exists(confirmPrompt)) {
            compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
        }
        val winnerMatcher = tagStartingWith("pick_winner_button_")
        if (compose.exists(winnerMatcher)) {
            compose.onNodeWithTag(TestTags.pickWinnerButton(placeId)).performScrollTo().performClick()
        }
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))
    }

    private fun navigateBackToMeetup(meetupTitle: String): String {
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle), timeoutMs = 30_000)
        compose.dismissSnackbarNotices()
        return meetupTitle
    }

    private fun scheduleMeetupTime(leadMinutes: Int) {
        val target = LocalDateTime.now().plusMinutes(leadMinutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
        compose.onNodeWithTag(TestTags.MEETUP_TIME_INPUT).performClick()
        compose.waitFor(hasTestTag("meeting_date_next"))
        compose.onNodeWithTag("meeting_date_next").performClick()
        compose.waitFor(hasTestTag("meeting_hour_wheel"))

        setTimeWheelValue("meeting_hour_wheel", target.hour)
        setTimeWheelValue("meeting_minute_wheel", target.minute)

        compose.onNodeWithTag("meeting_time_confirm").performClick()
        val expectedTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(target)
        compose.onNodeWithTag(TestTags.MEETUP_TIME_INPUT).assertTextContains(expectedTime)
    }

    private fun setTimeWheelValue(tag: String, targetValue: Int) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(targetValue.toFloat())
        }
        compose.waitForIdle()
    }

    private fun waitForLeavingButton(timeoutMs: Long = 180_000) {
        val matcher = hasTestTag(TestTags.LEAVING_BUTTON)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (compose.exists(matcher)) {
                compose.dismissSnackbarNotices()
                return
            }
            runCatching {
                compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
            }
            if (compose.exists(matcher)) {
                compose.dismissSnackbarNotices()
                return
            }
            Thread.sleep(3000)
        }
        compose.waitFor(matcher, timeoutMs = 1000)
        compose.dismissSnackbarNotices()
    }

    private fun openLiveLocationIfOnMeetupScreen() {
        compose.waitUntil(NETWORK_TIMEOUT_MS) {
            if (compose.exists(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))) {
                true
            } else if (compose.exists(hasTestTag(TestTags.VIEW_LIVE_LOCATION_BUTTON))) {
                runCatching {
                    compose.onNodeWithTag(TestTags.VIEW_LIVE_LOCATION_BUTTON).performScrollTo().performClick()
                }
                compose.exists(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))
            } else {
                false
            }
        }
    }

    private fun grantLocationPermissionIfRequested() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val options = listOf("While using the app", "Only this time", "Allow", "ALLOW", "While Using the App")
        repeat(5) {
            var clicked = false
            for (option in options) {
                val obj = device.findObject(UiSelector().text(option))
                if (obj.exists()) {
                    runCatching { obj.click() }
                    clicked = true
                    Thread.sleep(1000)
                    break
                }
            }
            if (!clicked) {
                Thread.sleep(500)
            }
        }
    }

    private fun waitForLocationPermissionPrompt(device: UiDevice): Boolean {
        val permissionChoices = listOf("While using the app", "Only this time", "Allow", "ALLOW", "While Using the App")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (permissionChoices.any { device.findObject(UiSelector().text(it)).exists() }) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun denyLocationPermissionIfRequested(device: UiDevice): Boolean {
        val options = listOf("Don't allow", "Deny", "DENY", "Don’t allow")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            for (option in options) {
                val obj = device.findObject(UiSelector().text(option))
                if (obj.exists() && runCatching { obj.click() }.isSuccess) return true
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun denyLocationPermissionIfRequested() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val options = listOf("Don't allow", "Deny", "DENY", "Don’t allow")
        repeat(5) {
            var clicked = false
            for (option in options) {
                val obj = device.findObject(UiSelector().text(option))
                if (obj.exists()) {
                    runCatching { obj.click() }
                    clicked = true
                    Thread.sleep(1000)
                    break
                }
            }
            if (!clicked) {
                Thread.sleep(500)
            }
        }
    }

    private fun tagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
