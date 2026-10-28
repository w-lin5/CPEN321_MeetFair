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
import com.example.cpen321application.data.VotingRepository
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val meetupReminders = com.example.cpen321application.notifications.MeetupReminders(context.applicationContext)
    // Shared session storage and GOogle Sign-in configuration for application
    val sessionStore = SessionStore(context.applicationContext)
    val googleSignInHelper = GoogleSignInHelper(BuildConfig.GOOGLE_CLIENT_ID)
    val calendarAuthorizer = CalendarAuthorizer()

    private val api = ApiClient(
        BuildConfig.API_BASE_URL,
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionStore)).build(),
    )

    val authRepository = AuthRepository(api, sessionStore)
    val groupRepository = GroupRepository(api)
    val meetupRepository = MeetupRepository(api, meetupReminders, sessionStore)
    // A bounded request for alarm receivers, without changing the stored alarm during validation.
    val reminderMeetupRepository = MeetupRepository(ApiClient(BuildConfig.API_BASE_URL,
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionStore))
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()))
    val profileRepository = ProfileRepository(api)
    val fairnessRepository = FairnessRepository(api)
    val votingRepository = VotingRepository(api)
    val realtimeClient = RealtimeClient(BuildConfig.API_BASE_URL, sessionStore)
}
