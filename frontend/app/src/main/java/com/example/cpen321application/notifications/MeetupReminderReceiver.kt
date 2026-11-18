package com.example.cpen321application.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.cpen321application.AppContainer
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.AppError
import com.example.cpen321application.model.Meetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val HTTP_SERVER_ERROR = 500

/**
 * Runs when a meetup's alarm fires (the intent names the meetup), and after a reboot or clock change
 * (no meetup: the alarms are restored). Before showing a reminder it asks the server whether the meetup
 * is still on, because it may have been deleted, moved or left since the alarm was set.
 */
class MeetupReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as MeetFairApplication).container
                val meetupId = intent.getStringExtra(REMINDER_MEETUP_ID)
                if (meetupId == null) container.meetupReminders.restore() else remind(container, meetupId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun remind(container: AppContainer, meetupId: String) {
        val reminders = container.meetupReminders
        val userId = container.sessionStore.currentUserId()
        // The alarm belongs to whoever was signed in when it was set
        if (userId == null || reminders.pending()[meetupId] != userId) {
            reminders.cancel(meetupId)
            return
        }
        when (val result = container.reminderMeetupRepository.get(meetupId)) {
            is ApiResult.Success -> onMeetupLoaded(container, result.data, userId)
            is ApiResult.Failure ->
                if (isTemporary(result.error)) reminders.retry(meetupId) else reminders.cancel(meetupId)
        }
    }

    private fun onMeetupLoaded(container: AppContainer, meetup: Meetup, userId: String) {
        val reminders = container.meetupReminders
        val session = container.sessionStore
        val now = System.currentTimeMillis()
        val dueAt = reminderTime(meetup, userId)
        when {
            // Signed out (or switched accounts) while the request was in flight
            session.currentUserId() != userId || session.currentToken() == null -> reminders.cancel(meetup.id)
            dueAt != null && dueAt <= now && now - dueAt < REMINDER_WINDOW_MS -> {
                reminders.cancel(meetup.id)
                reminders.show(meetup)
            }
            // Not due (the time moved) or not confirmed yet: keep the alarm in step with the server
            else -> reminders.update(meetup, userId)
        }
    }

    // No answer or a server error may pass; a 4xx means the meetup is gone or no longer ours
    private fun isTemporary(error: AppError): Boolean =
        error.httpCode == null || error.httpCode >= HTTP_SERVER_ERROR
}
