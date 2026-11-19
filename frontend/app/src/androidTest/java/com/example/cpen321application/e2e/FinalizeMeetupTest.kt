package com.example.cpen321application.e2e

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
import androidx.compose.ui.test.performTextInput
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.example.cpen321application.testutil.BaseE2ETest
import com.example.cpen321application.testutil.NETWORK_TIMEOUT_MS
import com.example.cpen321application.testutil.dismissSnackbarNotices
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.ui.common.TestTags
import org.junit.Test

/**
 * End-to-end tests for Use Case 4 "Voting and Fairness", focusing on finalizing meetups and fairness against
 * the back end. Voting candidates require Google Places/Routes data. Each test starts signed in on the group 
 * list and creates the meetup it needs. Multi-member weighted voting and Google Calendar OAuth/API outcomes need
 * additional accounts and device consent, so those remain manual checks (documentation/Testing.md).
 */
class FinalizeMeetupTest : BaseE2ETest() {

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
        val winnerText = compose.onNodeWithTag("candidate_map_$winnerId").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty()
            .joinToString("") { it.text }
        org.junit.Assert.assertTrue("Candidate should have a visible name", winnerText.isNotBlank())

        compose.onNodeWithTag(TestTags.voteButton(winnerId)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.voteButton(winnerId)) and hasText("Your vote"))
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.VOTE_LOCKED_TEXT))

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
        compose.onNodeWithText("Finalize early?").assertDoesNotExist()
    }

    // Use Case 3 (Finalize Meetup), failure 2a: the organizer picks with no locked votes.
    @Test
    fun finalize_earlyPromptFinalizeAnyway() {
        val title = openPreparedVotingScreen()
        dismissVotingStartedNotice()

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        assertEarlyFinalizePrompt()
        compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()

        val candidates = manualPickCandidates()
        val winnerId = candidates.keys.first()
        val winnerName = candidates.getValue(winnerId)
        compose.onNodeWithTag(TestTags.pickWinnerButton(winnerId)).performScrollTo().performClick()

        assertFinalizedOnVotingScreen(winnerId, winnerName)
        assertFinalizedOnMeetupScreen(title, winnerName)
    }

    // Use Case 3 (Finalize Meetup), failure 1b: offline finalization reports an error.
    @Test
    fun finalize_offlineError() {
        openPreparedVotingScreen()
        dismissVotingStartedNotice()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("cmd connectivity airplane-mode enable")
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
        compose.onNodeWithTag(TestTags.voteButton(placeId)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.voteButton(placeId)) and hasText("Your vote"))
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.VOTE_LOCKED_TEXT))

        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))

        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle), timeoutMs = 30_000)
        compose.dismissSnackbarNotices()

        val calendarSection = hasTestTag(TestTags.ADD_TO_CALENDAR_BUTTON) or hasTestTag(TestTags.CALENDAR_ADDED_TEXT)
        compose.waitFor(calendarSection)

        if (compose.exists(hasTestTag(TestTags.ADD_TO_CALENDAR_BUTTON))) {
            compose.onNodeWithTag(TestTags.ADD_TO_CALENDAR_BUTTON).performScrollTo().performClick()

            // A first-time authorization may show Google's consent UI. Accept it when
            // present; with prior consent the app proceeds directly to the result.
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val completed = hasTestTag(TestTags.CALENDAR_ADDED_TEXT) or hasTestTag(TestTags.ERROR_TEXT)
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
            if (compose.exists(hasTestTag(TestTags.ERROR_TEXT))) {
                val message = compose.onNodeWithTag(TestTags.ERROR_TEXT).fetchSemanticsNode()
                    .config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString("") { it.text }
                org.junit.Assert.fail("Adding the finalized meetup to Google Calendar failed: $message")
            }
        }

        compose.onNodeWithTag(TestTags.CALENDAR_ADDED_TEXT).assertIsDisplayed()
        if (compose.exists(hasTestTag(TestTags.ERROR_TEXT))) {
            val message = compose.onNodeWithTag(TestTags.ERROR_TEXT).fetchSemanticsNode()
                .config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString("") { it.text }
            org.junit.Assert.fail("Adding the finalized meetup to Google Calendar failed: $message")
        }
        compose.onNodeWithTag(TestTags.CALENDAR_ADDED_TEXT).assertIsDisplayed()
        openCalendarAndAssertEventExists(meetupTitle)
    }

    private fun openCalendarAndAssertEventExists(meetupTitle: String) {
        val calendarPackage = "com.google.android.calendar"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val installedPackages = device.executeShellCommand("pm path $calendarPackage")
        if (!installedPackages.contains("package:")) {
            Log.i("FinalizeMeetupTest", "Skipping Calendar UI verification: Google Calendar is not installed")
            return
        }
        // Launch through the shell so Android package-visibility filtering on the test
        // app cannot hide an installed Calendar package from PackageManager.
        device.executeShellCommand("monkey -p $calendarPackage -c android.intent.category.LAUNCHER 1")
        org.junit.Assert.assertTrue(
            "Google Calendar did not open",
            device.wait(Until.hasObject(By.pkg(calendarPackage)), 15_000),
        )

        // Allow Google Calendar 3 seconds to complete initial launch & sync
        Thread.sleep(3000)

        val searchButton = device.findObject(UiSelector().descriptionContains("Search")).takeIf { it.exists() }
            ?: device.findObject(UiSelector().textContains("Search")).takeIf { it.exists() }
            ?: throw AssertionError("Could not find Calendar's Search control")
        searchButton.click()

        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000)

        // Search for the event, retrying if Google Calendar was still syncing cloud events when opened
        var foundEvent = false
        val deadline = System.currentTimeMillis() + 45_000L
        while (System.currentTimeMillis() < deadline && !foundEvent) {
            try {
                val inputField = device.findObject(By.clazz("android.widget.EditText"))
                if (inputField != null) {
                    inputField.setText(meetupTitle)
                    device.pressEnter()
                }
                val eventNode = device.wait(
                    Until.findObject(By.text(meetupTitle)),
                    5_000,
                )
                if (eventNode != null) {
                    foundEvent = true
                } else {
                    device.findObject(By.clazz("android.widget.EditText"))?.setText("")
                    Thread.sleep(2000)
                }
            } catch (_: StaleObjectException) {
                Thread.sleep(1000)
            }
        }

        if (!foundEvent) {
            throw AssertionError("Event '$meetupTitle' was not found in Google Calendar search")
        }

        // Click the search result event, re-fetching the node if it went stale during sync/render
        var openedDetails = false
        val clickDeadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < clickDeadline && !openedDetails) {
            try {
                val target = device.findObject(By.text(meetupTitle))
                target?.click()
                openedDetails = device.wait(Until.hasObject(By.text(meetupTitle)), 3_000)
            } catch (_: StaleObjectException) {
                Thread.sleep(500)
            }
        }

        org.junit.Assert.assertTrue(
            "The Google Calendar event details did not open",
            openedDetails,
        )
    }

    private fun candidatePlaceIds(): List<String> {
        val matcher = tagStartingWith("candidate_item_")
        compose.waitFor(matcher)
        return compose.onAllNodes(matcher).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("candidate_item_")
        }
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
        compose.onNodeWithTag(TestTags.voteButton(placeId)).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).assertDoesNotExist()
    }

    private fun assertFinalizedOnMeetupScreen(title: String, winnerName: String) {
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(title))
        compose.waitFor(hasTestTag(TestTags.MEETUP_WINNER_TEXT))
        compose.onNodeWithText("MEETING SPOT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TestTags.MEETUP_WINNER_TEXT).assertTextContains(winnerName)
        compose.onNodeWithTag(TestTags.ADD_TO_CALENDAR_BUTTON).performScrollTo().assertIsDisplayed()
    }

    private fun manualPickCandidates(): Map<String, String> {
        val matcher = tagStartingWith("pick_winner_button_")
        compose.waitUntil(NETWORK_TIMEOUT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 3
        }
        val rows = compose.onAllNodes(matcher).fetchSemanticsNodes()
        org.junit.Assert.assertEquals("Manual pick should show exactly three candidates", 3, rows.size)
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
        val groupName = uniqueName("E2E Fairness Group")
        createdGroupNames += groupName
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        compose.onNodeWithText(groupName).assertIsDisplayed()
        return groupName
    }

    // Creates a uniquely named group and its first meetup, leaving the app on the meetup page.
    private fun createGroupAndMeetup(): String {
        val groupName = uniqueName("E2E Finalize Group")
        createdGroupNames += groupName
        val meetupTitle = uniqueName("E2E Finalize Meetup")

        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        compose.onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
        compose.onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
        return meetupTitle
    }

    // Creates a meetup, submits this user's starting location, and starts voting for it.
    private fun createMeetupAndEnterDetailsToVoting(): String {
        val meetupTitle = createGroupAndMeetup()
        compose.waitFor(hasTestTag(TestTags.PARTICIPATION_BUTTON))
        compose.onNodeWithTag(TestTags.PARTICIPATION_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.LOCATION_PICKER_BUTTON))
        compose.onNodeWithTag(TestTags.LOCATION_PICKER_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.LOCATION_CONFIRM))
        compose.onNodeWithTag(TestTags.LOCATION_CONFIRM).performClick()
        compose.waitFor(hasTestTag(TestTags.PARTICIPATION_SUBMIT) and isEnabled())
        compose.onNodeWithTag(TestTags.PARTICIPATION_SUBMIT).performClick()

        val submittedStatus = hasTestTag(TestTags.PARTICIPATION_STATUS_TEXT) and
            hasText("Your details are in")
        compose.waitFor(submittedStatus, timeoutMs = 60_000)

        compose.waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
        compose.onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT))
        compose.onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
        compose.dismissSnackbarNotices()
        return meetupTitle
    }

    private fun tagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
