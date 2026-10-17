package com.example.cpen321application.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.tasks.await

sealed interface CalendarAuthResult {
    data class Success(val accessToken: String) : CalendarAuthResult
    data class NeedsConsent(val result: AuthorizationResult) : CalendarAuthResult
    data class Failure(val error: Throwable) : CalendarAuthResult
}

class GoogleSignInHelper(private val serverClientId: String) {
    // Needs an Activity context so Credential Manager can show its sheet
    suspend fun getIdToken(activityContext: Context): Result<String> {
        // Configure Google sign in to request an ID token for backend auth
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()
        
        return try {
            val credential = CredentialManager.create(activityContext)
                .getCredential(activityContext, request).credential
            
            // Validate credential type before extracting google ID token
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Result.success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.failure(IllegalStateException("Unexpected credential type"))
            }
        } catch (e: GetCredentialException) {
            Result.failure(e)
        } catch (e: GoogleIdTokenParsingException) {
            Result.failure(e)
        }
    }

    /**
     * Requests Google Calendar OAuth authorization.
     * Returns [CalendarAuthResult.Success] if an access token is immediately available,
     * [CalendarAuthResult.NeedsConsent] if user consent is required (with pendingIntent),
     * or [CalendarAuthResult.Failure] on error.
     */
    suspend fun requestCalendarAuthorization(activityContext: Context): CalendarAuthResult {
        val calendarScope = Scope("https://www.googleapis.com/auth/calendar.events")
        val authRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(calendarScope))
            .build()

        return try {
            val result = Identity.getAuthorizationClient(activityContext)
                .authorize(authRequest)
                .await()

            val token = result.accessToken
            when {
                token != null -> CalendarAuthResult.Success(token)
                result.hasResolution() -> CalendarAuthResult.NeedsConsent(result)
                else -> CalendarAuthResult.Failure(
                    IllegalStateException("No access token returned and no resolution available")
                )
            }
        } catch (e: Exception) {
            CalendarAuthResult.Failure(e)
        }
    }
}
