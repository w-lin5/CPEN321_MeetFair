package com.example.cpen321application.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cpen321application.MainActivity
import com.example.cpen321application.testutil.assertSignedInOnGroupList
import com.example.cpen321application.testutil.textOf
import com.example.cpen321application.testutil.uniqueName
import com.example.cpen321application.testutil.waitFor
import com.example.cpen321application.ui.common.TestTags
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Generated codes use the unambiguous alphabet in backend config.ts (no 0/O, 1/I/L)
private val INVITE_CODE_TEXT = Regex("Invite code: ([ABCDEFGHJKMNPQRSTUVWXYZ2-9]{6})")

// Contains "0", which the generator never emits, so no group can ever have this code
private const val UNUSED_INVITE_CODE = "000000"

/**
 * End-to-end tests for Use Case 2 "Create or Join Group" (Requirements_and_Design.md, feature 2) against
 * the real back end. Each test starts signed in on the group list and creates the data it needs.
 * Joining another user's group needs a second account and is a manual step (documentation/Testing.md).
 */
@RunWith(AndroidJUnit4::class)
class CreateJoinGroupTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun startOnGroupList() = compose.assertSignedInOnGroupList()

    // Create: main success scenario, steps 1-5
    @Test
    fun createGroup_showsInviteCodeAndListsTheGroup() {
        val name = uniqueName("E2E Create")

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
        compose.onNodeWithTag(TestTags.MEMBER_ITEM).assertTextContains("(organizer)", substring = true)

        // The new group is listed on the main screen
        compose.onNodeWithTag(TestTags.BACK_BUTTON).performClick()
        compose.waitFor(hasTestTag(TestTags.GROUP_ITEM) and hasText(name))
    }

    // Create: failure scenario 2a, a blank group name
    @Test
    fun createGroup_blankNameCannotBeSubmitted() {
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()

        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput("   ")

        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).assertIsNotEnabled()
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
        compose.waitFor(hasTestTag(TestTags.ERROR_TEXT))
        compose.onNodeWithTag(TestTags.ERROR_TEXT).assertTextEquals("Already a member of this group")
    }

    // Creates a group through the UI and returns its invite code (the group page stays open)
    private fun createGroupAndReadInviteCode(name: String): String {
        compose.onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
        compose.onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(name)
        compose.onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
        compose.waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))

        val match = INVITE_CODE_TEXT.matchEntire(compose.textOf(TestTags.INVITE_CODE_TEXT))
        return checkNotNull(match) { "Group page shows no invite code" }.groupValues[1]
    }
}
