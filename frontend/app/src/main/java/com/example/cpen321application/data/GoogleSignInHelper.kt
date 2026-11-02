package com.example.cpen321application.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

class GoogleSignInHelper(private val serverClientId: String) {
    // Needs an Activity context so Credential Manager can show its sheet
    suspend fun getIdToken(activityContext: Context): Result<String> {
        if (serverClientId.isBlank()) {
            return Result.failure(IllegalStateException(
                "Google sign-in is not configured. Set GOOGLE_CLIENT_ID in frontend/local.properties and rebuild the app."
            ))
        }

        // Configure Google sign in to request an ID token for backend auth
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
                .build()
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
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}
