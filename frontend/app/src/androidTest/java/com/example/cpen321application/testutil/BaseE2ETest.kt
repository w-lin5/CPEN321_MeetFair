package com.example.cpen321application.testutil

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        LiveLocationService.stop(targetContext)
        restoreNetwork()
        createdGroupNames.clear()
        compose.assertSignedInOnGroupList()
    }

    @After
    open fun tearDown() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        restoreNetwork()
        LiveLocationService.stop(targetContext)
        compose.cleanupCreatedGroups(createdGroupNames)
    }

    protected fun restoreNetwork(timeoutMs: Long = 15_000L) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        runCatching { device.executeShellCommand("cmd connectivity airplane-mode disable") }
        val cm = targetContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (hasInternetConnection(cm)) {
            Thread.sleep(500)
            return
        }

        awaitInternetConnection(cm, timeoutMs)
    }

    private fun hasInternetConnection(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun awaitInternetConnection(manager: ConnectivityManager, timeoutMs: Long) {
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    latch.countDown()
                }
            }
            override fun onAvailable(network: android.net.Network) {
                latch.countDown()
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            if (hasInternetConnection(manager)) latch.countDown()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            Thread.sleep(1000)
        } catch (_: Exception) {
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}
