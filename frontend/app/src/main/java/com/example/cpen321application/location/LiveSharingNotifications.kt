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

internal class LiveSharingNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun builder(meetupId: String, background: Boolean): NotificationCompat.Builder {
        manager.createNotificationChannel(NotificationChannel("live_location", "Live location sharing", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("live_location_background", "Background sharing reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("meetfair://sharing/$meetupId"))
            .putExtra(REMINDER_MEETUP_ID, meetupId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, if (background) "live_location_background" else "live_location")
            .setSmallIcon(R.drawable.ic_meetup_reminder)
            .setContentTitle("MeetFair is sharing your live location")
            .setContentText(if (background) "Live location sharing continues in the background. Tap to return to your meetup."
                else "Location sharing is active. Open your meetup to mark arrival or pause sharing.")
            .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

    fun ongoing(meetupId: String): Notification = builder(meetupId, false).setOngoing(true).setOnlyAlertOnce(true).build()

    fun showBackground(meetupId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notice = builder(meetupId, true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setTimeoutAfter(8000).build()
        try { manager.notify(BACKGROUND_SHARING_NOTIFICATION_ID, notice) } catch (_: SecurityException) {
            // Permission can be revoked while the activity is leaving the foreground.
        }
    }

    fun dismissBackground() { manager.cancel(BACKGROUND_SHARING_NOTIFICATION_ID) }
    fun clear() { dismissBackground(); manager.cancel(SHARING_NOTIFICATION_ID) }
}
