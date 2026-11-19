package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.CalendarAuthResult
import com.example.cpen321application.testutil.assumeMultiUserRun
import com.example.cpen321application.testutil.candidatePlaceIds
import com.example.cpen321application.testutil.castAndLockVote
import com.example.cpen321application.testutil.createGroupAndMeetup
import com.example.cpen321application.testutil.createGroup
import com.example.cpen321application.testutil.finalizeAndReturnToMeetup
import com.example.cpen321application.testutil.nodeText
import com.example.cpen321application.testutil.tagStartingWith
import com.example.cpen321application.testutil.BaseE2ETest
import com.example.cpen321application.testutil.NETWORK_TIMEOUT_MS
import com.example.cpen321application.testutil.dismissSnackbarNotices
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.manualStep
import com.example.cpen321application.testutil.requireCreatedInviteCode
import com.example.cpen321application.testutil.retryUntil
import com.example.cpen321application.testutil.setUpTwoMemberVoting
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.testutil.startVotingFromMeetup
import com.example.cpen321application.testutil.submitParticipation
import com.example.cpen321application.ui.common.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end tests for Feature 4 "Voting and Fairness", focusing on finalizing meetups and fairness against
 * the back end. Voting candidates require Google Places/Routes data. Each test starts signed in on the group 
 * list and creates the meetup it needs. Multi-member finalization cases use the shared manual Device B flow.
 */
class FinalizeMeetupTest : BaseE2ETest() {
    private var createdInviteCode: String? = null
    private var createdGroupName: String? = null

