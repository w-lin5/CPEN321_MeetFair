package com.example.cpen321application.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cpen321application.MainActivity
import com.example.cpen321application.testutil.assertSignedInOnGroupList
import com.example.cpen321application.testutil.cleanupCreatedGroups
import com.example.cpen321application.testutil.exists
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.ui.common.TestTags
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for Use Case 1 "Vote on a Candidate" (Requirements_and_Design.md, feature 4)
 * against back end. Voting candidates require Google Places/Routes data, so the main success test
 * needs those APIs configured. Each test starts signed in on the group list and creates the data it
 * needs. Tie-breaking and weighted voting requires a second account and is a manual step
 * (documentation/Testing.md).
 */
@RunWith(AndroidJUnit4::class)
class VotingTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val createdGroupNames = mutableListOf<String>()

    @Before
    fun startOnGroupList() {
        createdGroupNames.clear()
        compose.assertSignedInOnGroupList()
    }

    @After
    fun deleteGroupsCreatedByTest() = compose.cleanupCreatedGroups(createdGroupNames)

    // Vote on a Candidate: main success scenario, steps 1-8
    @Test
    fun vote_castChangeAndLockVote() {
        // 1. The member opens a meetup in VOTING and selects "Vote on a spot".
        createMeetupAndEnterDetailsToVoting()

        // 2. The app shows the three places, each with its number of picks, locked weight and share,
        // and how many participants have locked their vote. While live updates are connected it
        // shows "Live: votes update automatically".
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT))
        compose.onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
        val candidateMatcher = tagStartingWith("candidate_item_")
        compose.waitFor(candidateMatcher)
        val candidates = compose.onAllNodes(candidateMatcher).fetchSemanticsNodes()
        org.junit.Assert.assertEquals("Voting should show exactly three candidates", 3, candidates.size)
        val placeIds = candidates.mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("candidate_item_")
        }
        org.junit.Assert.assertEquals(3, placeIds.size)
        compose.onAllNodes(hasText("Picks:", substring = true)).assertCountEquals(3)
        compose.onAllNodes(hasText("Locked weight:", substring = true)).assertCountEquals(3)
        compose.onNodeWithTag(TestTags.VOTER_COUNT_TEXT).assertTextContains("participant(s) have locked their vote", substring = true)
        compose.waitFor(hasTestTag(TestTags.VOTING_LIVE_TEXT))
        compose.onNodeWithTag(TestTags.VOTING_LIVE_TEXT).assertTextContains("Live: votes update automatically", substring = true)

        // 3. The member selects "Vote" on one place.
        val first = placeIds[0]
        val second = placeIds[1]
        compose.onNodeWithTag(TestTags.voteButton(first)).performScrollTo().performClick()

        // 4. Wait for the vote request; check that the candidate says "Your vote" and Submit vote
        // is displayed. The multi-member tally broadcast is covered manually.
        compose.waitFor(hasTestTag(TestTags.voteButton(first)) and hasText("Your vote"))
        compose.onNodeWithTag(TestTags.voteButton(first)).performScrollTo().assertTextContains("Your vote", substring = true)
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().assertIsDisplayed()

        // 5. The member selects "Change vote" on another place; check that the new and previous
        // candidate labels update.
        compose.onNodeWithTag(TestTags.voteButton(second)).performScrollTo().assertTextContains("Change vote", substring = true).performClick()
        compose.waitFor(hasTestTag(TestTags.voteButton(second)) and hasText("Your vote"))
        compose.onNodeWithTag(TestTags.voteButton(second)).performScrollTo().assertTextContains("Your vote", substring = true)
        compose.waitFor(hasTestTag(TestTags.voteButton(first)) and hasText("Change vote"))
        compose.onNodeWithTag(TestTags.voteButton(first)).performScrollTo().assertTextContains("Change vote", substring = true)

        // 6. The member selects "Submit vote" to lock their vote.
        val votingStartedNotice = hasText("Voting has started.")
        if (compose.exists(votingStartedNotice)) {
            compose.waitUntil(5_000) { !compose.exists(votingStartedNotice) }
        }
        compose.waitFor(hasTestTag(TestTags.LOCK_VOTE_BUTTON) and isEnabled())
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).performScrollTo().performClick()

        // 7. The system locks the vote using the member's Fair Credit weight.
        val lockStartedOrFinished =
            (hasTestTag(TestTags.LOCK_VOTE_BUTTON) and hasText("Locking…")) or
                hasTestTag(TestTags.VOTE_LOCKED_TEXT) or
                hasTestTag(TestTags.ERROR_TEXT)
        compose.waitFor(lockStartedOrFinished)
        compose.waitFor(hasTestTag(TestTags.VOTE_LOCKED_TEXT) or hasTestTag(TestTags.ERROR_TEXT))
        val voteLocked = compose.exists(hasTestTag(TestTags.VOTE_LOCKED_TEXT))
        if (!voteLocked) {
            val errorText = compose.onNodeWithTag(TestTags.ERROR_TEXT).fetchSemanticsNode()
                .config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString("") { it.text }
            org.junit.Assert.fail("Locking the vote failed: $errorText")
        }
        compose.onNodeWithTag(TestTags.VOTE_LOCKED_TEXT).assertTextContains("Your vote is locked (weight ", substring = true)

        // 8. The app shows "Your vote is locked" with the vote's weight; check the locked count
        // and the selected candidate's locked-vote badge.
        val weightText = compose.onNodeWithTag(TestTags.VOTE_LOCKED_TEXT).fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
        val weight = Regex("Your vote is locked \\(weight ([0-9]+(?:\\.[0-9]+)?)\\)")
            .matchEntire(weightText)?.groupValues?.get(1)?.toDouble()
        org.junit.Assert.assertNotNull("Locked vote should show its Fair Credit weight", weight)
        org.junit.Assert.assertTrue("Weight should be between 1.0 and 1.5", weight!! in 1.0..1.5)
        compose.onNodeWithTag(TestTags.VOTER_COUNT_TEXT).assertTextContains("1 of ", substring = true)
        placeIds.forEach { placeId -> compose.onNodeWithTag(TestTags.voteButton(placeId)).assertDoesNotExist() }
        compose.onNodeWithText("Your locked vote").assertIsDisplayed()
    }

    // 2a. Open voting with live updates connected before disconnecting the emulator network.
    @Test
    fun vote_disconnectedLiveShowsManualRefresh() {
        openPreparedVotingScreen()

        compose.waitFor(hasTestTag(TestTags.VOTING_LIVE_TEXT))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            // 2a1. Disconnect live updates and check that manual refresh replaces the live indicator.
            device.executeShellCommand("cmd connectivity airplane-mode enable")

            compose.waitFor(hasTestTag(TestTags.REFRESH_RESULTS_BUTTON), timeoutMs = 20_000)
            compose.onNodeWithTag(TestTags.VOTING_LIVE_TEXT).assertDoesNotExist()
            compose.onNodeWithTag(TestTags.REFRESH_RESULTS_BUTTON).assertIsDisplayed()

            // 2a2. Try to vote while offline and check for a network error without selecting the candidate.
            val candidateTag = compose.onAllNodes(tagStartingWith("candidate_item_"))
                .fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.TestTag)!!
            val placeId = candidateTag.removePrefix("candidate_item_")
            compose.onNodeWithTag(TestTags.voteButton(placeId)).performScrollTo().performClick()
            compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextContains("Network error", substring = true)
            compose.onNodeWithTag(TestTags.voteButton(placeId)).assertTextContains("Vote")

            // 2a3. Select manual refresh and confirm the voter count remains displayed.
            compose.onNodeWithTag(TestTags.REFRESH_RESULTS_BUTTON).performScrollTo().performClick()
            compose.onNodeWithTag(TestTags.VOTER_COUNT_TEXT).assertIsDisplayed()
        } finally {
            device.executeShellCommand("cmd connectivity airplane-mode disable")
        }
    }

    // 3b. Cast an initial vote before disconnecting the network.
    @Test
    fun vote_offlineNetworkFailureKeepsPreviousVoteAndCanRetry() {
        openPreparedVotingScreen()
        compose.waitFor(hasTestTag(TestTags.VOTING_LIVE_TEXT))

        val startedNotice = hasText("Voting has started.")
        if (compose.exists(startedNotice)) {
            compose.waitUntil(  5_000) { !compose.exists(startedNotice) }
        }

        val candidates = compose.onAllNodes(tagStartingWith("candidate_item_")).fetchSemanticsNodes()
        org.junit.Assert.assertEquals("Voting should show three candidates", 3, candidates.size)
        val placeIds = candidates.mapNotNull {
            it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("candidate_item_")
        }
        val original = placeIds[0]
        val replacement = placeIds[1]

        compose.onNodeWithTag(TestTags.voteButton(original)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(TestTags.voteButton(original)) and hasText("Your vote"))

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            // 3b1. Attempt to change the vote offline; verify the original selection is preserved.
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            compose.waitFor(hasTestTag(TestTags.REFRESH_RESULTS_BUTTON), timeoutMs = 20_000)

            compose.onNodeWithTag(TestTags.voteButton(replacement)).performScrollTo().performClick()
            compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextContains("Network error", substring = true)
            compose.onNodeWithTag(TestTags.voteButton(original)).assertTextContains("Your vote", substring = true)
            compose.onNodeWithTag(TestTags.voteButton(replacement)).assertTextContains("Change vote", substring = true)

            // 3b2. Restore connectivity, retry the change, and verify the new selection is saved.
            device.executeShellCommand("cmd connectivity airplane-mode disable")
            val connectivity = compose.activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            compose.waitUntil(20_000) {
                val network = connectivity.activeNetwork
                network != null && connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            compose.onNodeWithTag(TestTags.voteButton(replacement)).performScrollTo().performClick()
            val retrySucceeded = hasTestTag(TestTags.voteButton(replacement)) and hasText("Your vote")
            compose.waitFor(retrySucceeded or hasTestTag(TestTags.ERROR_TEXT))
            if (!compose.exists(retrySucceeded)) {
                val errorText = compose.onNodeWithTag(TestTags.ERROR_TEXT).fetchSemanticsNode()
                    .config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .joinToString("") { it.text }
                org.junit.Assert.fail("Retry after restoring connectivity failed: $errorText")
            }
            compose.onNodeWithTag(TestTags.voteButton(original)).assertTextContains("Change vote", substring = true)
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertDoesNotExist()
        } finally {
            device.executeShellCommand("cmd connectivity airplane-mode disable")
        }
    }

    @Test
    fun vote_finalizeClosesVoting() {
        // Prepare the organizer's voting screen with candidates and no votes locked.
        openPreparedVotingScreen()

        val candidateMatcher = tagStartingWith("candidate_item_")
        compose.waitFor(candidateMatcher)
        val placeId = compose.onAllNodes(candidateMatcher).fetchSemanticsNodes().first()
            .config.getOrNull(SemanticsProperties.TestTag)!!.removePrefix("candidate_item_")

        // Finalize with no locked votes; choose a winner when the UI requests a manual pick.
        val votingStartedNotice = hasText("Voting has started.")
        if (compose.exists(votingStartedNotice)) {
            compose.waitUntil(5_000) { !compose.exists(votingStartedNotice) }
        }
        compose.onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
        val winnerMatcher = tagStartingWith("pick_winner_button_")
        val confirmMatcher = hasTestTag(TestTags.CONFIRM_FINALIZE_BUTTON)
        compose.waitFor(confirmMatcher or winnerMatcher)
        if (compose.exists(confirmMatcher)) {
            compose.onNodeWithTag(TestTags.CONFIRM_FINALIZE_BUTTON).performClick()
        }
        compose.waitFor(winnerMatcher)
        compose.onNodeWithTag(TestTags.pickWinnerButton(placeId)).performScrollTo().performClick()

        // Verify finalization closes voting and removes candidate vote and lock controls.
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))
        compose.onNodeWithTag(TestTags.voteButton(placeId)).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.LOCK_VOTE_BUTTON).assertDoesNotExist()
    }

    // Helper: Creates a group and meetup, enters details, and starts voting for it.
    private fun openPreparedVotingScreen(): String {
        return createMeetupAndEnterDetailsToVoting()
    }

    // Helper: Creates a uniquely named group and its first meetup, leaving the app on the meetup page
    private fun createGroupAndMeetup(): String {
        val groupName = uniqueName("E2E Voting Group")
        createdGroupNames += groupName
        val meetupTitle = uniqueName("E2E Voting Meetup")

        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))

        // Group creation opens the new group page. Create the meetup there.
        compose.onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
        compose.onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
        return meetupTitle
    }

    // Helper: Creates a meetup, submits this user's starting location, and starts voting for it.
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

        // Saving details returns to the meetup page, where its organizer can start voting.
        compose.waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
        compose.onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT))
        compose.onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
        return meetupTitle
    }

    private fun tagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
