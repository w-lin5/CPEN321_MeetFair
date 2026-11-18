package com.example.cpen321application.ui.groups

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.DeleteConfirmation

@Composable
internal fun LeaveGroupDialog(
    meetups: List<Meetup>,
    userId: String?,
    onDismiss: () -> Unit,
    onLeave: () -> Unit,
) {
    val voting = meetups.any { meetup ->
        meetup.status == MeetupStatus.VOTING && meetup.participants.any { it.userId == userId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (voting) "Cannot leave group" else "Leave group?") },
        text = {
            Text(
                if (voting) {
                    "A meetup you joined is still voting. Wait until its voting ends before leaving."
                } else {
                    "Your details in meetups that have not started voting will be deleted. Completed voting and " +
                        "Fair Credit history will remain."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (!voting) onLeave()
            }) { Text(if (voting) "OK" else "Leave") }
        },
        dismissButton = { if (!voting) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DeleteGroupDialog(data: GroupDetailData, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DeleteConfirmation(
        title = "Delete group?",
        description = "Delete ${data.group.name} and all its meetups, participation details, votes and group " +
            "Fair Credit data? This cannot be undone. External calendar events will remain.",
        votingWarning = if (data.meetups.any { it.status == MeetupStatus.VOTING }) {
            "Warning: this group still has meetups with voting in progress. Deleting it will end those votes."
        } else {
            null
        },
        onDismiss = onDismiss,
        onDelete = {
            onDismiss()
            onDelete()
        },
    )
}

@Composable
internal fun RemoveMemberDialog(
    member: GroupMember,
    meetups: List<Meetup>,
    onDismiss: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val activeParticipation = meetups.any { meetup ->
        (meetup.status == MeetupStatus.VOTING || meetup.status == MeetupStatus.FINALIZED) &&
            meetup.participants.any { it.userId == member.userId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activeParticipation) "Cannot remove member" else "Remove member?") },
        text = {
            Text(
                if (activeParticipation) {
                    "This member submitted details for a meetup whose voting has started. " +
                        "Settle that meetup before removing them."
                } else {
                    "Remove ${member.name ?: "this member"} from the group? Their meetup details and votes in " +
                        "this group will be deleted, and they will lose access."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (!activeParticipation) onRemove(member.userId)
            }) { Text(if (activeParticipation) "OK" else "Remove") }
        },
        dismissButton = {
            if (!activeParticipation) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
