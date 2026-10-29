package com.example.cpen321application.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cpen321application.MainActivity
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val REMINDER_MEETUP_ID = "reminder_meetup_id"
private const val CHANNEL = "meetup_start"

internal fun reminderTime(meetup: Meetup, userId: String?): Long? {
    if (userId == null || meetup.participants.none { it.userId == userId }) return null
    if (meetup.status != MeetupStatus.FINALIZED || meetup.winner == null) return null
    return runCatching { Instant.parse(meetup.scheduledAt).toEpochMilli() }.getOrNull()
}

internal fun scheduledReminderTime(meetup: Meetup, userId: String?): Long? {
    if (userId == null || meetup.participants.none { it.userId == userId } || meetup.status == MeetupStatus.SETTLED) return null
    return runCatching { Instant.parse(meetup.scheduledAt).toEpochMilli() }.getOrNull()
}

/** Device-scheduled system reminders; no background socket or location sharing is started. */
class MeetupReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("meetup_reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun alarm(id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, MeetupReminderReceiver::class.java).setData(Uri.parse("meetfair://reminder/$id"))
            .putExtra(REMINDER_MEETUP_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun update(meetup: Meetup, userId: String?) {
        // Register before finalization too: the organizer may finalize while this device is closed.
        // The receiver must verify FINALIZED and a winner before showing anything.
        val at = scheduledReminderTime(meetup, userId)
        if (at == null) { cancel(meetup.id); return }
        // A foreground refresh at the due instant must not cancel a pending validation/retry.
        if (at <= System.currentTimeMillis()) return
        prefs.edit().putString(meetup.id, userId).putLong("time:${meetup.id}", at).apply()
        schedule(meetup.id, at)
    }

    private fun schedule(id: String, at: Long) {
        val intent = alarm(id)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun cancel(id: String) {
        alarms.cancel(alarm(id))
        prefs.edit().remove(id).remove("time:$id").apply()
        context.getSystemService(NotificationManager::class.java).cancel(id, 0)
    }

    fun clear() {
        pending().keys.toList().forEach(::cancel)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    fun pending(): Map<String, String> = prefs.all.mapNotNull { (id, user) ->
        (user as? String)?.let { id to it }
    }.toMap()

    fun restore() {
        val now = System.currentTimeMillis()
        pending().keys.forEach { id ->
            val at = prefs.getLong("time:$id", 0)
            if (at > now - 60 * 60 * 1000) schedule(id, maxOf(at, now + 1000)) else cancel(id)
        }
    }

    fun retry(id: String) {
        if (System.currentTimeMillis() - prefs.getLong("time:$id", 0) < 60 * 60 * 1000) {
            schedule(id, System.currentTimeMillis() + 60_000)
        } else cancel(id)
    }

    fun show(meetup: Meetup) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Meetup start reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setData(Uri.parse("meetfair://meetup/${meetup.id}"))
                .putExtra(REMINDER_MEETUP_ID, meetup.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val message = "Your meetup time has arrived. Open the meetup and tap 'I am leaving' to share your live location."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.example.cpen321application.R.drawable.ic_meetup_reminder)
            .setContentTitle("Time for ${meetup.title}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { manager.notify(meetup.id, 0, notification) } catch (_: SecurityException) {
            // Notification permission may be revoked between the check and posting.
        }
    }
}

class MeetupReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as MeetFairApplication).container
                val reminders = container.meetupReminders
                if (intent.getStringExtra(REMINDER_MEETUP_ID) == null) {
                    reminders.restore()
                    return@launch
                }
                val user = container.sessionStore.currentUserId()
                val ids = intent.getStringExtra(REMINDER_MEETUP_ID)?.let { listOf(it) }
                    ?: reminders.pending().keys.toList()
                for (id in ids) {
                    if (user == null || reminders.pending()[id] != user) { reminders.cancel(id); continue }
                    when (val response = container.reminderMeetupRepository.get(id)) {
                        is ApiResult.Success -> {
                            if (container.sessionStore.currentUserId() != user || container.sessionStore.currentToken() == null) {
                                reminders.cancel(id)
                                continue
                            }
                            val at = reminderTime(response.data, user)
                            if (at != null && at <= System.currentTimeMillis() &&
                                System.currentTimeMillis() - at < 60 * 60 * 1000) {
                                reminders.cancel(id)
                                reminders.show(response.data)
                            } else reminders.update(response.data, user)
                        }
                        is ApiResult.Failure -> if (response.error.httpCode == null || response.error.httpCode!! >= 500)
                            reminders.retry(id) else reminders.cancel(id)
                    }
                }
            } finally { result.finish() }
        }
    }
}
