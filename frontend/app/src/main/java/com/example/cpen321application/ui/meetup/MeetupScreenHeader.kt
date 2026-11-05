package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.TealPrimary

private val PersonIcon = icon("Person") {
    moveTo(12f, 12f); curveTo(14.21f, 12f, 16f, 10.21f, 16f, 8f); curveTo(16f, 5.79f, 14.21f, 4f, 12f, 4f)
    curveTo(9.79f, 4f, 8f, 5.79f, 8f, 8f); curveTo(8f, 10.21f, 9.79f, 12f, 12f, 12f); close()
    moveTo(12f, 6f); curveTo(13.1f, 6f, 14f, 6.9f, 14f, 8f); curveTo(14f, 9.1f, 13.1f, 10f, 12f, 10f)
    curveTo(10.9f, 10f, 10f, 9.1f, 10f, 8f); curveTo(10f, 6.9f, 10.9f, 6f, 12f, 6f); close()
    moveTo(12f, 14f); curveTo(9.33f, 14f, 4f, 15.34f, 4f, 18f); verticalLineTo(20f); horizontalLineTo(20f)
    verticalLineTo(18f); curveTo(20f, 15.34f, 14.67f, 14f, 12f, 14f); close()
    moveTo(6f, 18f); curveTo(6.22f, 17.28f, 9.31f, 15.9f, 12f, 15.9f); curveTo(14.7f, 15.9f, 17.79f, 17.28f, 18f, 18f)
    horizontalLineTo(6f); close()
}

// Dialogs and menu

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun MeetupDialogs(
    confirm: Confirm,
    meetup: Meetup,
    onDismiss: () -> Unit,
    onConfirm: (Confirm) -> Unit,
) {
    when (confirm) {
        Confirm.None -> Unit
        Confirm.Settle -> ConfirmDialog(
            title = "Settle Fair Credit?",
            text = "Finish this meetup and update everyone's Fair Credit. Completed trips use actual travel time; members who never started tracking use estimated travel time. All started trips must arrive first. This cannot be undone.",
            confirmLabel = "Settle",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(confirm) },
        )
        Confirm.Leave -> ConfirmDialog(
            title = "Leave meetup?",
            text = "Your starting location and preferences for this meetup will be deleted and will no longer affect place searches. You can join again by entering your details before voting starts.",
            confirmLabel = "Leave",
            onDismiss = onDismiss,
            onConfirm = { onConfirm(confirm) },
        )
        Confirm.Delete -> DeleteConfirmation(
            title = "Delete meetup?",
            description = "Delete ${meetup.title}, its participation details, candidates and votes? This cannot be undone. Settled Fair Credit and external calendar events will remain.",
            votingWarning = if (meetup.status == MeetupStatus.VOTING) "Warning: this meetup currently has voting in progress. Deleting it will end the vote." else null,
            onDismiss = onDismiss,
            onDelete = { onConfirm(confirm) },
        )
    }
}

@Composable
internal fun MeetupOptionsMenu(
    expanded: Boolean,
    ui: MeetupUi,
    running: MeetupAction?,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (ui.meetup.status == MeetupStatus.SETUP && ui.submitted) {
            DropdownMenuItem(
                text = { Text(if (running == MeetupAction.LEAVE) "Leaving..." else "Leave meetup") },
                onClick = onLeave,
                enabled = running == null,
                modifier = Modifier.testTag("leave_meetup"),
            )
        }
        if (ui.isOrganizer) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (running == MeetupAction.DELETE) "Deleting..." else "Delete meetup",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onDelete,
                enabled = running == null,
                modifier = Modifier.testTag("delete_meetup"),
            )
        }
    }
}

@Composable
internal fun MeetupHeader(
    ui: MeetupUi,
    onBack: () -> Unit,
    onMenuClick: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    val meetup = ui.meetup
    val spec = getStatusSpec(meetup.status)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.BACK_BUTTON)) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TealPrimary)
            }
            if (ui.showOptionsMenu) {
                Box {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options", tint = TealPrimary)
                    }
                    menuContent()
                }
            }
        }
        Surface(shape = CircleShape, color = spec.backgroundColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag(TestTags.MEETUP_STATUS_TEXT),
            ) {
                Icon(imageVector = spec.icon, contentDescription = null, tint = spec.color, modifier = Modifier.size(14.dp))
                Text(text = spec.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = spec.color)
                Text(text = "Status: ${meetup.status.name}", fontSize = 0.sp, color = Color.Transparent)
            }
        }
        Text(
            text = meetup.title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = TealPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(TestTags.MEETUP_TITLE_TEXT),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = PersonIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Organized by ${ui.organizerDisplay}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("meetup_organizer_text"),
            )
        }
    }
}
