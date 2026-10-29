package com.example.cpen321application.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.cpen321application.MainActivity
import com.example.cpen321application.MeetFairApplication
import com.example.cpen321application.R
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.notifications.REMINDER_MEETUP_ID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharingStatus(val meetupId: String? = null, val message: String = "Not sharing on this device")

/** Starts only from an explicit foreground user action; continues during screen lock/background. */
class LiveLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as MeetFairApplication).container
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private var task: Job? = null
    private var sessionTask: Job? = null
    private var latest: Location? = null
    private var meetupId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("meetupId") ?: run { stopSelf(); return START_NOT_STICKY }
        if (meetupId == id && task?.isActive == true) return START_NOT_STICKY
        if (meetupId != null && meetupId != id) { stopSelf(); return START_NOT_STICKY }
        meetupId = id
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("live_location", "Live location sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .putExtra(REMINDER_MEETUP_ID, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, "live_location").setSmallIcon(R.drawable.ic_meetup_reminder)
            .setContentTitle("MeetFair is sharing your location")
            .setContentText("Open your meetup to mark arrival or stop sharing.")
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        try {
            val serviceType = if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, 4001, notice, serviceType)
            if (!hasLocationPermission(this)) throw SecurityException("Location permission is required")
            var requested = false
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 5000L, 0f, this, Looper.getMainLooper())
                    requested = true
                }
            }
            if (!requested) throw IllegalStateException("Turn on device location and resume sharing")
        } catch (_: SecurityException) {
            mutableStatus.value = SharingStatus(message = "Location permission is required. Resume sharing to try again.")
            stopSelf(); return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            mutableStatus.value = SharingStatus(message = error.message.orEmpty())
            stopSelf(); return START_NOT_STICKY
        }
        val sharingUser = container.sessionStore.currentUserId()
        sessionTask = scope.launch {
            container.sessionStore.token.collect { if (it == null || container.sessionStore.currentUserId() != sharingUser) stopSelf() }
        }
        task = scope.launch {
            while (isActive) {
                if (!hasLocationPermission(this@LiveLocationService) || !androidx.core.location.LocationManagerCompat.isLocationEnabled(locations)) {
                    container.tripRepository.stop(id)
                    mutableStatus.value = SharingStatus(message = "Location is off. Turn it on and resume sharing.")
                    stopSelf(); break
                }
                val point = latest?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 90_000_000_000L }
                val response = if (point == null) container.tripRepository.list(id)
                    else container.tripRepository.location(id, point.latitude, point.longitude, point.accuracy)
                when (response) {
                    is ApiResult.Failure -> {
                        mutableStatus.value = SharingStatus(id, "Location update failed. Retrying...")
                        if (response.error.httpCode in listOf(401, 403, 404, 409)) { stopSelf(); break }
                    }
                    is ApiResult.Success -> {
                        if (point == null) {
                            val rows = response.data as? List<*>
                            val own = rows?.filterIsInstance<com.example.cpen321application.data.MeetupTrip>()
                                ?.find { it.userId == container.sessionStore.currentUserId() }
                            if (own == null || own.status != "ACTIVE" || !own.sharing) { stopSelf(); break }
                        }
                        mutableStatus.value = SharingStatus(id, if (point == null) "Waiting for a fresh location fix..." else "Sharing your live location")
                    }
                }
                delay(5000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) { latest = location }
    @Deprecated("Required by LocationListener on Android 8 and 9")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) {
        mutableStatus.value = SharingStatus(meetupId, "Location provider disabled. Waiting for a location fix...")
    }
    override fun onDestroy() {
        runCatching { locations.removeUpdates(this) }
        scope.cancel()
        if (mutableStatus.value.meetupId != null) mutableStatus.value = SharingStatus()
        super.onDestroy()
    }

    companion object {
        private val mutableStatus = MutableStateFlow(SharingStatus())
        val status = mutableStatus.asStateFlow()
        fun hasLocationPermission(context: Context): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        fun start(context: Context, meetupId: String) {
            ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java).putExtra("meetupId", meetupId))
        }
        fun stop(context: Context) { context.stopService(Intent(context, LiveLocationService::class.java)) }
    }
}
