package com.example.cpen321application.ui.meetup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.StatusAmber
import com.example.cpen321application.ui.theme.StatusAmberLight
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary

private val VotingIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Voting",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 3f)
        horizontalLineTo(5f)
        curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
        verticalLineTo(19f)
        curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
        horizontalLineTo(19f)
        curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
        verticalLineTo(5f)
        curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
        close()
        moveTo(9f, 17f)
        lineTo(4f, 12f)
        lineTo(5.41f, 10.59f)
        lineTo(9f, 14.17f)
        lineTo(18.59f, 4.58f)
        lineTo(20f, 6f)
        lineTo(9f, 17f)
        close()
    }.build()

private val SettledIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Settled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
        curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
        curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
        close()
        moveTo(10f, 17f)
        lineTo(5f, 12f)
        lineTo(6.41f, 10.59f)
        lineTo(10f, 14.17f)
        lineTo(17.59f, 6.58f)
        lineTo(19f, 8f)
        lineTo(10f, 17f)
        close()
    }.build()

/** Visual specifications for a meetup's status badge. */
data class MeetupStatusSpec(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val backgroundColor: Color,
)

/** Returns the display specification for the given [MeetupStatus]. */
fun getStatusSpec(status: MeetupStatus): MeetupStatusSpec = when (status) {
    MeetupStatus.SETUP -> MeetupStatusSpec(
        label = "Setup",
        icon = Icons.Default.Edit,
        color = StatusAmber,
        backgroundColor = StatusAmberLight,
    )
    MeetupStatus.VOTING -> MeetupStatusSpec(
        label = "Voting",
        icon = VotingIcon,
        color = RosePrimary,
        backgroundColor = RosePrimary.copy(alpha = 0.15f),
    )
    MeetupStatus.FINALIZED -> MeetupStatusSpec(
        label = "Finalized",
        icon = Icons.Default.CheckCircle,
        color = StatusGreen,
        backgroundColor = StatusGreenLight,
    )
    MeetupStatus.SETTLED -> MeetupStatusSpec(
        label = "Settled",
        icon = SettledIcon,
        color = TealPrimary,
        backgroundColor = TealContainer,
    )
}

