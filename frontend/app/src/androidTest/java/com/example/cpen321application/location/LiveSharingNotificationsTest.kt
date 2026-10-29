package com.example.cpen321application.location

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LiveSharingNotificationsTest {
    @Test fun backgroundAlertCanDismissWithoutRemovingOngoingSharingNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val notifications = LiveSharingNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val ongoing = notifications.ongoing("test-meetup")
            assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
            manager.notify(SHARING_NOTIFICATION_ID, ongoing)
            notifications.showBackground("test-meetup")
            await { manager.activeNotifications.any { it.id == BACKGROUND_SHARING_NOTIFICATION_ID } }
            val alert = manager.activeNotifications.single { it.id == BACKGROUND_SHARING_NOTIFICATION_ID }.notification
            assertEquals("MeetFair is sharing your live location", alert.extras.getString("android.title"))
            assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(alert.channelId).importance)
            assertNotNull(alert.contentIntent)
            notifications.dismissBackground()
            await { manager.activeNotifications.none { it.id == BACKGROUND_SHARING_NOTIFICATION_ID } }
            assertTrue(manager.activeNotifications.any { it.id == SHARING_NOTIFICATION_ID })
            notifications.clear()
            await { manager.activeNotifications.none { it.id in listOf(SHARING_NOTIFICATION_ID, BACKGROUND_SHARING_NOTIFICATION_ID) } }
        } finally { notifications.clear() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(condition())
    }
}
