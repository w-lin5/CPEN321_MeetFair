package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
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
import com.example.cpen321application.testutil.castAndLockVote
import com.example.cpen321application.testutil.createGroup
import com.example.cpen321application.testutil.finalizeAndReturnToMeetup
import com.example.cpen321application.testutil.startVotingFromMeetup
import com.example.cpen321application.testutil.submitParticipation
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.manualStep
import com.example.cpen321application.testutil.requireCreatedInviteCode
import com.example.cpen321application.testutil.retryUntil
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
 * completes participation, voting and finalization, and tests live location sharing through arrival and settlement.
 */
class ShareLiveLocationTest : BaseE2ETest() {
    private var createdInviteCode: String? = null

    // Main success: leave, share, arrive, settle Fair Credit, and inspect the updated history.
    @Test
    fun shareLocation_startTripArriveAndSettleFairCredit() {
        val meetupTitle = openFinalizedMeetup(leadMinutes = 1)

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
        compose.onAllNodesWithTag(TestTags.TRIP_STATUS_TEXT).onFirst().assertIsDisplayed()

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

        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle, substring = true))

        // The organizer settles from the meetup after the scheduled time and all trips have ended.
        compose.waitFor(hasTestTag("settle_fair_credit"), timeoutMs = 180_000)
        compose.onNodeWithTag("settle_fair_credit").performScrollTo().performClick()
        compose.waitFor(hasText("Settle Fair Credit?"))
        compose.onNodeWithText("Settle", useUnmergedTree = true).performClick()
        compose.waitFor(hasText("Fair Credit settled. View updated balances and history in the group's Fair Credit page."))

        // Verify the settled meetup appears in the group's populated Fair Credit history.
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag("group_fair_credit_button"))
        compose.onNodeWithTag("group_fair_credit_button").performScrollTo().performClick()

        val entryMatcher = hasTestTag(TestTags.FAIRNESS_ENTRY_ITEM) and hasText(meetupTitle, substring = true)
        compose.waitFor(entryMatcher)
        compose.onNode(entryMatcher)
            .performScrollTo()
            .assertTextContains(meetupTitle, substring = true)
            .assertTextContains("Fair Credit change:", substring = true)
            .performClick()

        compose.onAllNodes(hasTestTag(TestTags.FAIRNESS_HISTORY_EMPTY)).assertCountEquals(0)
        compose.onNodeWithText("Your trip:", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Group average adjusted burden:", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
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

        startSharingTrip()
        device.waitForLocationPermissionPrompt()
        device.denyLocationPermissionIfRequested()

        compose.waitFor(hasText("Location permission is required to share your trip. No trip was started."))
        compose.onNodeWithTag(TestTags.ERROR_TEXT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Location permission is required to share your trip. No trip was started.")
        compose.onNodeWithTag(TestTags.LOCATION_SHARING_INDICATOR).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().assertIsDisplayed()

        startSharingTripAndGrantPermission()
        compose.onAllNodesWithTag(TestTags.TRIP_STATUS_TEXT).onFirst().assertIsDisplayed()
    }

    // Failures 6a/6b: airplane mode drops realtime and location requests; restore service afterwards.
    @Test
    fun shareLocation_networkLoss() {
        openFinalizedMeetup(leadMinutes = 1)
        startSharingTripAndGrantPermission()

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
        startSharingTripAndGrantPermission()

        val inviteCode = requireCreatedInviteCode(createdInviteCode)
        compose.activity.manualStep("On Device B:\n1. Join the group with invite code $inviteCode.\n2. Open meetup '$title'.\n" +
            "3. Select 'View live locations'.\n4. Verify User A is listed as 'On the way'.\n" +
            "5. Tap Continue here on Device A.")
    }

    private fun openFinalizedMeetup(leadMinutes: Int): String {
        val meetupTitle = createScheduledMeetup(leadMinutes)
        compose.submitParticipation()
        compose.startVotingFromMeetup(timeoutMs = 30_000)
        compose.castAndLockVote(compose.candidatePlaceIds(timeoutMs = 30_000).first())
        compose.finalizeAndReturnToMeetup(meetupTitle)
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

    private fun startSharingTrip() {
        waitForLeavingButton()
        compose.onNodeWithTag(TestTags.LEAVING_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasText("Share your live location?"))
        compose.onNodeWithTag(TestTags.SHARE_TRIP_CONFIRM).performClick()
    }

    private fun startSharingTripAndGrantPermission() {
        startSharingTrip()
        grantLocationPermissionIfRequested()
        openLiveLocationIfOnMeetupScreen()
        compose.waitFor(hasTestTag(TestTags.LOCATION_SHARING_INDICATOR))
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
