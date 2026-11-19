package com.example.cpen321application.testutil

import android.app.AlertDialog
import android.app.Activity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.StaleObjectException
import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// E2E tests talk to the real back end; a cold server or a slow emulator network can take several seconds
const val NETWORK_TIMEOUT_MS = 20_000L

/** Repeats a UI check until it succeeds, the timeout expires, or it throws an unrelated exception. */
fun retryUntil(
    timeoutMs: Long,
    intervalMs: Long = 500,
    condition: () -> Boolean,
): Boolean {
    require(timeoutMs > 0) { "timeoutMs must be positive" }
    require(intervalMs > 0) { "intervalMs must be positive" }

    val deadline = System.currentTimeMillis() + timeoutMs
    do {
        try {
            if (condition()) return true
        } catch (_: StaleObjectException) {
            // UIAutomator can invalidate a node while the app is rendering; fetch it again next attempt.
        }

        val remainingMs = deadline - System.currentTimeMillis()
        if (remainingMs > 0) {
            try {
                Thread.sleep(minOf(intervalMs, remainingMs))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    } while (System.currentTimeMillis() < deadline)

    return false
}

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
