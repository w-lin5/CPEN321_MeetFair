package com.example.cpen321application.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.ScreenColumn
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

/** A group together with its meetups, loaded as one unit so the screen has a single loading/error state. */
data class GroupDetailData(val group: Group, val meetups: List<Meetup>)

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

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = combine(groups.detail(groupId), meetups.listForGroup(groupId))
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
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Group", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { GroupInfo(it, onNewMeetup, onOpenMeetup) }
    }
}

@Composable
private fun GroupInfo(data: GroupDetailData, onNewMeetup: () -> Unit, onOpenMeetup: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        // Several composables in one lazy item would overlap, so each item has a single Column root
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text(data.group.name, style = MaterialTheme.typography.titleLarge)
                Text("Invite code: ${data.group.inviteCode}", modifier = Modifier.testTag(TestTags.INVITE_CODE_TEXT))
                Text("Members", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(data.group.members, key = { it.userId }) { MemberRow(it) }
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
private fun MemberRow(member: GroupMember) {
    val role = if (member.role == GroupRole.ORGANIZER) " (organizer)" else ""
    Text("${member.name ?: "Unknown"}$role", modifier = Modifier.testTag(TestTags.MEMBER_ITEM))
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
