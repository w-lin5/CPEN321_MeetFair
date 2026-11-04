package com.example.cpen321application.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.meetup.MeetupStatusSpec
import com.example.cpen321application.ui.meetup.getStatusSpec
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealPrimary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A group together with its meetups, loaded as one unit so the screen has a single loading/error state. */
data class GroupDetailData(val group: Group, val meetups: List<Meetup>)
data class MemberRemovalState(val pending: Boolean = false, val error: String? = null)

private fun combine(group: ApiResult<Group>, meetups: ApiResult<List<Meetup>>): UiState<GroupDetailData> =
    when (group) {
        is ApiResult.Failure -> UiState.Error(group.error.message)
        is ApiResult.Success -> when (meetups) {
            is ApiResult.Failure -> UiState.Error(meetups.error.message)
            is ApiResult.Success -> UiState.Success(GroupDetailData(group.data, meetups.data))
        }
    }

/** Loads one group (with members' names) and its meetups. */
class GroupDetailViewModel(
    private val groups: GroupRepository,
    private val meetups: MeetupRepository,
    private val groupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<GroupDetailData>>(UiState.Loading)
    val state: StateFlow<UiState<GroupDetailData>> = _state.asStateFlow()

    private val _removal = MutableStateFlow(MemberRemovalState())
    val removal: StateFlow<MemberRemovalState> = _removal.asStateFlow()
    private val _deletion = MutableStateFlow(FormState<Unit>())
    val deletion: StateFlow<FormState<Unit>> = _deletion.asStateFlow()
    private val _leaving = MutableStateFlow(FormState<Unit>())
    val leaving: StateFlow<FormState<Unit>> = _leaving.asStateFlow()

    fun leaveGroup() {
        if (_leaving.value.submitting || _leaving.value.result != null || hasPendingAdminAction()) return
        _leaving.value = FormState(submitting = true)
        viewModelScope.launch { _leaving.value = groups.leave(groupId).toFormState() }
    }
    private var deleted = false
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()
    private var loadJob: Job? = null

    fun onGroupEvent(event: GroupEvent) {
        when (event) {
            GroupEvent.Connected -> load()
            is GroupEvent.Deleted -> if (event.groupId == groupId) {
                deleted = true
                loadJob?.cancel()
                _state.value = UiState.Error("This group has been deleted by its organizer.")
            }
            is GroupEvent.MeetupDeleted -> if (event.groupId == groupId) {
                load()
            }
        }
    }

    private fun hasPendingAdminAction(): Boolean = _deletion.value.submitting || _removal.value.pending

    fun deleteGroup() {
        if (_deletion.value.submitting) return
        _deletion.value = FormState(submitting = true)
        viewModelScope.launch { _deletion.value = groups.delete(groupId).toFormState() }
    }

    fun removeMember(memberId: String) {
        if (_removal.value.pending || _deletion.value.submitting || _leaving.value.submitting) return
        _removal.value = MemberRemovalState(pending = true)
        viewModelScope.launch {
            when (val result = groups.removeMember(groupId, memberId)) {
                is ApiResult.Failure -> _removal.value = MemberRemovalState(error = result.error.message)
                is ApiResult.Success -> {
                    _state.value = combine(groups.detail(groupId), meetups.listForGroup(groupId))
                    _removal.value = MemberRemovalState()
                }
            }
        }
    }

    fun load() {
        if (deleted) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            val group = groups.detail(groupId)
            if (group is ApiResult.Failure && group.error.httpCode == 404) {
                deleted = true
                _missing.value = true
                _state.value = UiState.Empty
            } else {
                _state.value = combine(group, meetups.listForGroup(groupId))
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) = viewModelFactory {
            initializer { GroupDetailViewModel(c.groupRepository, c.meetupRepository, groupId) }
        }
    }
}

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
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(onBack = onBack)
    GroupLiveUpdates(container, groupId, vm, onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Dimens.ScreenPadding),
    ) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { data ->
            GroupDetailContent(
                data = data,
                vm = vm,
                userId = container.sessionStore.currentUserId(),
                nav = GroupNav(onBack, onNewMeetup, onOpenMeetup, onOpenFairness),
            )
        }
    }
}

/** Navigation callbacks the group screen hands to its content. */
private data class GroupNav(
    val onBack: () -> Unit,
    val onNewMeetup: () -> Unit,
    val onOpenMeetup: (String) -> Unit,
    val onOpenFairness: () -> Unit,
)

