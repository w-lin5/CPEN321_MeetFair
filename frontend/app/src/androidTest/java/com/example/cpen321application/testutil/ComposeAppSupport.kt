package com.example.cpen321application.testutil

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.ui.common.TestTags
import org.junit.Assert.fail

fun ComposeTestRule.relaunchApp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
    val signIn = hasTestTag(TestTags.SIGN_IN_BUTTON)
    runCatching { waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) || exists(signIn) } }
}

fun ComposeTestRule.assertSignedInOnGroupList() {
    val groupList = hasTestTag(TestTags.CREATE_GROUP_BUTTON)
    val signIn = hasTestTag(TestTags.SIGN_IN_BUTTON)
    if (!exists(groupList) && !exists(signIn)) relaunchApp()
    waitUntil(NETWORK_TIMEOUT_MS) { exists(groupList) || exists(signIn) }
    if (!exists(groupList)) {
        fail("Sign in to MeetFair on the device before running the E2E tests (see documentation/Testing.md)")
    }
}
