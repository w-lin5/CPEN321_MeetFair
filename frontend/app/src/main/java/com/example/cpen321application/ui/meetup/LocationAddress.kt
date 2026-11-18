package com.example.cpen321application.ui.meetup

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val GEOCODER_TIMEOUT_MS = 5_000L

// Matches the server's limit for a start location's label
private const val ADDRESS_MAX_LENGTH = 120

/** Best-effort reverse geocoding; a missing address must not prevent selecting a point. */
internal suspend fun locationAddress(context: Context, lat: Double, lng: Double): String? {
    if (!Geocoder.isPresent()) return null
    return try {
        withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
            val geocoder = Geocoder(context.applicationContext, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { continuation ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    })
                }
            } else {
                // Before Android 13 only this blocking (now deprecated) overload exists
                withContext(Dispatchers.IO) { geocoder.getFromLocation(lat, lng, 1).orEmpty() }
            }
            addresses.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }?.take(ADDRESS_MAX_LENGTH)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
