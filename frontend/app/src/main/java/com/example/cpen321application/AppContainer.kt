package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.data.ApiClient
import com.example.cpen321application.data.AuthInterceptor
import com.example.cpen321application.data.AuthRepository
import com.example.cpen321application.data.GoogleSignInHelper
import com.example.cpen321application.data.SessionStore
import okhttp3.OkHttpClient
import com.example.cpen321application.data.VotingRepository

class AppContainer(context: Context) {
    // Shared session storage and GOogle Sign-in configuration for application
    val sessionStore = SessionStore(context.applicationContext)
    val googleSignInHelper = GoogleSignInHelper(BuildConfig.GOOGLE_CLIENT_ID)

    private val api = ApiClient(
        BuildConfig.API_BASE_URL,
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(sessionStore)).build(),
    )

    val authRepository = AuthRepository(api, sessionStore)
    val votingRepository = VotingRepository(api)
}
