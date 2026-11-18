package com.example.cpen321application.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.meetup.MeetupStatusSpec
import com.example.cpen321application.ui.meetup.getStatusSpec
import com.example.cpen321application.ui.meetup.isoToDisplay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private data class MeetupDateParts(val month: String, val day: String, val time: String)

private fun parseMeetupDateParts(iso: String): MeetupDateParts = try {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val month = zdt.format(DateTimeFormatter.ofPattern("MMM", Locale.US)).uppercase()
    val day = zdt.format(DateTimeFormatter.ofPattern("dd", Locale.US))
    val time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    MeetupDateParts(month, day, time)
} catch (_: DateTimeParseException) {
    // An unreadable date must not show a made-up one
    MeetupDateParts(month = "", day = "?", time = isoToDisplay(iso))
}

@Composable
private fun MeetupDateBadge(
    parts: MeetupDateParts,
    spec: MeetupStatusSpec,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = spec.backgroundColor,
        modifier = Modifier.size(width = 56.dp, height = 56.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = parts.month,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = spec.color.copy(alpha = 0.8f),
            )
            Text(
                text = parts.day,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = spec.color,
            )
        }
    }
}

@Composable
private fun MeetupStatusChip(
    spec: MeetupStatusSpec,
) {
    Surface(
        shape = CircleShape,
        color = spec.backgroundColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.color,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = spec.color,
            )
        }
    }
}

@Composable
private fun MeetupDetails(
    title: String,
    time: String,
    organizerDisplay: String,
    spec: MeetupStatusSpec,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Organizer: $organizerDisplay",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MeetupStatusChip(spec = spec)
    }
}

@Composable
internal fun MeetupRow(
    meetup: Meetup,
    members: List<GroupMember>,
    currentUserId: String?,
    onOpen: (String) -> Unit,
) {
    val parts = parseMeetupDateParts(meetup.scheduledAt)
    val spec = getStatusSpec(meetup.status)
    val organizer = members.find { it.userId == meetup.organizerId }
    val organizerDisplay = if (meetup.organizerId == currentUserId) "You" else (organizer?.name ?: "Organizer")

    MeetFairCard(
        modifier = Modifier
            .clickable { onOpen(meetup.id) }
            .testTag(TestTags.MEETUP_ITEM),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                MeetupDateBadge(parts = parts, spec = spec)
                MeetupDetails(
                    title = meetup.title,
                    time = parts.time,
                    organizerDisplay = organizerDisplay,
                    spec = spec,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open meetup",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
