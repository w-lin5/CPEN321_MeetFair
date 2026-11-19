package com.example.cpen321application.testutil

import android.util.Log
import android.app.AlertDialog
import android.app.Activity
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
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Best-effort cleanup for groups created by an E2E test. Cleanup errors are logged so they never
 * replace or fail the test's original result in tearDown.
 */
fun ComposeTestRule.cleanupCreatedGroups(names: Iterable<String>) {
    try {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
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
                runCatching { waitUntil(2_000) { exists(groupList) } }
            }
            if (!exists(groupList)) {
                runCatching { relaunchApp() }
            }
        }

        runCatching { returnToGroupList() }

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
                    Log.w("ComposeE2E", "Best-effort cleanup failed for group '$name': ${error.message}")
                    runCatching { returnToGroupList() }
                }
            }
        }
    } catch (e: Throwable) {
        Log.w("ComposeE2E", "Best-effort cleanup failed: ${e.message}")
    }
}

/** The text of the single node tagged [tag], e.g. "Invite code: ABC234". */
fun ComposeTestRule.textOf(tag: String): String =
    onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }
        .joinToString("") { it.text }

/** A name no earlier run has used, so a test never depends on data left by another test or user. */
fun uniqueName(prefix: String): String = "$prefix ${System.currentTimeMillis()}"

/** Skips multi-user scenarios unless the E2E runner was started with its multi-user option. */
fun assumeMultiUserRun() {
    assumeTrue(
        "Run with instrumentation argument multi-user=true and a second signed-in device",
        InstrumentationRegistry.getArguments().getString("multi-user") == "true",
    )
}

/** Returns the invite code captured from a newly created group, with a useful setup error if absent. */
fun requireCreatedInviteCode(inviteCode: String?): String =
    checkNotNull(inviteCode) { "The created group's invite code was not captured" }

/** Shows the standard manual Device B instruction dialog used by multi-user E2E scenarios. */
fun Activity.manualStep(message: String) {
    val latch = CountDownLatch(1)
    var dialog: AlertDialog? = null
    runOnUiThread {
        dialog = AlertDialog.Builder(this)
            .setTitle("Manual User B step")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> latch.countDown() }
            .setCancelable(false)
            .show()
    }
    if (!latch.await(5, TimeUnit.MINUTES)) {
        runOnUiThread { dialog?.dismiss() }
        throw AssertionError("Timed out waiting for User B. Manual step: $message")
    }
}
