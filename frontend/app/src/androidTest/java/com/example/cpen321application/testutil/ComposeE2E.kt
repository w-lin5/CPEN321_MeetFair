package com.example.cpen321application.testutil

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cpen321application.ui.common.TestTags
import org.junit.Assert.fail

// E2E tests talk to the real back end; a cold server or a slow emulator network can take several seconds
const val NETWORK_TIMEOUT_MS = 20_000L

fun ComposeTestRule.exists(matcher: SemanticsMatcher): Boolean = runCatching {
    onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
}.getOrDefault(false)

/** Waits until at least one node matches, e.g. a screen that appears after a back-end round trip. */
fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = NETWORK_TIMEOUT_MS) {
    waitUntil(timeoutMs) { exists(matcher) }
}

/** Waits for any active status snackbars (e.g. "Meetup finalized...", "Voting has started...") to dismiss. */
fun ComposeTestRule.dismissSnackbarNotices() {
    val notice = hasText("Voting has started.", substring = true) or
        hasText("Meetup finalized", substring = true)
    if (exists(notice)) {
        runCatching { waitUntil(7_000) { !exists(notice) } }
    }
}

/** Relaunches the app main activity if it was closed or killed during a test. */
fun ComposeTestRule.relaunchApp() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val packageName = targetContext.packageName
    val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent != null) {
        targetContext.startActivity(intent)
        val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
        val signIn = hasTestTag(TestTags.SIGN_IN_BUTTON)
        runCatching { waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) || exists(signIn) } }
    }
}

/**
 * Course precondition for feature tests: the user is already signed in and the app opens on its main
 * screen (the group list). Fails with setup instructions instead of a confusing timeout otherwise.
 */
fun ComposeTestRule.assertSignedInOnGroupList() {
    val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
    val signIn = hasTestTag(TestTags.SIGN_IN_BUTTON)
    if (!exists(groupList) && !exists(signIn)) {
        relaunchApp()
    }
    waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) || exists(signIn) }
    if (!exists(groupList)) {
        fail("Sign in to MeetFair on the device before running the E2E tests (see documentation/Testing.md)")
    }
}

/**
 * Deletes groups created by an E2E test. Attempts every group and reports cleanup failures so a
 * broken cleanup flow cannot silently leave test data behind.
 */
fun ComposeTestRule.cleanupCreatedGroups(names: Iterable<String>) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
    val cleanupErrors = mutableListOf<String>()
    val groupsToDelete = names.toList().distinct().asReversed()
    if (groupsToDelete.isEmpty()) return

    fun returnToGroupList() {
        repeat(10) {
            if (exists(groupList)) return
            val clickedBack = runCatching {
                if (exists(hasTestTag(TestTags.BACK_BUTTON))) {
                    onAllNodes(hasTestTag(TestTags.BACK_BUTTON))[0].performClick()
                    true
                } else false
            }.getOrDefault(false)

            if (!clickedBack) {
                runCatching { device.pressBack() }
            }
            // A back action may return through several nested screens. Keep trying if this
            // intermediate screen does not reach the list yet.
            runCatching { waitUntil(2_000) { exists(groupList) } }
        }
        if (!exists(groupList)) {
            relaunchApp()
        }
        check(exists(groupList)) { "Could not navigate to the group list before cleanup" }
    }

    try {
        returnToGroupList()
    } catch (error: Throwable) {
        cleanupErrors += error.message ?: error::class.java.simpleName
    }

    if (exists(groupList)) {
        for (name in groupsToDelete) {
            val groupMatcher = hasTestTag(TestTags.GROUP_ITEM) and hasText(name)
            try {
                if (!exists(groupMatcher)) {
                    runCatching { onNode(groupMatcher).performScrollTo() }
                }
                if (!exists(groupMatcher)) continue

                onNode(groupMatcher).performScrollTo().performClick()
                waitUntil(NETWORK_TIMEOUT_MS) { exists(hasContentDescription("Options")) }
                onNodeWithContentDescription("Options").performClick()
                waitUntil(5_000) { exists(hasTestTag("delete_group")) }
                onNodeWithTag("delete_group").performClick()
                waitUntil(5_000) { exists(hasText("Delete group?")) }
                onNodeWithText("Delete").performClick()
                waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) && !exists(groupMatcher) }
            } catch (error: Throwable) {
                cleanupErrors += "'$name': ${error.message ?: error::class.java.simpleName}"
                // Return to the list before attempting the next test-created group.
                runCatching { returnToGroupList() }
            }
        }
    }

    if (cleanupErrors.isNotEmpty()) {
        fail("Failed to clean up test-created groups: ${cleanupErrors.joinToString("; ")}")
    }
}

/** The text of the single node tagged [tag], e.g. "Invite code: ABC234". */
fun ComposeTestRule.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }
        .joinToString("") { it.text }

/** A name no earlier run has used, so a test never depends on data left by another test or user. */
fun uniqueName(prefix: String): String = "$prefix ${System.currentTimeMillis()}"
