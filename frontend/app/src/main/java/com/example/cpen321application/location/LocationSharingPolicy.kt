package com.example.cpen321application.location

import java.util.concurrent.TimeUnit

internal object LocationSharingPolicy {
    const val UPDATE_INTERVAL_MS = 5_000L
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val MIN_MOVEMENT_METERS = 10f
    const val PROVIDER_MIN_DISTANCE_METERS = 0f
    val MAX_FIX_AGE_NANOS: Long = TimeUnit.SECONDS.toNanos(90)

    fun shouldUpload(lastUploadMs: Long?, nowMs: Long, distanceMeters: Float, accuracyImproved: Boolean): Boolean {
        if (lastUploadMs == null) return true
        val elapsed = nowMs - lastUploadMs
        return elapsed >= UPDATE_INTERVAL_MS &&
            (elapsed >= HEARTBEAT_INTERVAL_MS || distanceMeters >= MIN_MOVEMENT_METERS || accuracyImproved)
    }
}
