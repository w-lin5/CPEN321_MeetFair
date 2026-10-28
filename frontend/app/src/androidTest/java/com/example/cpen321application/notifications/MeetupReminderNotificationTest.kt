package com.example.cpen321application.notifications

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.PlaceCandidate
import org.junit.Assert.*
import org.junit.Test

class MeetupReminderNotificationTest {
    @Test fun systemNotificationHasMeetupContentAndCanBeCancelled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        val reminders = MeetupReminders(context)
        val id = "notification-instrumentation-test"
        val meetup = Meetup(id, "group", "organizer", "Reminder test", "2030-01-01T12:00:00Z",
            MeetupStatus.FINALIZED, emptyList(), PlaceCandidate("place", "Cafe", "", 49.0, -123.0))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            reminders.show(meetup)
            val posted = manager.activeNotifications.single { it.tag == id }.notification
            assertEquals("Time for Reminder test", posted.extras.getString("android.title"))
            assertTrue(posted.extras.getCharSequence("android.bigText").toString().contains("I am leaving"))
            assertNotNull(posted.contentIntent)
            reminders.cancel(id)
            // NotificationManager processes removal asynchronously in the system service.
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (manager.activeNotifications.any { it.tag == id } && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertFalse(manager.activeNotifications.any { it.tag == id })
        } finally { reminders.cancel(id) }
    }
}
