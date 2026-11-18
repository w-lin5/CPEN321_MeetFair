package com.example.cpen321application.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.cpen321application.MainActivity
import com.example.cpen321application.R
import com.example.cpen321application.notifications.REMINDER_MEETUP_ID

internal const val SHARING_NOTIFICATION_ID = 4001
internal const val BACKGROUND_SHARING_NOTIFICATION_ID = 4002

private const val ONGOING_CHANNEL = "live_location"
private const val BACKGROUND_CHANNEL = "live_location_background"
private const val OPEN_MEETUP_REQUEST_CODE = 1

// The background reminder removes itself; the ongoing notification stays for as long as sharing does
private const val BACKGROUND_REMINDER_TIMEOUT_MS = 8_000L

/** The notifications that keep location sharing visible to the user while it runs. */
internal class LiveSharingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Shown for as long as the foreground service shares the location. */
    fun ongoing(meetupId: String): Notification =
        builder(
            meetupId,
            ONGOING_CHANNEL,
            text = "Location sharing is active. Open your meetup to mark arrival or pause sharing.",
        ).setOngoing(true).setOnlyAlertOnce(true).build()

    /** A heads-up reminder when the app goes to the background while still sharing. */
    fun showBackground(meetupId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val reminder = builder(
            meetupId,
            BACKGROUND_CHANNEL,
            text = "Live location sharing continues in the background. Tap to return to your meetup.",
        ).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(BACKGROUND_REMINDER_TIMEOUT_MS)
            .build()
        try {
            manager.notify(BACKGROUND_SHARING_NOTIFICATION_ID, reminder)
        } catch (_: SecurityException) {
            // Permission can be revoked while the activity is leaving the foreground.
        }
    }

    fun dismissBackground() {
        manager.cancel(BACKGROUND_SHARING_NOTIFICATION_ID)
    }

    fun clear() {
        dismissBackground()
        manager.cancel(SHARING_NOTIFICATION_ID)
    }

    private fun builder(meetupId: String, channel: String, text: String): NotificationCompat.Builder {
        createChannels()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_meetup_reminder)
            .setContentTitle("MeetFair is sharing your live location")
            .setContentText(text)
            .setContentIntent(openMeetup(meetupId))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    // Creating a channel that already exists does nothing
    private fun createChannels() {
        val ongoing = NotificationChannel(ONGOING_CHANNEL, "Live location sharing", NotificationManager.IMPORTANCE_LOW)
        val background = NotificationChannel(
            BACKGROUND_CHANNEL,
            "Background sharing reminders",
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannels(listOf(ongoing, background))
    }

    private fun openMeetup(meetupId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("meetfair://sharing/$meetupId"))
            .putExtra(REMINDER_MEETUP_ID, meetupId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            OPEN_MEETUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
