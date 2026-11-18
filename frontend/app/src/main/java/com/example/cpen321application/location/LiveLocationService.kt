package com.example.cpen321application.location

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.AppError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What this device is sharing right now; [meetupId] is null while it shares nothing. */
data class SharingStatus(val meetupId: String? = null, val message: String = "Not sharing on this device")

private const val EXTRA_MEETUP_ID = "meetupId"
private const val UPDATE_INTERVAL_MS = 5_000L

// A fix older than this is not sent: the server would show a stale position as live
private const val FIX_MAX_AGE_NANOS = 90_000_000_000L

// Server answers that mean the trip can no longer be shared: signed out, removed, deleted, or arrived/settled
private val SHARING_ENDED_CODES = setOf(401, 403, 404, 409)

private const val WAITING_FOR_FIX = "Waiting for a fresh location fix..."

/**
 * Foreground service that sends this device's position for one meetup every few seconds.
 * It is only started by an explicit tap in the app and keeps running while the screen is locked or
 * the app is in the background, until the user arrives, pauses sharing or loses access to the meetup.
 */
class LiveLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as MeetFairApplication).container
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private var sharingJob: Job? = null
    private var latestFix: Location? = null
    private var meetupId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_MEETUP_ID)
        val alreadySharingIt = meetupId == requestedId && sharingJob?.isActive == true
        when {
            alreadySharingIt -> Unit
            // One meetup at a time: a request without a meetup or for a second one ends the service
            requestedId == null || meetupId != null && meetupId != requestedId -> stopSelf()
            !startLocationUpdates(requestedId) -> stopSelf()
            else -> startSharing(requestedId)
        }
        return START_NOT_STICKY
    }

    private fun startSharing(id: String) {
        meetupId = id
        mutableStatus.value = SharingStatus(id, WAITING_FOR_FIX)
        val sharingUserId = container.sessionStore.currentUserId()
        // Signing out (or signing in as someone else) must stop sharing immediately
        scope.launch {
            container.sessionStore.token.collect { token ->
                if (token == null || container.sessionStore.currentUserId() != sharingUserId) stopSelf()
            }
        }
        sharingJob = scope.launch {
            while (isActive && shareOnce(id)) delay(UPDATE_INTERVAL_MS)
            stopSelf()
        }
    }

    // Enters the foreground and subscribes to location providers; false (with a status message) on failure
    private fun startLocationUpdates(id: String): Boolean = try {
        val serviceType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(
            this, SHARING_NOTIFICATION_ID, LiveSharingNotifications(this).ongoing(id), serviceType,
        )
        if (!hasLocationPermission(this)) throw SecurityException("Location permission is required")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter(locations::isProviderEnabled)
        check(providers.isNotEmpty()) { "Turn on device location and resume sharing" }
        providers.forEach { provider ->
            locations.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper())
        }
        true
    } catch (_: SecurityException) {
        mutableStatus.value = SharingStatus(message = "Location permission is required. Resume sharing to try again.")
        false
    } catch (error: IllegalStateException) {
        mutableStatus.value = SharingStatus(message = error.message.orEmpty())
        false
    }

    // One round of sharing; false means sharing must stop
    private suspend fun shareOnce(id: String): Boolean {
        if (!hasLocationPermission(this) || !LocationManagerCompat.isLocationEnabled(locations)) {
            container.tripRepository.stopSharing(id)
            mutableStatus.value = SharingStatus(message = "Location is off. Turn it on and resume sharing.")
            return false
        }
        val fix = latestFix?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < FIX_MAX_AGE_NANOS }
        return if (fix == null) checkStillSharing(id) else sendFix(id, fix)
    }

    private suspend fun sendFix(id: String, fix: Location): Boolean =
        when (val result = container.tripRepository.updateLocation(id, fix.latitude, fix.longitude, fix.accuracy)) {
            is ApiResult.Success -> {
                mutableStatus.value = SharingStatus(id, "Sharing your live location")
                true
            }
            is ApiResult.Failure -> retryUnlessEnded(id, result.error)
        }

    // Without a fresh fix there is nothing to send, but the trip may have ended on another device meanwhile
    private suspend fun checkStillSharing(id: String): Boolean =
        when (val result = container.tripRepository.list(id)) {
            is ApiResult.Success -> {
                val userId = container.sessionStore.currentUserId()
                val stillSharing = result.data.any { it.userId == userId && it.isActive && it.sharing }
                if (stillSharing) mutableStatus.value = SharingStatus(id, WAITING_FOR_FIX)
                stillSharing
            }
            is ApiResult.Failure -> retryUnlessEnded(id, result.error)
        }

    // Network hiccups are retried on the next round; a definite "no" from the server ends sharing
    private fun retryUnlessEnded(id: String, error: AppError): Boolean {
        mutableStatus.value = SharingStatus(id, "Location update failed. Retrying...")
        return error.httpCode !in SHARING_ENDED_CODES
    }

    override fun onLocationChanged(location: Location) {
        latestFix = location
    }

    @Deprecated("Required by LocationListener on Android 8 and 9")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        mutableStatus.value = SharingStatus(meetupId, "Location provider disabled. Waiting for a location fix...")
    }

    override fun onDestroy() {
        runCatching { locations.removeUpdates(this) }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        LiveSharingNotifications(this).clear()
        if (mutableStatus.value.meetupId != null) mutableStatus.value = SharingStatus()
        super.onDestroy()
    }

    companion object {
        private val mutableStatus = MutableStateFlow(SharingStatus())

        /** Observed by the UI to show whether, and for which meetup, this device is sharing. */
        val status = mutableStatus.asStateFlow()

        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        fun start(context: Context, meetupId: String) {
            val intent = Intent(context, LiveLocationService::class.java).putExtra(EXTRA_MEETUP_ID, meetupId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
    }
}
