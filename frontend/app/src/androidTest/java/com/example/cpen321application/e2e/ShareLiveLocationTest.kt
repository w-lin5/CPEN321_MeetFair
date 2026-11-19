package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.SemanticsMatcher
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
import com.example.cpen321application.testutil.BaseE2ETest
import com.example.cpen321application.testutil.NETWORK_TIMEOUT_MS
import com.example.cpen321application.testutil.assumeMultiUserRun
import com.example.cpen321application.testutil.denyLocationPermissionIfRequested
import com.example.cpen321application.testutil.grantLocationPermissionIfRequested
import com.example.cpen321application.testutil.dismissSnackbarNotices
import com.example.cpen321application.testutil.candidatePlaceIds
import com.example.cpen321application.testutil.createGroup
import com.example.cpen321application.testutil.navigateBackToMeetup
import com.example.cpen321application.testutil.submitParticipation
import com.example.cpen321application.testutil.tagStartingWith
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.manualStep
import com.example.cpen321application.testutil.requireCreatedInviteCode
import com.example.cpen321application.testutil.retryUntil
import com.example.cpen321application.testutil.textOf
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.testutil.waitForLocationPermissionPrompt
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
        device.waitForLocationPermissionPrompt()
        device.denyLocationPermissionIfRequested()

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
        compose.submitParticipation()
        startVoting()
        finalizeFirstCandidate()
        compose.navigateBackToMeetup(meetupTitle)
        return meetupTitle
    }

    private fun createScheduledMeetup(leadMinutes: Int): String {
        val group = compose.createGroup(createdGroupNames, "E2E Location Group")
        val meetupTitle = uniqueName("E2E Location Meetup")
        createdInviteCode = group.inviteCode

        // Create meetup with scheduled time
        compose.onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
        scheduleMeetupTime(leadMinutes)
        compose.onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
        return meetupTitle
    }

    private fun startVoting() {
        compose.waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
        compose.onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT), timeoutMs = 30_000)
        compose.dismissSnackbarNotices()
    }

    private fun finalizeFirstCandidate() {
        val placeId = compose.candidatePlaceIds(timeoutMs = 30_000).first()
        castAndLockVote(placeId)
        finalizeWithWinner(placeId)
    }

    private fun castAndLockVote(placeId: String) {
        compose.onNodeWithTag(TestTags.voteButton(placeId)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.VOTE_LOCKED_TEXT))
    }

    private fun finalizeWithWinner(placeId: String) {
        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        confirmFinalizationIfPrompted()
        chooseWinnerIfPrompted(placeId)
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))
    }

    private fun confirmFinalizationIfPrompted() {
        if (compose.exists(hasTestTag(TestTags.CONFIRM_FINALIZE_BUTTON))) {
            compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
        }
    }

    private fun chooseWinnerIfPrompted(placeId: String) {
        if (compose.exists(tagStartingWith("pick_winner_button_"))) {
            compose.onNodeWithTag(TestTags.pickWinnerButton(placeId)).performScrollTo().performClick()
        }
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
        val available = retryUntil(timeoutMs, intervalMs = 3_000) {
            compose.exists(matcher) || refreshMeetupAndCheckLeavingButton(matcher)
        }
        compose.dismissSnackbarNotices()
        if (!available) compose.waitFor(matcher, timeoutMs = 1_000)
    }

    private fun refreshMeetupAndCheckLeavingButton(matcher: SemanticsMatcher): Boolean {
        runCatching {
            compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
        }
        return compose.exists(matcher)
    }

    private fun openLiveLocationIfOnMeetupScreen() {
        compose.waitUntil(NETWORK_TIMEOUT_MS) { liveLocationIsOpenOrCanBeOpened() }
    }

    private fun liveLocationIsOpenOrCanBeOpened(): Boolean {
        if (compose.exists(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))) return true
        if (!compose.exists(hasTestTag(TestTags.VIEW_LIVE_LOCATION_BUTTON))) return false

        runCatching {
            compose.onNodeWithTag(TestTags.VIEW_LIVE_LOCATION_BUTTON).performScrollTo().performClick()
        }
        return compose.exists(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))
    }

}
