package com.example.cpen321application.ui.meetup

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.Black), pathBuilder = path)
        .build()

internal val LockIcon = icon("Lock") {
    moveTo(18f, 8f); horizontalLineTo(17f); verticalLineTo(6f); curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
    curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f); verticalLineTo(8f); horizontalLineTo(6f)
    curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f); verticalLineTo(20f); curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
    horizontalLineTo(18f); curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f); verticalLineTo(10f)
    curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f); close()
    moveTo(9f, 6f); curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f); curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
    verticalLineTo(8f); horizontalLineTo(9f); verticalLineTo(6f); close()
}

internal val DirectionsArrowIcon = icon("DirectionsArrow") {
    moveTo(21f, 3f); lineTo(3f, 10.53f); verticalLineTo(11.51f); lineTo(9.84f, 14.16f)
    lineTo(12.49f, 21f); horizontalLineTo(13.47f); lineTo(21f, 3f); close()
}

internal val BarChartIcon = icon("BarChart") {
    moveTo(5f, 9.2f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(5f); verticalLineTo(9.2f); close()
    moveTo(10.6f, 5f); horizontalLineTo(13.4f); verticalLineTo(19f); horizontalLineTo(10.6f); verticalLineTo(5f)
    close()
    moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(16.2f); verticalLineTo(13f)
    close()
}

internal val TimerIcon = icon("Timer") {
    moveTo(15f, 1f); horizontalLineTo(9f); verticalLineTo(3f); horizontalLineTo(15f); verticalLineTo(1f); close()
    moveTo(11f, 14f); horizontalLineTo(13f); verticalLineTo(9f); horizontalLineTo(11f); verticalLineTo(14f); close()
    moveTo(19.03f, 7.39f); lineTo(20.45f, 5.97f); curveTo(20.02f, 5.46f, 19.55f, 5.01f, 19.04f, 4.6f)
    lineTo(17.62f, 6.02f); curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f); curveTo(7.03f, 4f, 3f, 8.03f, 3f, 13f)
    curveTo(3f, 17.97f, 7.02f, 22f, 12f, 22f); curveTo(16.97f, 22f, 21f, 17.97f, 21f, 13f)
    curveTo(21f, 10.88f, 20.26f, 8.93f, 19.03f, 7.39f); close()
    moveTo(12f, 20f); curveTo(8.13f, 20f, 5f, 16.87f, 5f, 13f); curveTo(5f, 9.13f, 8.13f, 6f, 12f, 6f)
    curveTo(15.87f, 6f, 19f, 9.13f, 19f, 13f); curveTo(19f, 16.87f, 15.87f, 20f, 12f, 20f); close()
}

internal val FlagIcon = icon("Flag") {
    moveTo(14.4f, 6f); lineTo(14f, 4f); horizontalLineTo(5f); verticalLineTo(21f); horizontalLineTo(7f)
    verticalLineTo(14f); horizontalLineTo(12.6f); lineTo(13f, 16f); horizontalLineTo(20f); verticalLineTo(6f)
    horizontalLineTo(14.4f); close()
}

internal val SwapHorizontalIcon = icon("SwapHorizontal") {
    moveTo(6.99f, 11f); lineTo(3f, 15f); lineTo(6.99f, 19f); verticalLineTo(16f); horizontalLineTo(14f)
    verticalLineTo(14f); horizontalLineTo(6.99f); verticalLineTo(11f); close()
    moveTo(17.01f, 13f); lineTo(21f, 9f); lineTo(17.01f, 5f); verticalLineTo(8f); horizontalLineTo(10f)
    verticalLineTo(10f); horizontalLineTo(17.01f); verticalLineTo(13f); close()
}
