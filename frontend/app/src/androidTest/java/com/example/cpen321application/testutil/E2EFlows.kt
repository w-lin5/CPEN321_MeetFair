package com.example.cpen321application.testutil

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.example.cpen321application.ui.common.TestTags

data class CreatedGroup(val name: String, val inviteCode: String)
data class CreatedMeetup(val groupName: String, val title: String, val inviteCode: String)

fun ComposeTestRule.createGroup(createdGroupNames: MutableList<String>, prefix: String): CreatedGroup {
    return createGroupWithName(createdGroupNames, uniqueName(prefix))
}

fun ComposeTestRule.createGroupWithName(createdGroupNames: MutableList<String>, groupName: String): CreatedGroup {
    createdGroupNames += groupName
    onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
    onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
    onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
    waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
    return CreatedGroup(groupName, textOf(TestTags.INVITE_CODE_TEXT))
}

fun ComposeTestRule.createGroupAndMeetup(
    createdGroupNames: MutableList<String>,
    groupPrefix: String,
    meetupPrefix: String = "$groupPrefix Meetup",
): CreatedMeetup {
    val group = createGroup(createdGroupNames, groupPrefix)
    val groupName = group.name
    val meetupTitle = uniqueName(meetupPrefix)
    onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
    onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
    onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
    waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
    return CreatedMeetup(groupName, meetupTitle, group.inviteCode)
}

fun ComposeTestRule.startVotingFromMeetup() {
    waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
    onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
    waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT))
    onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
    dismissSnackbarNotices()
}

fun ComposeTestRule.submitParticipation(expectedMemberCount: Int? = null) {
    waitFor(hasTestTag(TestTags.PARTICIPATION_BUTTON))
    onNodeWithTag(TestTags.PARTICIPATION_BUTTON).performClick()
    waitFor(hasTestTag(TestTags.LOCATION_PICKER_BUTTON))
    onNodeWithTag(TestTags.LOCATION_PICKER_BUTTON).performClick()
    waitFor(hasTestTag(TestTags.LOCATION_CONFIRM))
    onNodeWithTag(TestTags.LOCATION_CONFIRM).performClick()
    waitFor(hasTestTag(TestTags.PARTICIPATION_SUBMIT) and isEnabled())
    onNodeWithTag(TestTags.PARTICIPATION_SUBMIT).performClick()
    waitFor(hasTestTag(TestTags.PARTICIPATION_STATUS_TEXT) and hasText("Your details are in"), timeoutMs = 60_000)
    if (expectedMemberCount != null) {
        onNodeWithTag(TestTags.PARTICIPATION_STATUS_TEXT).assertTextContains("Your details are in")
        onNodeWithText("$expectedMemberCount member(s) have submitted their details", substring = true).assertExists()
    }
}

/** Creates the two-member voting setup shared by manual Device B E2E scenarios. */
fun AndroidComposeTestRule<*, *>.setUpTwoMemberVoting(
    meetupTitle: String,
    inviteCode: String,
    expectedMemberCount: Int? = null,
) {
    submitParticipation(expectedMemberCount)
    activity.manualStep(
        "On Device B, join with invite code $inviteCode, open meetup '$meetupTitle', and enter/save participation details. " +
            "Then tap Continue here on Device A.",
    )
    startVotingFromMeetup()
}

fun ComposeTestRule.navigateBackToMeetup(title: String) {
    onNodeWithTag(TestTags.BACK_BUTTON).performClick()
    waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(title), timeoutMs = 30_000)
    dismissSnackbarNotices()
}

fun ComposeTestRule.finalizeAndReturnToMeetup(title: String) {
    onNodeWithTag(TestTags.FINALIZE_BUTTON).performScrollTo().performClick()
    waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT) and hasText("Voting is closed"))
    navigateBackToMeetup(title)
}

fun ComposeTestRule.candidatePlaceIds(timeoutMs: Long = NETWORK_TIMEOUT_MS): List<String> {
    val matcher = tagStartingWith("candidate_item_")
    waitFor(matcher, timeoutMs)
    return onAllNodes(matcher).fetchSemanticsNodes().mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("candidate_item_")
    }
}

fun tagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}

fun nodeText(node: SemanticsNode): String =
    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }
