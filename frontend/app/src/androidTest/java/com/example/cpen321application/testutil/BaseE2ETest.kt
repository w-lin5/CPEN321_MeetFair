package com.example.cpen321application.testutil

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.cpen321application.MainActivity
import com.example.cpen321application.location.LiveLocationService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Base test class for all E2E feature tests.
 * Manages test isolation, stopping location services, resetting network states,
 * and deleting all created groups in tearDown.
 */
@RunWith(AndroidJUnit4::class)
abstract class BaseE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    protected val createdGroupNames = mutableListOf<String>()

    @Before
    open fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveLocationService.stop(targetContext)
        runCatching { device.executeShellCommand("cmd connectivity airplane-mode disable") }
        createdGroupNames.clear()
        compose.assertSignedInOnGroupList()
    }

    @After
    open fun tearDown() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        LiveLocationService.stop(targetContext)
        runCatching { device.executeShellCommand("cmd connectivity airplane-mode disable") }
        compose.cleanupCreatedGroups(createdGroupNames)
    }
}
