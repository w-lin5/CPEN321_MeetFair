package com.example.cpen321application.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toListUiState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the groups the signed-in user belongs to. */
class GroupListViewModel(private val groups: GroupRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Group>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Group>>> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = groups.list().toListUiState()
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { GroupListViewModel(c.groupRepository) } }
    }
}

@Composable
fun GroupListScreen(container: AppContainer, onCreate: () -> Unit, onJoin: () -> Unit, onOpen: (String) -> Unit) {
    val vm: GroupListViewModel = screenViewModel(GroupListViewModel.factory(container))
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Your groups") {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            Button(onClick = onCreate, modifier = Modifier.testTag(TestTags.CREATE_GROUP_BUTTON)) {
                Text("Create group")
            }
            Button(onClick = onJoin, modifier = Modifier.testTag(TestTags.JOIN_GROUP_BUTTON)) {
                Text("Join group")
            }
        }
        Box(Modifier.weight(1f)) {
            UiStateContent(state, onRetry = vm::load, emptyMessage = "No groups yet. Create one or join with a code.") {
                GroupList(it, onOpen)
            }
        }
        OutlinedButton(
            onClick = { scope.launch { container.authRepository.signOut() } },
            modifier = Modifier.testTag(TestTags.SIGN_OUT_BUTTON),
        ) { Text("Sign out") }
    }
}

@Composable
private fun GroupList(groups: List<Group>, onOpen: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        items(groups, key = { it.id }) { group ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(group.id) }.testTag(TestTags.GROUP_ITEM),
            ) {
                Column(Modifier.padding(Dimens.ScreenPadding)) {
                    Text(group.name)
                    Text("${group.members.size} members")
                }
            }
        }
    }
}
