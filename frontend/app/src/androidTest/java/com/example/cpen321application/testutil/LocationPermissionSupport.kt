package com.example.cpen321application.testutil

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.platform.app.InstrumentationRegistry

private val LOCATION_ALLOW_OPTIONS = listOf("While using the app", "Only this time", "Allow", "ALLOW", "While Using the App")
private val LOCATION_DENY_OPTIONS = listOf("Don't allow", "Deny", "DENY", "Don’t allow")

fun grantLocationPermissionIfRequested() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    UiDevice.getInstance(instrumentation).tapPermissionOptions(LOCATION_ALLOW_OPTIONS)
}

fun UiDevice.waitForLocationPermissionPrompt(timeoutMs: Long = 10_000): Boolean =
    waitForPermissionOptions(LOCATION_ALLOW_OPTIONS, timeoutMs)

fun UiDevice.denyLocationPermissionIfRequested(timeoutMs: Long = 10_000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val choice = findPermissionOption(LOCATION_DENY_OPTIONS)
        if (choice != null && runCatching { choice.click() }.isSuccess) return true
        Thread.sleep(250)
    }
    return false
}

private fun UiDevice.tapPermissionOptions(options: List<String>) {
    repeat(5) {
        val choice = findPermissionOption(options)
        if (choice != null) {
            runCatching { choice.click() }
            Thread.sleep(1000)
        } else {
            Thread.sleep(500)
        }
    }
}

private fun UiDevice.waitForPermissionOptions(options: List<String>, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (findPermissionOption(options) != null) return true
        Thread.sleep(250)
    }
    return false
}

private fun UiDevice.findPermissionOption(options: List<String>) =
    options.firstNotNullOfOrNull { label -> findObject(UiSelector().text(label)).takeIf { it.exists() } }
