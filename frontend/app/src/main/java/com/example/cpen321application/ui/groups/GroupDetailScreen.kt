package com.example.cpen321application.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

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

    fun deleteGroup() {
        if (_deletion.value.submitting || _deletion.value.result != null || _removal.value.pending) return
        _deletion.value = FormState(submitting = true)
        viewModelScope.launch { _deletion.value = groups.delete(groupId).toFormState() }
    }

    fun removeMember(memberId: String) {
        if (_removal.value.pending || _deletion.value.submitting) return
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
) {
    val vm: GroupDetailViewModel = screenViewModel(GroupDetailViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    val removal by vm.removal.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val notify = LocalNotify.current
    val missing by vm.missing.collectAsState()
    var exited by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
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
                event is GroupEvent.MeetupDeleted && event.groupId == groupId ->
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

    ScreenColumn("Group", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { data ->
            GroupInfo(data, onNewMeetup, onOpenMeetup,
                canRemove = data.group.organizerId == container.sessionStore.currentUserId(),
                removal = removal, onRemove = { selectedMemberId = it.userId },
                deletion = deletion, onDelete = { confirmDelete = true })
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
                val activeParticipation = data.meetups.any { meetup ->
                    (meetup.status.name == "VOTING" || meetup.status.name == "FINALIZED") &&
                        meetup.participants.any { it.userId == selected.userId }
                }
                AlertDialog(
                    onDismissRequest = { selectedMemberId = null },
                    title = { Text(if (activeParticipation) "Cannot remove member" else "Remove member?") },
                    text = {
                        Text(if (activeParticipation)
                            "This member submitted details for a meetup whose voting has started. Settle that meetup before removing them."
                        else "Remove ${selected.name ?: "this member"} from the group? Their meetup details and votes in this group will be deleted, and they will lose access.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedMemberId = null
                            if (!activeParticipation) vm.removeMember(selected.userId)
                        }) { Text(if (activeParticipation) "OK" else "Remove") }
                    },
                    dismissButton = {
                        if (!activeParticipation) {
                            TextButton(onClick = { selectedMemberId = null }) { Text("Cancel") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupInfo(
    data: GroupDetailData, onNewMeetup: () -> Unit, onOpenMeetup: (String) -> Unit,
    canRemove: Boolean, removal: MemberRemovalState, onRemove: (GroupMember) -> Unit,
    deletion: FormState<Unit>, onDelete: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        // Several composables in one lazy item would overlap, so each item has a single Column root
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text(data.group.name, style = MaterialTheme.typography.titleLarge)
                Text("Invite code: ${data.group.inviteCode}", modifier = Modifier.testTag(TestTags.INVITE_CODE_TEXT))
                if (canRemove) {
                    TextButton(onClick = onDelete, enabled = !deletion.submitting && !removal.pending,
                        modifier = Modifier.testTag("delete_group")) {
                        Text(if (deletion.submitting) "Deleting..." else "Delete group", color = MaterialTheme.colorScheme.error)
                    }
                }
                deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Members", style = MaterialTheme.typography.titleMedium)
                if (removal.pending) Text("Removing member...")
                removal.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT)) }
            }
        }
        items(data.group.members, key = { it.userId }) { member ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MemberRow(member, Modifier.weight(1f))
                if (canRemove && member.userId != data.group.organizerId) {
                    TextButton(
                        onClick = { onRemove(member) },
                        enabled = !removal.pending && !deletion.submitting,
                        modifier = Modifier.testTag("remove_member_${member.userId}"),
                    ) { Text("Remove") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text("Meetups", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onNewMeetup, modifier = Modifier.testTag(TestTags.NEW_MEETUP_BUTTON)) {
                    Text("New meetup")
                }
            }
        }
        if (data.meetups.isEmpty()) {
            item { Text("No meetups yet.", modifier = Modifier.testTag(TestTags.MEETUPS_EMPTY)) }
        }
        items(data.meetups, key = { it.id }) { MeetupRow(it, onOpenMeetup) }
    }
}

@Composable
private fun MemberRow(member: GroupMember, modifier: Modifier = Modifier) {
    val role = if (member.role == GroupRole.ORGANIZER) " (organizer)" else ""
    Text("${member.name ?: "Unknown"}$role", modifier = modifier.testTag(TestTags.MEMBER_ITEM))
}

@Composable
private fun MeetupRow(meetup: Meetup, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(meetup.id) }.testTag(TestTags.MEETUP_ITEM)) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            Text(meetup.title)
            Text("${isoToDisplay(meetup.scheduledAt)} · ${meetup.status.name}")
        }
    }
}
