package com.example.cpen321application.testutil

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.cpen321application.ui.common.TestTags
import org.junit.Assert.fail

// E2E tests talk to the real back end; a cold server or a slow emulator network can take several seconds
const val NETWORK_TIMEOUT_MS = 20_000L

fun ComposeTestRule.exists(matcher: SemanticsMatcher): Boolean = onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

/** Waits until at least one node matches, e.g. a screen that appears after a back-end round trip. */
fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = NETWORK_TIMEOUT_MS) {
    waitUntil(timeoutMs) { exists(matcher) }
}

/**
 * Course precondition for feature tests: the user is already signed in and the app opens on its main
 * screen (the group list). Fails with setup instructions instead of a confusing timeout otherwise.
 */
fun ComposeTestRule.assertSignedInOnGroupList() {
    val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
    val signIn = hasTestTag(TestTags.SIGN_IN_BUTTON)
    waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) || exists(signIn) }
    if (!exists(groupList)) {
        fail("Sign in to MeetFair on the device before running the E2E tests (see documentation/Testing.md)")
    }
}

/** The text of the single node tagged [tag], e.g. "Invite code: ABC234". */
fun ComposeTestRule.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }
        .joinToString("") { it.text }

/** A name no earlier run has used, so a test never depends on data left by another test or user. */
fun uniqueName(prefix: String): String = "$prefix ${System.currentTimeMillis()}"
