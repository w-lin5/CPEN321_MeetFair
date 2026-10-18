package com.example.cpen321application.data

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

sealed interface CalendarAuthResult {
    data class Success(val accessToken: String) : CalendarAuthResult
    data class NeedsConsent(val result: AuthorizationResult) : CalendarAuthResult
    data class Failure(val error: Throwable) : CalendarAuthResult
}

/** Gets a short-lived Google Calendar access token on the device; the backend uses it once and never stores it. */
class CalendarAuthorizer {
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
