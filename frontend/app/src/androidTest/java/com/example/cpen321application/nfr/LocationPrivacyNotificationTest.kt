package com.example.cpen321application.nfr

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.location.LiveSharingNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real Android notification must clearly disclose sharing without putting coordinates on the lock screen. */
class LocationPrivacyNotificationTest {
    @Test fun ongoingNotificationDisclosesSharingAndUsesPrivateVisibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notice = LiveSharingNotifications(context).ongoing("privacy-test-meetup")
        assertEquals("MeetFair is sharing your live location", notice.extras.getString(Notification.EXTRA_TITLE))
        val text = notice.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("Location sharing is active"))
        assertTrue(text.contains("arrival or pause sharing"))
        assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        assertNotNull(notice.contentIntent)
    }
}
