package com.example.cpen321application.ui.meetup

internal fun formatTripDuration(seconds: Long): String {
    val duration = seconds.coerceAtLeast(0)
    return "${duration / 3600} h ${(duration % 3600) / 60} min ${duration % 60} sec"
}
