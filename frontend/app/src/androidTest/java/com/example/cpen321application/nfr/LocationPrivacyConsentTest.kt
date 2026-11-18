package com.example.cpen321application.nfr

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.AppContainer
import com.example.cpen321application.location.LiveLocationService
import com.example.cpen321application.ui.meetup.StartTripButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Requirements 3.7.2 / 4.7.2: exercise the real consent UI with deterministic permission denial. */
class LocationPrivacyConsentTest {
    @get:Rule val compose = createComposeRule()

    private class DeniedPermissions : ActivityResultRegistry() {
        val requested = mutableListOf<String>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            val permissions = when (input) {
                is String -> arrayOf<String>(input)
                is Array<*> -> input.filterIsInstance<String>().toTypedArray()
                else -> error("Unexpected permission request")
            }
            requested.addAll(permissions)
            dispatchResult(requestCode, Activity.RESULT_OK, Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    IntArray(permissions.size) { PackageManager.PERMISSION_DENIED }))
        }
    }

    private fun content(registry: DeniedPermissions, onStarted: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deniedContext = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission in setOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) {
                    PackageManager.PERMISSION_DENIED
                } else super.checkPermission(permission, pid, uid)
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val container = AppContainer(context)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides deniedContext,
                LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { StartTripButton(container, "privacy-test-meetup", onStarted = onStarted) }
            }
        }
    }

    @Test fun viewingAndCancellingNeverRequestPermissionOrStartSharing() {
        val registry = DeniedPermissions()
        var navigated = false
        assertNull(LiveLocationService.status.value.meetupId)
        content(registry) { navigated = true }
        compose.runOnIdle { assertTrue(registry.requested.isEmpty()) }
        compose.onNodeWithTag("start_trip_button").performClick()
        compose.onNodeWithText("Share your live location?").assertIsDisplayed()
        compose.onNodeWithText("Members of this group can see your latest location", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(registry.requested.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Share your live location?").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(registry.requested.isEmpty())
            assertFalse(navigated)
            assertNull(LiveLocationService.status.value.meetupId)
        }
    }

    @Test fun denyingLocationAfterConfirmationDoesNotStartSharingOrNavigate() {
        val registry = DeniedPermissions()
        var navigated = false
        assertNull(LiveLocationService.status.value.meetupId)
        content(registry) { navigated = true }
        compose.onNodeWithTag("start_trip_button").performClick()
        compose.runOnIdle { assertTrue(registry.requested.isEmpty()) }
        compose.onNodeWithText("Share and start trip").performClick()
        compose.onNodeWithText("Location permission is required to share your trip. No trip was started.").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(registry.requested.containsAll(listOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
            assertFalse(navigated)
            assertNull(LiveLocationService.status.value.meetupId)
        }
    }
}
