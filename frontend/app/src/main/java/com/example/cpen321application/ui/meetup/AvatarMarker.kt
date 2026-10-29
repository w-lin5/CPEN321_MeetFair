package com.example.cpen321application.ui.meetup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.*
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
internal fun AvatarMarker(userId: String, name: String, photoUrl: String?, position: LatLng) {
    var photo by remember(photoUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photoUrl) {
        photo = withContext(Dispatchers.IO) {
            runCatching {
                val uri = URI(photoUrl ?: return@runCatching null)
                if (uri.scheme != "https") return@runCatching null
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() < 512 * 1024) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, 512 * 1024 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                } finally { connection.disconnect() }
            }.getOrNull()
        }
    }
    val bitmap = remember(photo, name, userId) {
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            canvas.drawCircle(48f, 48f, 47f, paint)
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(48f, 48f, 41f, Path.Direction.CW) })
            val image = photo
            if (image != null) {
                val side = minOf(image.width, image.height)
                val source = android.graphics.Rect((image.width - side) / 2, (image.height - side) / 2,
                    (image.width + side) / 2, (image.height + side) / 2)
                canvas.drawBitmap(image, source, android.graphics.Rect(7, 7, 89, 89), paint)
            } else {
                paint.color = Color.rgb(45, 94, 156)
                canvas.drawCircle(48f, 48f, 41f, paint)
                paint.color = Color.WHITE; paint.textSize = 34f; paint.textAlign = Paint.Align.CENTER
                canvas.drawText(name.trim().take(1).uppercase().ifBlank { "?" }, 48f, 60f, paint)
            }
            canvas.restore()
        }
    }
    val marker = rememberMarkerState(position = position)
    SideEffect { marker.position = position }
    Marker(state = marker, title = name, icon = BitmapDescriptorFactory.fromBitmap(bitmap), anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f))
}
