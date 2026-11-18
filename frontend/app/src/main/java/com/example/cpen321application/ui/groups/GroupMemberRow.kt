package com.example.cpen321application.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealPrimary

@Composable
internal fun MemberRow(
    member: GroupMember,
    canRemove: Boolean,
    removalPending: Boolean,
    deletionSubmitting: Boolean,
    onRemove: (GroupMember) -> Unit,
) {
    val isOrganizer = member.role == GroupRole.ORGANIZER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(TestTags.MEMBER_ITEM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MemberIdentity(
            name = member.name,
            modifier = Modifier.weight(1f),
        )

        MemberTrailingAction(
            isOrganizer = isOrganizer,
            canRemove = canRemove,
            removalPending = removalPending,
            deletionSubmitting = deletionSubmitting,
            userId = member.userId,
            onRemove = { onRemove(member) },
        )
    }
}

@Composable
private fun MemberIdentity(
    name: String?,
    modifier: Modifier = Modifier,
) {
    val initial = (name ?: "U").take(1).uppercase()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = TealPrimary,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }

        Text(
            text = name ?: "Unknown",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemberTrailingAction(
    isOrganizer: Boolean,
    canRemove: Boolean,
    removalPending: Boolean,
    deletionSubmitting: Boolean,
    userId: String,
    onRemove: () -> Unit,
) {
    if (isOrganizer) {
        Surface(
            shape = CircleShape,
            color = RosePrimary.copy(alpha = 0.15f),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                text = "Organizer",
                color = RosePrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    } else if (canRemove) {
        TextButton(
            onClick = onRemove,
            enabled = !removalPending && !deletionSubmitting,
            modifier = Modifier.testTag(TestTags.removeMemberButton(userId)),
        ) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
        }
    }
}
