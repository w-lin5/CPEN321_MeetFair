package com.example.cpen321application.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cpen321application.testutil.NETWORK_TIMEOUT_MS
import com.example.cpen321application.testutil.BaseE2ETest
import com.example.cpen321application.testutil.createGroupAndMeetup as createGroupAndMeetupFlow
import com.example.cpen321application.testutil.createGroupWithName
import com.example.cpen321application.testutil.submitParticipation
import com.example.cpen321application.testutil.assumeMultiUserRun
import com.example.cpen321application.testutil.manualStep
import com.example.cpen321application.testutil.textOf
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.ui.common.TestTags
import org.junit.Assert.assertTrue
import org.junit.Test

// Generated codes use the unambiguous alphabet in backend config.ts (no 0/O, 1/I/L)
private val INVITE_CODE_TEXT = Regex("([ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6})")

// Contains "0", which the generator never emits, so no group can ever have this code
private const val UNUSED_INVITE_CODE = "000000"

/**
 * End-to-end tests for Use Case 2 "Create or Join Group" (Requirements_and_Design.md, feature 2) against
 * the real back end. Each test starts signed in on the group list and creates the data it needs.
 * Joining another user's group needs a second account and is a manual step (documentation/Testing.md).
 */
class CreateJoinGroupTest : BaseE2ETest() {

