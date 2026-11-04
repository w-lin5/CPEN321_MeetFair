package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.TealPrimary

/** Confirmation dialog the meetup screen is currently showing. */
internal enum class MeetupDialog { NONE, SETTLE, LEAVE, DELETE }

/** Back button plus the options menu (leave / delete) for the meetup screen. */
@Composable
internal fun MeetupTopBar(
    vm: MeetupViewModel,
    canLeave: Boolean,
    canDelete: Boolean,
    onBack: () -> Unit,
    onSelect: (MeetupDialog) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag(TestTags.BACK_BUTTON),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TealPrimary,
            )
        }

        if (canLeave || canDelete) MeetupOptionsMenu(vm, canLeave, canDelete, onSelect)
    }
}

@Composable
private fun MeetupOptionsMenu(
    vm: MeetupViewModel,
    canLeave: Boolean,
    canDelete: Boolean,
    onSelect: (MeetupDialog) -> Unit,
) {
    val leaving by vm.leaving.collectAsState()
    val deletion by vm.deletion.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = TealPrimary,
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            if (canLeave) {
                DropdownMenuItem(
                    text = { Text(if (leaving.submitting) "Leaving..." else "Leave meetup") },
                    onClick = { showMenu = false; onSelect(MeetupDialog.LEAVE) },
                    enabled = !leaving.submitting && !deletion.submitting,
                    modifier = Modifier.testTag("leave_meetup"),
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (deletion.submitting) "Deleting..." else "Delete meetup",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { showMenu = false; onSelect(MeetupDialog.DELETE) },
                    enabled = !deletion.submitting,
                    modifier = Modifier.testTag("delete_meetup"),
                )
            }
        }
    }
}

/** Status chip, title, organizer line and any leave/delete errors or settlement notice. */
@Composable
internal fun MeetupHeader(meetup: Meetup, vm: MeetupViewModel, isOrganizer: Boolean) {
    val organizerName by vm.organizerName.collectAsState()
    val leaving by vm.leaving.collectAsState()
    val deletion by vm.deletion.collectAsState()

    MeetupStatusChip(meetup.status)

    Text(
        text = meetup.title,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.ExtraBold,
        color = TealPrimary,
        modifier = Modifier.testTag(TestTags.MEETUP_TITLE_TEXT),
    )

    val organizerDisplay = if (isOrganizer) "You" else (organizerName ?: "Organizer")
    Text(
        text = "Meeting Organizer: $organizerDisplay",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("meetup_organizer_text"),
    )

    leaving.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (meetup.status == MeetupStatus.SETTLED) {
        Text("Fair Credit settled. View updated balances and history in the group's Fair Credit page.")
    }
}

@Composable
private fun MeetupStatusChip(status: MeetupStatus) {
    val spec = getStatusSpec(status)
    Surface(
        shape = CircleShape,
        color = spec.backgroundColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag(TestTags.MEETUP_STATUS_TEXT),
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.color,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = spec.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = spec.color,
            )
            Text(
                text = "Status: ${status.name}",
                fontSize = 0.sp,
                color = Color.Transparent,
            )
        }
    }
}

/** Shows the settle / leave / delete confirmation selected by [dialog], if any. */
@Composable
internal fun MeetupConfirmDialog(dialog: MeetupDialog, meetup: Meetup, vm: MeetupViewModel, onDismiss: () -> Unit) {
    when (dialog) {
        MeetupDialog.NONE -> Unit
        MeetupDialog.SETTLE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Settle Fair Credit?") },
            text = { Text("Finish this meetup and update everyone's Fair Credit. Completed trips use actual travel time; members who never started tracking use estimated travel time. All started trips must arrive first. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { onDismiss(); vm.settle() }) { Text("Settle") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        MeetupDialog.LEAVE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Leave meetup?") },
            text = { Text("Your starting location and preferences for this meetup will be deleted and will no longer affect place searches. You can join again by entering your details before voting starts.") },
            confirmButton = { TextButton(onClick = { onDismiss(); vm.leaveMeetup() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        MeetupDialog.DELETE -> DeleteConfirmation(
            title = "Delete meetup?",
            description = "Delete ${meetup.title}, its participation details, candidates and votes? This cannot be undone. Settled Fair Credit and external calendar events will remain.",
            votingWarning = if (meetup.status == MeetupStatus.VOTING)
                "Warning: this meetup currently has voting in progress. Deleting it will end the vote." else null,
            onDismiss = onDismiss,
            onDelete = { onDismiss(); vm.deleteMeetup() },
        )
    }
}
