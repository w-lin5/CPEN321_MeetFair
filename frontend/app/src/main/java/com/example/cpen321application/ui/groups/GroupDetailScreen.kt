package com.example.cpen321application.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.TealPrimary

@Composable
fun GroupDetailScreen(
    container: AppContainer,
    groupId: String,
    onBack: () -> Unit,
    onNewMeetup: () -> Unit,
    onOpenMeetup: (String) -> Unit,
    onOpenFairness: () -> Unit = {},
) {
    val vm: GroupDetailViewModel = screenViewModel(GroupDetailViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    val removal by vm.removal.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentUserId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(onBack = onBack)
    GroupDetailEffects(container, groupId, vm, onBack)

    val busy = GroupBusyState(removal, deletion, leaving)
    val actions = GroupInfoActions(
        onRemove = { selectedMemberId = it.userId },
        onDelete = { confirmDelete = true },
        onLeave = { confirmLeave = true },
    )
    val navigation = GroupDetailNavigation(onBack, onNewMeetup, onOpenMeetup, onOpenFairness)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Dimens.ScreenPadding),
    ) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { data ->
            GroupInfo(data, currentUserId, busy, actions, navigation)

            if (confirmLeave) {
                LeaveGroupDialog(
                    data.meetups,
                    currentUserId,
                    onDismiss = { confirmLeave = false },
                    onLeave = vm::leaveGroup,
                )
            }
            if (confirmDelete) {
                DeleteGroupDialog(data, onDismiss = { confirmDelete = false }, onDelete = vm::deleteGroup)
            }
            data.group.members.firstOrNull { it.userId == selectedMemberId }?.let { selected ->
                RemoveMemberDialog(
                    member = selected,
                    meetups = data.meetups,
                    onDismiss = { selectedMemberId = null },
                    onRemove = vm::removeMember,
                )
            }
        }
    }
}

@Composable
private fun GroupDetailEffects(
    container: AppContainer,
    groupId: String,
    vm: GroupDetailViewModel,
    onBack: () -> Unit,
) {
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    val missing by vm.missing.collectAsState()
    val notify = LocalNotify.current
    var exited by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(container, groupId) {
        container.realtimeClient.groupEvents().collect { event ->
            vm.onGroupEvent(event)
            when {
                event is GroupEvent.Deleted && event.groupId == groupId && !vm.deletion.value.submitting &&
                    vm.deletion.value.result == null && !exited -> {
                    exited = true
                    notify("This group has been deleted by its organizer.")
                    onBack()
                }
                event is GroupEvent.MeetupDeleted && event.groupId == groupId && event.notifyParticipant ->
                    notify("A meetup was deleted. The list has been updated.")
            }
        }
    }
    LaunchedEffect(deletion.result) {
        if (deletion.result != null && !exited) { exited = true; notify("Group deleted."); onBack() }
    }
    LaunchedEffect(missing) {
        if (missing && !exited) { exited = true; notify("This group is no longer available."); onBack() }
    }
    LaunchedEffect(leaving.result) {
        if (leaving.result != null && !exited) { exited = true; onBack() }
    }
}

@Composable
private fun GroupInfo(
    data: GroupDetailData,
    currentUserId: String?,
    busy: GroupBusyState,
    actions: GroupInfoActions,
    navigation: GroupDetailNavigation,
) {
    val isOrganizer = data.group.organizerId == currentUserId

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                GroupHeader(data.group.name, data.hasTrackingStage, isOrganizer, busy, actions, navigation.onBack)
            }
            item { InviteCodeCard(data.group.inviteCode) }
            item { MembersHeader(data.group.members.size, busy.removal) }

            items(data.group.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    canRemove = isOrganizer,
                    removalPending = busy.removal.pending,
                    deletionSubmitting = busy.deletion.submitting,
                    onRemove = actions.onRemove,
                )
            }
            item { GroupFairCreditButton(onClick = navigation.onOpenFairness) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Meetups",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
            }

            if (data.meetups.isEmpty()) {
                item { Text("No meetups yet.", modifier = Modifier.testTag(TestTags.MEETUPS_EMPTY)) }
            }

            items(data.meetups, key = { it.id }) { meetup ->
                MeetupRow(meetup, data.group.members, currentUserId, navigation.onOpenMeetup)
            }

            // Bottom space for floating action button
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        NewMeetupButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp),
            onClick = navigation.onNewMeetup,
        )
    }
}