    // Create meetup: the creator is shown as the organizer
    @Test
    fun createMeetup_creatorIsOrganizer() {
        val (groupName, meetupTitle) = createGroupAndMeetup("E2E Organizer")

        compose.onNodeWithTag("meetup_organizer_text")
            .assertIsDisplayed()
            .assertTextContains("organized", substring = true, ignoreCase = true)
        compose.onNodeWithTag(TestTags.MEETUP_TITLE_TEXT).assertTextContains(meetupTitle)
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_ITEM) and hasText(meetupTitle))
        compose.onNodeWithText(groupName).assertIsDisplayed()
    }

    // Participation: a member submits their meetup details
    @Test
    fun participation_submitDetails() {
        val (_, meetupTitle) = createGroupAndMeetup("E2E Participation")
        compose.submitParticipation()

        compose.onNodeWithTag(TestTags.PARTICIPATION_STATUS_TEXT)
            .assertTextContains("Your details are in")
        compose.onNodeWithText("1 member(s) have submitted their details", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_ITEM) and hasText(meetupTitle))
    }

    // Leave meetup: a submitted participant leaves while the meetup is in setup
    @Test
    fun leaveMeetup_leave() {
        createGroupAndMeetup("E2E Leave Meetup")
        compose.submitParticipation()

        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("leave_meetup").performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT))
        compose.onNodeWithTag(TestTags.PARTICIPATION_BUTTON).assertIsDisplayed()
    }

    // Delete meetup: the organizer deletes a meetup while it is in setup
    @Test
    fun deleteMeetup_deletesInSetup() {
        val (_, meetupTitle) = createGroupAndMeetup("E2E Delete Meetup")

        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("delete_meetup").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitFor(hasTestTag(TestTags.MEETUPS_EMPTY))
        compose.onAllNodes(hasText(meetupTitle)).assertCountEquals(0)
    }
    
    // Delete group: the organizer deletes their group
    @Test
    fun deleteGroup_deletesGroup() {
        val groupName = uniqueName("E2E Delete Group")
        createdGroupNames += groupName
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))

        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("delete_group").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitFor(hasTestTag(TestTags.CREATE_GROUP_BUTTON))
        compose.onAllNodes(hasTestTag(TestTags.GROUP_ITEM) and hasText(groupName)).assertCountEquals(0)
    }

    // Create: main success scenario, steps 1-5
    @Test
    fun createGroup_showsInviteCodeAndListsTheGroup() {
        val name = uniqueName("E2E Create")
        createdGroupNames += name

        // 1. The user selects the option to create a group
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).assertIsNotEnabled()

        // 2. The user enters a group name
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(name)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).assertIsEnabled().performClick()

        // 3-5. The group is created with the user as its only member (the organizer) and shown with its invite code
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        compose.onNodeWithText(name).assertIsDisplayed()
        assertTrue(INVITE_CODE_TEXT.matches(compose.textOf(TestTags.INVITE_CODE_TEXT)))
        compose.onAllNodesWithTag(TestTags.MEMBER_ITEM).assertCountEquals(1)
        compose.onNodeWithTag(TestTags.MEMBER_ITEM).assertTextContains("organizer", substring = true, ignoreCase = true)

        // The new group is listed on the main screen
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.GROUP_ITEM) and hasText(name))
    }

    // Multi-user: Use Case 2, success; User B joins User A's group with its invite code.
    @Test
    fun joinGroup_success() {
        assumeMultiUserRun()
        val groupName = uniqueName("E2E Join Success")
        val inviteCode = createGroupAndReadInviteCode(groupName)
        assertMemberCountAfterReload(
            groupName,
            expected = 2,
            manualStep = "On Device B:\n1. Select Join group.\n2. Enter invite code $inviteCode.\n3. Tap Join group.\n" +
                "4. Verify '$groupName' opens.\n5. Tap Continue here on Device A.",
        )
    }

    // Multi-user: Feature 2, Use Case 6, success; User B leaves User A's group.
    @Test
    fun leaveGroup_nonOrganizerCanLeave() {
        assumeMultiUserRun()
        val groupName = uniqueName("E2E Leave Group")
        val inviteCode = createGroupAndReadInviteCode(groupName)
        assertMemberCountAfterReload(
            groupName,
            expected = 1,
            manualStep = "On Device B:\n1. Select Join group.\n2. Enter invite code $inviteCode and tap Join group.\n" +
                "3. In '$groupName', open Options.\n4. Select Leave group and confirm Leave.\n" +
                "5. Tap Continue here on Device A.",
        )
    }

    // Multi-user: Use Case 7, success; User A removes User B from the group.
    @Test
    fun removeMember_success() {
        assumeMultiUserRun()
        val groupName = uniqueName("E2E Remove Member")
        val inviteCode = createGroupAndReadInviteCode(groupName)
        assertMemberCountAfterReload(
            groupName,
            expected = 2,
            manualStep = "On Device B:\n1. Select Join group.\n2. Enter invite code $inviteCode.\n3. Tap Join group.\n" +
                "4. Verify '$groupName' opens.\n5. Tap Continue here on Device A.",
        )

        compose.onNodeWithText("Remove").performClick()
        compose.waitFor(hasText("Remove member?"))
        compose.onNodeWithText("Remove member?").assertIsDisplayed()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        assertMemberCount(1)
    }

    // Create: failure scenario 2a, a blank group name
    @Test
    fun createGroup_blankNameCannotBeSubmitted() {
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()

        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput("   ")

        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).assertIsNotEnabled()
    }

    // Create: failure scenario 2b, a name longer than 60 characters, followed by retry.
    @Test
    fun createGroup_nameLongerThan60Characters() {
        val expectedError = "Invalid request: name must be a non-empty string of at most 60 characters"
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput("x".repeat(61))
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()

        compose.waitFor(hasTestTag(TestTags.ERROR_TEXT) and hasText(expectedError))
        compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextEquals(expectedError)

        val validName = uniqueName("E2E Short Retry")
        createdGroupNames += validName
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextClearance()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(validName)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        compose.onNodeWithText(validName).assertIsDisplayed()
    }

    // Join: failure scenario 2a, an invite code that matches no group
    @Test
    fun joinGroup_invalidCodeShowsErrorAndAllowsRetry() {
        compose.onNodeWithTag(TestTags.JOIN_GROUP_BUTTON).performClick()

        compose.onNodeWithTag(TestTags.INVITE_CODE_INPUT).performTextInput(UNUSED_INVITE_CODE)
        compose.onNodeWithTag(TestTags.JOIN_GROUP_SUBMIT).performClick()

        // 2a1. The app says the code is invalid
        compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
        compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextEquals("Invalid invite code")
        // 2a2. The user stays on the join screen and can enter another code
        compose.onNodeWithTag(TestTags.INVITE_CODE_INPUT).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.JOIN_GROUP_SUBMIT).assertIsEnabled()
    }

    // Join: failure scenario 3a, the user is already a member of the group
    @Test
    fun joinGroup_alreadyMemberShowsError() {
        val code = createGroupAndReadInviteCode(uniqueName("E2E Rejoin"))
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.JOIN_GROUP_BUTTON))

        compose.onNodeWithTag(TestTags.JOIN_GROUP_BUTTON).performClick()
        // Codes are matched case-insensitively, so the lower-case form must find the same group
        compose.onNodeWithTag(TestTags.INVITE_CODE_INPUT).performTextInput(code.lowercase())
        compose.onNodeWithTag(TestTags.JOIN_GROUP_SUBMIT).performClick()

        // 3a1. The app says the user is already in the group
        compose.waitFor(hasTestTag(TestTags.ERROR_TEXT) and hasText("Already a member of this group"))
        compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextEquals("Already a member of this group")
    }

    // Create: failure scenario 3a; Join: failure scenario 3b, network unavailable
    @Test
    fun createAndJoinGroup_offlineShowsNetworkError() {
        val name = uniqueName("E2E Offline Create")
        createdGroupNames += name
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            // Turning airplane mode to cut off network connection
            device.executeShellCommand("cmd connectivity airplane-mode enable")

            compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
            compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(name)
            compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
            compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextContains("Network error", substring = true)

            compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
            compose.waitFor(hasTestTag(TestTags.JOIN_GROUP_BUTTON))
            compose.onNodeWithTag(TestTags.JOIN_GROUP_BUTTON).performClick()
            compose.onNodeWithTag(TestTags.INVITE_CODE_INPUT).performTextInput(UNUSED_INVITE_CODE)
            compose.onNodeWithTag(TestTags.JOIN_GROUP_SUBMIT).performClick()
            compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
            compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextContains("Network error", substring = true)
        } finally {
            restoreNetwork()
        }
    }

    // Creates a group through the UI and returns its invite code (the group page stays open)
    private fun createGroupAndReadInviteCode(name: String): String {
        val created = compose.createGroupWithName(createdGroupNames, name)
        val match = INVITE_CODE_TEXT.matchEntire(created.inviteCode)
        return checkNotNull(match) { "Group page shows no invite code" }.groupValues[1]
    }

    private fun createGroupAndMeetup(prefix: String): Pair<String, String> {
        val created = compose.createGroupAndMeetupFlow(createdGroupNames, prefix)
        return created.groupName to created.title
    }

    private fun assertMemberCountAfterReload(groupName: String, expected: Int, manualStep: String) {
        compose.activity.manualStep(manualStep)
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        val groupMatcher = hasTestTag(TestTags.GROUP_ITEM) and hasText(groupName)
        compose.waitFor(groupMatcher)
        compose.onNode(groupMatcher).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
        assertMemberCount(expected)
    }

    private fun assertMemberCount(expected: Int) {
        compose.waitUntil(NETWORK_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TestTags.MEMBER_ITEM).fetchSemanticsNodes().size == expected
        }
        compose.onAllNodesWithTag(TestTags.MEMBER_ITEM).assertCountEquals(expected)
    }

}
