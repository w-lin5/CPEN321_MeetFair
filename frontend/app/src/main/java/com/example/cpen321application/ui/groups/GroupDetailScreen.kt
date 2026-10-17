package com.example.cpen321application.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads one group, including its members' names. */
class GroupDetailViewModel(private val groups: GroupRepository, private val groupId: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Group>>(UiState.Loading)
    val state: StateFlow<UiState<Group>> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = groups.detail(groupId).toUiState()
        }
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) =
            viewModelFactory { initializer { GroupDetailViewModel(c.groupRepository, groupId) } }
    }
}

@Composable
fun GroupDetailScreen(container: AppContainer, groupId: String, onBack: () -> Unit) {
    val vm: GroupDetailViewModel = screenViewModel(GroupDetailViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Group", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { GroupInfo(it) }
    }
}

@Composable
private fun GroupInfo(group: Group) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(group.name, style = MaterialTheme.typography.titleLarge)
        Text("Invite code: ${group.inviteCode}", modifier = Modifier.testTag(TestTags.INVITE_CODE_TEXT))
        Text("Members", style = MaterialTheme.typography.titleMedium)
        group.members.forEach { member ->
            val role = if (member.role == GroupRole.ORGANIZER) " (organizer)" else ""
            Text("${member.name ?: "Unknown"}$role", modifier = Modifier.testTag(TestTags.MEMBER_ITEM))
        }
    }
}