    // Use Case 5 (View Fairness History): the profile lists the user's groups.
    @Test
    fun fairness_profileListsGroups() {
        val groupName = createFairnessTestGroup()

        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.PROFILE_BUTTON))
        compose.onNodeWithTag(TestTags.PROFILE_BUTTON).performClick()
        compose.waitFor(hasText(groupName, substring = true))
        compose.onNodeWithText(groupName, substring = true).assertIsDisplayed()
    }

    // Use Case 5 (View Fairness History): show balances and the user's history.
    @Test
    fun fairness_groupFairCredits() {
        createFairnessTestGroup()

        compose.onNodeWithTag("group_fair_credit_button").performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.FAIR_CREDIT_TEXT))
        compose.onNodeWithTag(TestTags.FAIR_CREDIT_TEXT).assertIsDisplayed()
        compose.onNodeWithText("Group balances").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.FAIRNESS_MEMBER_ITEM).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.FAIRNESS_HISTORY_EMPTY).performScrollTo().assertIsDisplayed()
    }

    // Use Case 3 (Finalize Meetup): a locked vote determines the winner.
    @Test
    fun finalize_finalizeMeetup() {
        val title = openPreparedVotingScreen()
        dismissVotingStartedNotice()

        val winnerId = candidatePlaceIds().first()
        val winnerText = nodeText(compose.onNodeWithTag("candidate_map_$winnerId").fetchSemanticsNode())
        org.junit.Assert.assertTrue("Candidate should have a visible name", winnerText.isNotBlank())

        compose.castAndLockVote(winnerId)
        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        assertFinalizedOnVotingScreen(winnerId, winnerText)
        assertFinalizedOnMeetupScreen(title, winnerText)
    }

    // Use Case 3 (Finalize Meetup), failure 1a: keep voting returns to the open poll.
    @Test
    fun finalize_earlyPromptKeepVotingLeavesPollOpen() {
        openPreparedVotingScreen()
        dismissVotingStartedNotice()

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        assertEarlyFinalizePrompt()
        compose.onNodeWithText("Keep voting").performClick()
        compose.onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).assertIsDisplayed()
        compose.onAllNodes(hasText("Finalize early?")).assertCountEquals(0)
    }

    // Use Case 3 (Finalize Meetup), failure 2a: the organizer picks with no locked votes.
    @Test
    fun finalize_earlyPromptFinalizeAnyway() {
        val title = openPreparedVotingScreen()
        dismissVotingStartedNotice()

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        assertEarlyFinalizePrompt()
        compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()

        compose.waitFor(
            hasTestTag(TestTags.ERROR_TEXT) and
                hasText("Cannot finalize with no locked votes; pick a winner"),
        )
        compose.onNodeWithTag(TestTags.ERROR_TEXT)
            .performScrollTo()
            .assertTextContains("Cannot finalize with no locked votes; pick a winner")
        val candidates = manualPickCandidates()
        val winnerId = candidates.keys.first()
        val winnerName = candidates.getValue(winnerId)
        compose.onNodeWithTag(TestTags.pickWinnerButton(winnerId)).performScrollTo().performClick()

        assertFinalizedOnVotingScreen(winnerId, winnerName)
        assertFinalizedOnMeetupScreen(title, winnerName)
    }

    // Multi-user: Use Case 3, failure 1a; User B's unlocked vote is excluded.
    @Test
    fun finalize_earlyCountsOnlyLockedVotes() {
        assumeMultiUserRun()
        val title = createGroupAndMeetup()
        val inviteCode = requireCreatedInviteCode(createdInviteCode)
        compose.setUpTwoMemberVoting(title, inviteCode)

        val candidates = candidatePlaceIds()
        val lockedWinnerId = candidates[0]
        val unlockedVoteId = candidates[1]
        compose.castAndLockVote(lockedWinnerId)
        compose.activity.manualStep(
            "On Device B, open 'Vote on a spot', vote for the second place ($unlockedVoteId), verify the vote is recorded, and do not lock it. Then tap Continue here on Device A.",
        )
        compose.waitFor(hasText("1 of 2", substring = true), timeoutMs = 60_000)
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Picks: 1", substring = true)).fetchSemanticsNodes().size == 2
        }

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        assertEarlyFinalizePrompt()
        compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
        val winnerName = candidateName(lockedWinnerId)
        assertFinalizedOnVotingScreen(lockedWinnerId, winnerName)
    }

    // Multi-user: Use Case 3, failure 2b2; only tied places are offered for manual selection.
    @Test
    fun finalize_unresolvedTieOffersOnlyTiedCandidates() {
        assumeMultiUserRun()
        val title = createGroupAndMeetup()
        val inviteCode = requireCreatedInviteCode(createdInviteCode)
        compose.setUpTwoMemberVoting(title, inviteCode)

        val candidates = candidatePlaceIds()
        val firstTiedId = candidates[0]
        val secondTiedId = candidates[1]
        compose.castAndLockVote(firstTiedId)
        compose.activity.manualStep(
            "On Device B, open 'Vote on a spot', vote for the second place ($secondTiedId), lock the vote, and tap Continue here on Device A.",
        )
        compose.waitFor(hasText("2 of 2", substring = true), timeoutMs = 60_000)
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Picks: 1", substring = true)).fetchSemanticsNodes().size == 2
        }

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.ERROR_TEXT) and hasText("Tie between candidates", substring = true))
        compose.onNodeWithTag(TestTags.ERROR_TEXT).performScrollTo().assertTextContains("Tie between candidates", substring = true)
        val choices = manualPickCandidates(expectedCount = 2)
        assertEquals(setOf(firstTiedId, secondTiedId), choices.keys)
    }

    // Use Case 3 (Finalize Meetup), failure 1b: offline finalization reports an error.
    @Test
    fun finalize_offlineError() {
        openPreparedVotingScreen()
        dismissVotingStartedNotice()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            compose.waitFor(hasTestTag(TestTags.REFRESH_RESULTS_BUTTON), timeoutMs = 20_000)
            compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
            assertEarlyFinalizePrompt()
            compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
            compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextContains("Network error", substring = true)
            compose.onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
        } finally {
            device.executeShellCommand("cmd connectivity airplane-mode disable")
        }
    }

    // Use Case 4 (Add Meetup to Google Calendar): verify the finalized meetup event.
    @Test
    fun calendar_addFinalizedMeetup() {
        val meetupTitle = openPreparedVotingScreen()
        val placeId = candidatePlaceIds().first()

        // Vote for and lock the first option so finalization chooses it automatically.
        compose.castAndLockVote(placeId)

        compose.finalizeAndReturnToMeetup(meetupTitle)

        val calendarSection = hasTestTag(TestTags.ADD_TO_CALENDAR_BUTTON) or hasTestTag(TestTags.CALENDAR_ADDED_TEXT)
        compose.waitFor(calendarSection)

        if (compose.exists(hasTestTag(TestTags.ADD_TO_CALENDAR_BUTTON))) {
            addCalendarEventFromUi()
        }

        compose.onNodeWithTag(TestTags.CALENDAR_ADDED_TEXT).assertIsDisplayed()
        openCalendarAndAssertEventExists(meetupTitle)
    }

    // Use Case 4, failure 7b: a repeated client request is rejected after the first event was recorded.
    @Test
    fun calendar_duplicateEvent() {
        val meetupTitle = openFinalizedMeetupPage()
        addCalendarEventFromUi()
        compose.onNodeWithTag(TestTags.CALENDAR_ADDED_TEXT).assertIsDisplayed()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MeetFairApplication).container
        val auth = runBlocking { container.calendarAuthorizer.requestCalendarAuthorization(compose.activity) }
        val accessToken = when (auth) {
            is CalendarAuthResult.Success -> auth.accessToken
            is CalendarAuthResult.NeedsConsent -> throw AssertionError("Calendar consent should already be granted")
            is CalendarAuthResult.Failure -> throw AssertionError("Could not get Calendar token: ${auth.error.message}")
        }

        val createdName = checkNotNull(createdGroupName)
        val group = when (val groups = runBlocking { container.groupRepository.list() }) {
            is ApiResult.Success -> groups.data.firstOrNull { it.name == createdName }
            is ApiResult.Failure -> null
        } ?: throw AssertionError("Could not find the test group '$createdName'")
        val meetup = when (val meetups = runBlocking { container.meetupRepository.listForGroup(group.id) }) {
            is ApiResult.Success -> meetups.data.firstOrNull { it.title == meetupTitle }
            is ApiResult.Failure -> null
        } ?: throw AssertionError("Could not find the test meetup '$meetupTitle'")

        val duplicate = runBlocking { container.meetupRepository.addToCalendar(meetup.id, accessToken) }
        assertEquals("This meetup is already in your calendar", (duplicate as? ApiResult.Failure)?.error?.message)
    }

    private fun openCalendarAndAssertEventExists(meetupTitle: String) {
        val device = launchCalendarOrSkip()
        if (device == null) return
        searchCalendarForEvent(device, meetupTitle)
    }

    private fun launchCalendarOrSkip(): UiDevice? {
        val calendarPackage = "com.google.android.calendar"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val installedPackages = device.executeShellCommand("pm path $calendarPackage")
        if (!installedPackages.contains("package:")) {
            Log.i("FinalizeMeetupTest", "Skipping Calendar UI verification: Google Calendar is not installed")
            return null
        }
        // Launch through the shell so Android package-visibility filtering on the test
        // app cannot hide an installed Calendar package from PackageManager.
        device.executeShellCommand("monkey -p $calendarPackage -c android.intent.category.LAUNCHER 1")
        org.junit.Assert.assertTrue(
            "Google Calendar did not open",
            device.wait(Until.hasObject(By.pkg(calendarPackage)), 15_000),
        )
        Thread.sleep(3000)
        return device
    }

    private fun searchCalendarForEvent(device: UiDevice, meetupTitle: String) {
        val searchButton = device.findObject(UiSelector().descriptionContains("Search")).takeIf { it.exists() }
            ?: device.findObject(UiSelector().textContains("Search")).takeIf { it.exists() }
            ?: throw AssertionError("Could not find Calendar's Search control")
        searchButton.click()

        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000)

        // Retry the search while Calendar may still be syncing the new event.
        val foundEvent = retryUntil(timeoutMs = 45_000, intervalMs = 2_000) {
            val inputField = device.findObject(By.clazz("android.widget.EditText"))
            if (inputField == null) {
                false
            } else {
                inputField.setText(meetupTitle)
                device.pressEnter()
                device.wait(Until.findObject(By.text(meetupTitle)), 5_000) != null
            }
        }

        if (!foundEvent) {
            throw AssertionError("Event '$meetupTitle' was not found in Google Calendar search")
        }
    }

    private fun openFinalizedMeetupPage(): String {
        val meetupTitle = openPreparedVotingScreen()
        compose.castAndLockVote(candidatePlaceIds().first())
        compose.finalizeAndReturnToMeetup(meetupTitle)
        return meetupTitle
    }

    private fun addCalendarEventFromUi() {
        compose.onNodeWithTag(TestTags.ADD_TO_CALENDAR_BUTTON).performScrollTo().performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val completed = hasTestTag(TestTags.CALENDAR_ADDED_TEXT) or hasText("Google Calendar", substring = true)
        for (attempt in 0 until 90) {
            if (compose.exists(completed)) break
            val action = listOf("Allow", "Continue", "Next").firstNotNullOfOrNull { label ->
                device.findObject(UiSelector().text(label)).takeIf { it.exists() }
            }
            if (action != null) {
                action.click()
                Thread.sleep(500)
            } else {
                Thread.sleep(1_000)
            }
        }
        compose.waitFor(completed, timeoutMs = 5_000)
        if (compose.exists(hasText("Google Calendar", substring = true))) {
            val message = compose.onNode(hasText("Google Calendar", substring = true)).fetchSemanticsNode()
                .config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString("") { it.text }
            throw AssertionError("Adding the finalized meetup to Google Calendar failed: $message")
        }
    }

    private fun candidatePlaceIds(): List<String> {
        return compose.candidatePlaceIds()
    }

    private fun assertEarlyFinalizePrompt() {
        compose.waitFor(hasTestTag(TestTags.CONFIRM_FINALIZE_BUTTON))
        compose.onNodeWithText("Finalize early?").assertIsDisplayed()
        compose.onNodeWithText("Not every participant has locked their vote, and unlocked votes do not count.")
            .assertIsDisplayed()
        compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).assertTextContains("Finalize anyway")
    }

    private fun confirmFinalizingEarlyIfPrompted() {
        val winnerMatcher = tagStartingWith("pick_winner_button_")
        val confirmMatcher = hasTestTag(TestTags.CONFIRM_FINALIZE_BUTTON)
        compose.waitFor(confirmMatcher or winnerMatcher)
        if (compose.exists(confirmMatcher)) {
            assertEarlyFinalizePrompt()
            compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
        }
    }

    private fun assertFinalizedOnVotingScreen(placeId: String, placeName: String) {
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))
        compose.onNodeWithTag("candidate_map_$placeId").performScrollTo().assertTextContains(placeName)
        compose.onNodeWithTag(TestTags.WINNER_TEXT).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasTestTag(TestTags.voteButton(placeId))).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(TestTags.LOCK_VOTE_BUTTON)).assertCountEquals(0)
    }

    private fun assertFinalizedOnMeetupScreen(title: String, winnerName: String) {
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(title))
        compose.waitFor(hasTestTag(TestTags.MEETUP_WINNER_TEXT))
        compose.onNodeWithText("MEETING SPOT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TestTags.MEETUP_WINNER_TEXT).assertTextContains(winnerName)
        compose.onNodeWithTag(TestTags.ADD_TO_CALENDAR_BUTTON).performScrollTo().assertIsDisplayed()
    }

    private fun manualPickCandidates(expectedCount: Int = 3): Map<String, String> {
        val matcher = tagStartingWith("pick_winner_button_")
        compose.waitUntil(NETWORK_TIMEOUT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == expectedCount
        }
        val rows = compose.onAllNodes(matcher).fetchSemanticsNodes()
        assertEquals("Manual pick should show exactly $expectedCount candidates", expectedCount, rows.size)
        return rows.associate { node ->
            val id = node.config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix("pick_winner_button_")
            val name = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
            org.junit.Assert.assertTrue("Manual-pick candidate $id should have a location name", name.isNotBlank())
            compose.onNodeWithTag(TestTags.pickWinnerButton(id))
                .performScrollTo()
                .assertTextContains(name)
            id to name
        }
    }

    private fun dismissVotingStartedNotice() {
        compose.dismissSnackbarNotices()
    }

    private fun openPreparedVotingScreen(): String = createMeetupAndEnterDetailsToVoting()

    private fun createFairnessTestGroup(): String {
        val group = compose.createGroup(createdGroupNames, "E2E Fairness Group")
        compose.onNodeWithText(group.name).assertIsDisplayed()
        return group.name
    }

    // Creates a uniquely named group and its first meetup, leaving the app on the meetup page.
    private fun createGroupAndMeetup(): String {
        val created = compose.createGroupAndMeetup(createdGroupNames, "E2E Finalize Group", "E2E Finalize Meetup")
        createdGroupName = created.groupName
        createdInviteCode = created.inviteCode
        return created.title
    }

    // Creates a meetup, submits this user's starting location, and starts voting for it.
    private fun createMeetupAndEnterDetailsToVoting(): String {
        val meetupTitle = createGroupAndMeetup()
        compose.submitParticipation()
        startVoting()
        return meetupTitle
    }

    private fun startVoting() {
        compose.startVotingFromMeetup()
    }

    private fun candidateName(placeId: String): String =
        compose.onNodeWithTag("candidate_map_$placeId").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

}
