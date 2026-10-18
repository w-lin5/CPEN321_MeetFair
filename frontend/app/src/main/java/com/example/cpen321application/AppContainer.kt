package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.data.ApiClient
import com.example.cpen321application.data.AuthInterceptor
import com.example.cpen321application.data.AuthRepository
import com.example.cpen321application.data.GoogleSignInHelper
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.SessionStore
import com.example.cpen321application.data.VotingRepository
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    // Shared session storage and GOogle Sign-in configuration for application
    val sessionStore = SessionStore(context.applicationContext)
    val googleSignInHelper = GoogleSignInHelper(BuildConfig.GOOGLE_CLIENT_ID)

    private val api = ApiClient(
        BuildConfig.API_BASE_URL,
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionStore)).build(),
    )

    val authRepository = AuthRepository(api, sessionStore)
    val groupRepository = GroupRepository(api)
    val meetupRepository = MeetupRepository(api)
    val votingRepository = VotingRepository(api)
}