// Leaves the screen when the group is deleted (here or elsewhere), disappears, or the user leaves it
@Composable
private fun GroupLiveUpdates(container: AppContainer, groupId: String, vm: GroupDetailViewModel, onBack: () -> Unit) {
    val notify = LocalNotify.current
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    val missing by vm.missing.collectAsState()
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
private fun GroupDetailContent(data: GroupDetailData, vm: GroupDetailViewModel, userId: String?, nav: GroupNav) {
    val removal by vm.removal.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }

    GroupInfo(
        data = data,
        currentUserId = userId,
        nav = nav,
        busy = GroupBusyState(removal, deletion, leaving),
        actions = GroupInfoActions(
            onRemove = { selectedMemberId = it.userId },
            onDelete = { confirmDelete = true },
            onLeave = { confirmLeave = true },
        ),
    )

    if (confirmLeave) {
        LeaveGroupDialog(data, userId, onDismiss = { confirmLeave = false }, onLeave = vm::leaveGroup)
    }
    if (confirmDelete) {
        DeleteConfirmation(
            title = "Delete group?",
            description = "Delete ${data.group.name} and all its meetups, participation details, votes and group Fair Credit data? This cannot be undone. External calendar events will remain.",
            votingWarning = if (data.meetups.any { it.status.name == "VOTING" })
                "Warning: this group still has meetups with voting in progress. Deleting it will end those votes." else null,
            onDismiss = { confirmDelete = false },
            onDelete = { confirmDelete = false; vm.deleteGroup() },
        )
    }
    val selected = data.group.members.firstOrNull { it.userId == selectedMemberId }
    if (selected != null) {
        RemoveMemberDialog(data, selected, onDismiss = { selectedMemberId = null }, onRemove = vm::removeMember)
    }
}

@Composable
private fun LeaveGroupDialog(data: GroupDetailData, userId: String?, onDismiss: () -> Unit, onLeave: () -> Unit) {
    val voting = data.meetups.any { meetup ->
        meetup.status.name == "VOTING" && meetup.participants.any { it.userId == userId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (voting) "Cannot leave group" else "Leave group?") },
        text = { Text(if (voting) "A meetup you joined is still voting. Wait until its voting ends before leaving."
            else "Your details in meetups that have not started voting will be deleted. Completed voting and Fair Credit history will remain.") },
        confirmButton = { TextButton(onClick = {
            onDismiss()
            if (!voting) onLeave()
        }) { Text(if (voting) "OK" else "Leave") } },
        dismissButton = { if (!voting) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveMemberDialog(
    data: GroupDetailData,
    member: GroupMember,
    onDismiss: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val activeParticipation = data.meetups.any { meetup ->
        (meetup.status.name == "VOTING" || meetup.status.name == "FINALIZED") &&
            meetup.participants.any { it.userId == member.userId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activeParticipation) "Cannot remove member" else "Remove member?") },
        text = {
            Text(if (activeParticipation)
                "This member submitted details for a meetup whose voting has started. Settle that meetup before removing them."
            else "Remove ${member.name ?: "this member"} from the group? Their meetup details and votes in this group will be deleted, and they will lose access.")
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

@Composable
private fun GroupInfo(
    data: GroupDetailData,
    currentUserId: String?,
    nav: GroupNav,
    busy: GroupBusyState,
    actions: GroupInfoActions,
) {
    val canRemove = data.group.organizerId == currentUserId

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { GroupHeader(data.group.name, isOrganizer = canRemove, busy, actions, nav.onBack) }
            item { InviteCodeCard(data.group.inviteCode) }
            item { MembersHeader(data.group.members.size, busy.removal) }

            // Member Rows
            items(data.group.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    canRemove = canRemove,
                    removalPending = busy.removal.pending,
                    deletionSubmitting = busy.deletion.submitting,
                    onRemove = actions.onRemove,
                )
            }
            item { GroupFairCreditButton(onClick = nav.onOpenFairness) }

            // Meetups Header
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

            // Meetup Rows
            items(data.meetups, key = { it.id }) { meetup ->
                MeetupRow(meetup, data.group.members, currentUserId, nav.onOpenMeetup)
            }

            // Bottom space for floating action button
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        // Floating Action Button for New Meetup
        NewMeetupButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp),
            onClick = nav.onNewMeetup,
        )
    }
}

@Composable
private fun MemberRow(
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
            modifier = Modifier.testTag("remove_member_$userId"),
        ) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class MeetupDateParts(val month: String, val day: String, val time: String)

private fun parseMeetupDateParts(iso: String): MeetupDateParts = try {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val month = zdt.format(DateTimeFormatter.ofPattern("MMM", Locale.US)).uppercase()
    val day = zdt.format(DateTimeFormatter.ofPattern("dd", Locale.US))
    val time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    MeetupDateParts(month, day, time)
} catch (_: Exception) {
    MeetupDateParts("OCT", "28", isoToDisplay(iso))
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
private fun MeetupRow(
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

