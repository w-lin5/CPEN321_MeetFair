package com.example.cpen321application.ui.meetup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AvatarMarker(userId: String, name: String, photoUrl: String?, position: LatLng) {
    var photo by remember(photoUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photoUrl) {
        photo = withContext(Dispatchers.IO) { downloadAvatar(photoUrl) }
    }
    val bitmap = remember(photo, name, userId) { avatarBitmap(photo, name) }
    val marker = rememberMarkerState(position = position)
    SideEffect { marker.position = position }
    Marker(
        state = marker,
        title = name,
        icon = BitmapDescriptorFactory.fromBitmap(bitmap),
        anchor = Offset(MARKER_CENTER, MARKER_CENTER),
    )
}

// The marker sits on the member's position with its centre, not its bottom edge
private const val MARKER_CENTER = 0.5f

// Geometry of the round avatar, in pixels of its square bitmap
private const val AVATAR_SIZE = 96
private const val AVATAR_CENTER = AVATAR_SIZE / 2f
private const val BORDER_RADIUS = 47f
private const val PHOTO_RADIUS = 41f
private const val PHOTO_INSET = 7
private const val INITIAL_TEXT_SIZE = 34f
private const val INITIAL_BASELINE = 60f
private val PLACEHOLDER_BLUE = Color.rgb(45, 94, 156)

// A white-bordered circle showing the member's photo, or their initial while there is none
private fun avatarBitmap(photo: Bitmap?, name: String): Bitmap =
    Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(AVATAR_CENTER, AVATAR_CENTER, BORDER_RADIUS, paint)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(AVATAR_CENTER, AVATAR_CENTER, PHOTO_RADIUS, Path.Direction.CW) })
        if (photo != null) {
            // The largest centred square of the photo fills the circle
            val side = minOf(photo.width, photo.height)
            val left = (photo.width - side) / 2
            val top = (photo.height - side) / 2
            val target = Rect(PHOTO_INSET, PHOTO_INSET, AVATAR_SIZE - PHOTO_INSET, AVATAR_SIZE - PHOTO_INSET)
            canvas.drawBitmap(photo, Rect(left, top, left + side, top + side), target, paint)
        } else {
            paint.color = PLACEHOLDER_BLUE
            canvas.drawCircle(AVATAR_CENTER, AVATAR_CENTER, PHOTO_RADIUS, paint)
            paint.color = Color.WHITE
            paint.textSize = INITIAL_TEXT_SIZE
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(name.trim().take(1).uppercase().ifBlank { "?" }, AVATAR_CENTER, INITIAL_BASELINE, paint)
        }
        canvas.restore()
    }

// Only https photos are fetched; any failure (bad URL, network, decode) just falls back to the initial
private fun downloadAvatar(photoUrl: String?): Bitmap? = runCatching {
    photoUrl?.let { URI(it) }?.takeIf { it.scheme == "https" }?.let(::readAvatar)
}.getOrNull()

private fun readAvatar(uri: URI): Bitmap? {
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val bytes = connection.inputStream.use(::readUpTo512Kb)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
    } finally { connection.disconnect() }
}

private fun readUpTo512Kb(stream: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() < 512 * 1024) {
        val count = stream.read(buffer, 0, minOf(buffer.size, 512 * 1024 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
