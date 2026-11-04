package com.example.cpen321application.ui.groups

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.launch

private val CopyIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f)
        lineTo(4f, 1f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        lineTo(2f, 17f)
        horizontalLineTo(4f)
        lineTo(4f, 3f)
        horizontalLineTo(16f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        lineTo(8f, 5f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        lineTo(6f, 21f)
        curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
        horizontalLineTo(19f)
        curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
        lineTo(21f, 7f)
        curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
        close()
        moveTo(19f, 21f)
        lineTo(8f, 21f)
        lineTo(8f, 7f)
        horizontalLineTo(19f)
        verticalLineTo(21f)
        close()
    }.build()

/** In-flight / failed state of the group screen's leave, delete and remove-member actions. */
internal data class GroupBusyState(
    val removal: MemberRemovalState,
    val deletion: FormState<Unit>,
    val leaving: FormState<Unit>,
)

/** Callbacks that open the group screen's confirmation dialogs. */
internal data class GroupInfoActions(
    val onRemove: (GroupMember) -> Unit,
    val onDelete: () -> Unit,
    val onLeave: () -> Unit,
)

/** Back button, options menu (delete for the organizer, leave for members), group name and action errors. */
@Composable
internal fun GroupHeader(
    groupName: String,
    isOrganizer: Boolean,
    busy: GroupBusyState,
    actions: GroupInfoActions,
    onBack: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
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

            GroupOptionsMenu(isOrganizer, busy, actions)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "GROUP",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = groupName,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
        }

        busy.leaving.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        busy.deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun GroupOptionsMenu(isOrganizer: Boolean, busy: GroupBusyState, actions: GroupInfoActions) {
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
            if (isOrganizer) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (busy.deletion.submitting) "Deleting..." else "Delete group",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        actions.onDelete()
                    },
                    enabled = !busy.deletion.submitting && !busy.removal.pending,
                    modifier = Modifier.testTag("delete_group"),
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (busy.leaving.submitting) "Leaving..." else "Leave group",
                        )
                    },
                    onClick = {
                        showMenu = false
                        actions.onLeave()
                    },
                    enabled = !busy.leaving.submitting,
                    modifier = Modifier.testTag("leave_group"),
                )
            }
        }
    }
}

/** The group's invite code with copy and share buttons. */
@Composable
internal fun InviteCodeCard(inviteCode: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()

    MeetFairCard(
        containerColor = TealContainer,
        borderWidth = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "INVITE CODE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary.copy(alpha = 0.7f),
                    letterSpacing = 1.2.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = inviteCode,
                    modifier = Modifier.testTag(TestTags.INVITE_CODE_TEXT),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                    ),
                    color = TealPrimary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InviteIconButton(CopyIcon, "Copy invite code") {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invite code", inviteCode)))
                        notify("Invite code copied")
                    }
                }
                InviteIconButton(Icons.Default.Share, "Share invite code") { shareInviteCode(context, inviteCode) }
            }
        }
    }
}

private fun shareInviteCode(context: Context, inviteCode: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, "Join my MeetFair group with invite code: $inviteCode")
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

@Composable
private fun InviteIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = TealPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "Members" title with the member count and the remove-member progress / error. */
@Composable
internal fun MembersHeader(memberCount: Int, removal: MemberRemovalState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Members",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = TealPrimary,
            )
            Text(
                text = "$memberCount",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (removal.pending) Text("Removing member...")
        removal.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
    }
}

@Composable
internal fun GroupFairCreditButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("group_fair_credit_button"),
    ) {
        Text(
            text = "View Group's Fair Credits",
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )
    }
}

@Composable
internal fun NewMeetupButton(modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = RosePrimary,
            contentColor = Color.White,
        ),
        modifier = modifier.testTag(TestTags.NEW_MEETUP_BUTTON),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = Color.White,
            )
            Text(
                text = "New meetup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
