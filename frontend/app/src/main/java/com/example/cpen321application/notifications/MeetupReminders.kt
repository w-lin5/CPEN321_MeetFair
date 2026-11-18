package com.example.cpen321application.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cpen321application.MainActivity
import com.example.cpen321application.R
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import java.time.Instant

/** Intent extra naming the meetup a reminder (or a tapped notification) is about. */
const val REMINDER_MEETUP_ID = "reminder_meetup_id"

private const val CHANNEL = "meetup_start"
private const val PREFS = "meetup_reminders"
private const val TIME_KEY_PREFIX = "time:"

// A reminder is still worth showing (or retrying) for this long after the meetup time
internal const val REMINDER_WINDOW_MS = 60 * 60 * 1000L
private const val RETRY_DELAY_MS = 60_000L
private const val RESTORE_DELAY_MS = 1_000L

private fun meetupTimeMillis(meetup: Meetup): Long? =
    runCatching { Instant.parse(meetup.scheduledAt).toEpochMilli() }.getOrNull()

private fun isParticipant(meetup: Meetup, userId: String?): Boolean =
    userId != null && meetup.participants.any { it.userId == userId }

/** When to show the reminder: only participants of a meetup with a confirmed destination get one. */
internal fun reminderTime(meetup: Meetup, userId: String?): Long? {
    val confirmed = meetup.status == MeetupStatus.FINALIZED && meetup.winner != null
    return if (isParticipant(meetup, userId) && confirmed) meetupTimeMillis(meetup) else null
}

/**
 * When to wake up for a meetup. An alarm is registered before the meetup is finalized too, because the
 * organizer may finalize it while this device is closed; the receiver checks [reminderTime] before showing.
 */
internal fun scheduledReminderTime(meetup: Meetup, userId: String?): Long? {
    val pending = meetup.status != MeetupStatus.SETTLED
    return if (isParticipant(meetup, userId) && pending) meetupTimeMillis(meetup) else null
}

/** Device-scheduled reminders for the start of a meetup; no background socket or location sharing is started. */
class MeetupReminders(private val context: Context) {
    // Per meetup id: the user the alarm was set for, and under "time:<id>" when it is due
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Sets, moves or cancels the alarm so that it matches the meetup as the server reports it. */
    fun update(meetup: Meetup, userId: String?) {
        val dueAt = scheduledReminderTime(meetup, userId)
        if (dueAt == null) {
            cancel(meetup.id)
            return
        }
        // A refresh at (or after) the due instant must not cancel the receiver's pending check or retry
        if (dueAt <= System.currentTimeMillis()) return
        prefs.edit().putString(meetup.id, userId).putLong(TIME_KEY_PREFIX + meetup.id, dueAt).apply()
        schedule(meetup.id, dueAt)
    }

    fun cancel(meetupId: String) {
        alarms.cancel(alarmIntent(meetupId))
        prefs.edit().remove(meetupId).remove(TIME_KEY_PREFIX + meetupId).apply()
        notifications.cancel(meetupId, 0)
    }

    /** Removes every alarm and shown reminder; called when the session ends. */
    fun clear() {
        pending().keys.toList().forEach(::cancel)
        notifications.activeNotifications
            .filter { it.notification.channelId == CHANNEL }
            .forEach { notifications.cancel(it.tag, it.id) }
    }

    /** Meetup id -> the user its alarm was set for. */
    fun pending(): Map<String, String> =
        prefs.all.mapNotNull { (meetupId, userId) -> (userId as? String)?.let { meetupId to it } }.toMap()

    /** Alarms do not survive a reboot or a clock change: set the recent ones again and drop the stale ones. */
    fun restore() {
        val now = System.currentTimeMillis()
        pending().keys.forEach { meetupId ->
            val dueAt = dueTime(meetupId)
            if (dueAt > now - REMINDER_WINDOW_MS) {
                schedule(meetupId, maxOf(dueAt, now + RESTORE_DELAY_MS))
            } else {
                cancel(meetupId)
            }
        }
    }

    /** Tries again shortly (e.g. the server was unreachable), until the meetup time is too far in the past. */
    fun retry(meetupId: String) {
        val now = System.currentTimeMillis()
        if (now - dueTime(meetupId) < REMINDER_WINDOW_MS) schedule(meetupId, now + RETRY_DELAY_MS) else cancel(meetupId)
    }

    fun show(meetup: Meetup) {
        if (!canPostNotifications()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Meetup start reminders", NotificationManager.IMPORTANCE_HIGH),
        )
        val message = "Your meetup time has arrived. Open the meetup and tap 'I am leaving' to share your " +
            "live location."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_meetup_reminder)
            .setContentTitle("Time for ${meetup.title}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openMeetup(meetup.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            notifications.notify(meetup.id, 0, notification)
        } catch (_: SecurityException) {
            // Notification permission may be revoked between the check and posting.
        }
    }

    private fun dueTime(meetupId: String): Long = prefs.getLong(TIME_KEY_PREFIX + meetupId, 0)

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // Exact when Android allows it; otherwise the reminder may arrive a little late
    private fun schedule(meetupId: String, dueAt: Long) {
        val intent = alarmIntent(meetupId)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, intent)
            }
        } catch (_: SecurityException) {
            // The exact-alarm permission was revoked after the check
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, intent)
        }
    }

    // The data URI makes each meetup's alarm a distinct PendingIntent
    private fun alarmIntent(meetupId: String): PendingIntent {
        val intent = Intent(context, MeetupReminderReceiver::class.java)
            .setData(Uri.parse("meetfair://reminder/$meetupId"))
            .putExtra(REMINDER_MEETUP_ID, meetupId)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openMeetup(meetupId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("meetfair://meetup/$meetupId"))
            .putExtra(REMINDER_MEETUP_ID, meetupId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
