package com.example.cpen321application.ui.meetup

internal fun formatTripDuration(seconds: Long): String {
    val duration = seconds.coerceAtLeast(0)
    val hours = duration / 3600
    val minutes = (duration % 3600) / 60
    val secs = duration % 60
    return when {
        hours > 0 -> "$hours h $minutes min $secs sec"
        minutes > 0 -> "$minutes min $secs sec"
        else -> "$secs sec"
    }
}
