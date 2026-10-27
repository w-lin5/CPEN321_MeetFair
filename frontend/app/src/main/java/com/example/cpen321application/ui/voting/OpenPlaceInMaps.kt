package com.example.cpen321application.ui.voting

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.cpen321application.model.PlaceCandidate

/** Universal Maps URL uses the Place ID to distinguish branches with the same name. */
internal fun openPlaceInMaps(context: Context, place: PlaceCandidate) {
    val uri = Uri.parse("https://www.google.com/maps/search/").buildUpon()
        .appendQueryParameter("api", "1")
        .appendQueryParameter("query", "${place.lat},${place.lng}")
        .apply {
            if (place.placeId.isNotBlank() && !place.placeId.startsWith("manual-")) {
                appendQueryParameter("query_place_id", place.placeId)
            }
        }
        .build()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(Intent(intent).setPackage("com.google.android.apps.maps"))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No maps app or browser is available to open this place.", Toast.LENGTH_LONG).show()
        }
    }
}
