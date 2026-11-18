package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.data.ApiClient
import com.example.cpen321application.data.AuthInterceptor
import com.example.cpen321application.data.AuthRepository
import com.example.cpen321application.data.CalendarAuthorizer
import com.example.cpen321application.data.FairnessRepository
import com.example.cpen321application.data.GoogleSignInHelper
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.ProfileRepository
import com.example.cpen321application.data.RealtimeClient
import com.example.cpen321application.data.SessionStore
import com.example.cpen321application.data.TripRepository
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.location.TripStarter
import com.example.cpen321application.notifications.MeetupReminders
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

// Limits for one request. The whole call is capped so a screen never spins for long before offering a retry
private const val CONNECT_TIMEOUT_SECONDS = 5L
private const val READ_TIMEOUT_SECONDS = 8L
private const val WRITE_TIMEOUT_SECONDS = 5L
private const val CALL_TIMEOUT_SECONDS = 10L

// An alarm receiver only gets a few seconds from Android before it is stopped
private const val REMINDER_CALL_TIMEOUT_SECONDS = 8L

/** Manual dependency injection: builds every data source and repository once per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val meetupReminders = MeetupReminders(appContext)

    // Reminders belong to the signed-in user, so they are cancelled when the session ends
    val sessionStore = SessionStore(appContext, onCleared = meetupReminders::clear)
    val googleSignInHelper = GoogleSignInHelper(BuildConfig.GOOGLE_CLIENT_ID)
    val calendarAuthorizer = CalendarAuthorizer()

    private val api = ApiClient(
        BuildConfig.API_BASE_URL,
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionStore))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
    )

    // For the reminder alarm: a tighter time limit, and reading a meetup must not reschedule the alarm being handled
    private val reminderApi = ApiClient(
        BuildConfig.API_BASE_URL,
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionStore))
            .callTimeout(REMINDER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
    )

    val authRepository = AuthRepository(api, sessionStore)
    val groupRepository = GroupRepository(api)
    val meetupRepository = MeetupRepository(api, meetupReminders, sessionStore)
    val reminderMeetupRepository = MeetupRepository(reminderApi)
    val profileRepository = ProfileRepository(api)
    val fairnessRepository = FairnessRepository(api)
    val votingRepository = VotingRepository(api)
    val tripRepository = TripRepository(api)
    val tripStarter = TripStarter(appContext, tripRepository)
    val realtimeClient = RealtimeClient(BuildConfig.API_BASE_URL, sessionStore)
}
