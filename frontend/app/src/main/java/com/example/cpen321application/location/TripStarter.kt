package com.example.cpen321application.location

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.TripRepository

/**
 * Starts (or resumes) sharing the user's trip to a meetup: checks the device, tells the server, then
 * starts the foreground service that sends the location. Location permission is requested by the UI first.
 */
class TripStarter(private val context: Context, private val trips: TripRepository) {
    /**
     * Returns null once sharing has started, otherwise the message to show the user.
     * [appInForeground] is asked right before the service starts (see [startService]).
     */
    suspend fun start(meetupId: String, appInForeground: () -> Boolean): String? {
        val sharingMeetupId = LiveLocationService.status.value.meetupId
        if (sharingMeetupId != null && sharingMeetupId != meetupId) {
            return "Stop sharing your current trip before starting another one."
        }
        if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
            return "Turn on Location in Android settings, then try again."
        }
        return when (val result = trips.start(meetupId)) {
            is ApiResult.Failure -> result.error.message
            is ApiResult.Success -> startService(meetupId, appInForeground)
        }
    }

    // Android only lets a visible app start a location service. If the user left during the request,
    // the server is told that nothing is being shared, so the trip does not look live to the group
    private suspend fun startService(meetupId: String, appInForeground: () -> Boolean): String? = try {
        check(appInForeground())
        LiveLocationService.start(context, meetupId)
        null
    } catch (_: IllegalStateException) {
        trips.stopSharing(meetupId)
        "Keep the app open and try resuming location sharing."
    } catch (_: SecurityException) {
        trips.stopSharing(meetupId)
        "Allow location access while using the app, then try again."
    }
}
