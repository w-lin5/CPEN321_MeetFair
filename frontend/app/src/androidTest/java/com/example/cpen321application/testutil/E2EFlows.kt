package com.example.cpen321application.testutil

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.example.cpen321application.ui.common.TestTags

data class CreatedMeetup(val groupName: String, val title: String, val inviteCode: String)

fun ComposeTestRule.createGroupAndMeetup(
    createdGroupNames: MutableList<String>,
    groupPrefix: String,
    meetupPrefix: String = "$groupPrefix Meetup",
): CreatedMeetup {
    val groupName = uniqueName(groupPrefix)
    val meetupTitle = uniqueName(meetupPrefix)
    createdGroupNames += groupName
    onNodeWithTag(TestTags.CREATE_GROUP_BUTTON).performClick()
    onNodeWithTag(TestTags.GROUP_NAME_INPUT).performTextInput(groupName)
    onNodeWithTag(TestTags.CREATE_GROUP_SUBMIT).performClick()
    waitFor(hasTestTag(TestTags.INVITE_CODE_TEXT))
    val inviteCode = textOf(TestTags.INVITE_CODE_TEXT)
    onNodeWithTag(TestTags.NEW_MEETUP_BUTTON).performClick()
    onNodeWithTag(TestTags.MEETUP_TITLE_INPUT).performTextInput(meetupTitle)
    onNodeWithTag(TestTags.CREATE_MEETUP_SUBMIT).performClick()
    waitFor(hasTestTag(TestTags.MEETUP_TITLE_TEXT) and hasText(meetupTitle))
    return CreatedMeetup(groupName, meetupTitle, inviteCode)
}

fun ComposeTestRule.startVotingFromMeetup() {
    waitFor(hasTestTag(TestTags.START_VOTING_BUTTON))
    onNodeWithTag(TestTags.START_VOTING_BUTTON).performClick()
    waitFor(hasTestTag(TestTags.VOTING_STATUS_TEXT))
    onNodeWithTag(TestTags.VOTING_STATUS_TEXT).assertTextContains("Voting is open")
    dismissSnackbarNotices()
}

fun ComposeTestRule.candidatePlaceIds(timeoutMs: Long = NETWORK_TIMEOUT_MS): List<String> {
    val matcher = tagStartingWith("candidate_item_")
    waitFor(matcher, timeoutMs)
    return onAllNodes(matcher).fetchSemanticsNodes().mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("candidate_item_")
    }
}

private fun tagStartingWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}
