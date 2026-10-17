package com.example.cpen321application.ui.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.ui.common.FormSpec
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.SingleFieldFormScreen
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Submits a new group name; the created group is exposed through [FormState.result]. */
class CreateGroupViewModel(private val groups: GroupRepository) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Group>())
    val state: StateFlow<FormState<Group>> = _state.asStateFlow()

    fun submit(name: String) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = FormState(submitting = true)
            _state.value = groups.create(name.trim()).toFormState()
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { CreateGroupViewModel(c.groupRepository) } }
    }
}

/** Submits an invite code; the joined group is exposed through [FormState.result]. */
class JoinGroupViewModel(private val groups: GroupRepository) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Group>())
    val state: StateFlow<FormState<Group>> = _state.asStateFlow()

    fun submit(inviteCode: String) {
        if (_state.value.submitting) return
        viewModelScope.launch {
            _state.value = FormState(submitting = true)
            _state.value = groups.join(inviteCode.trim()).toFormState()
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { JoinGroupViewModel(c.groupRepository) } }
    }
}

private val CREATE_SPEC = FormSpec(
    title = "Create group",
    label = "Group name",
    submitLabel = "Create",
    inputTag = TestTags.GROUP_NAME_INPUT,
    submitTag = TestTags.CREATE_GROUP_SUBMIT,
)

private val JOIN_SPEC = FormSpec(
    title = "Join group",
    label = "Invite code",
    submitLabel = "Join",
    inputTag = TestTags.INVITE_CODE_INPUT,
    submitTag = TestTags.JOIN_GROUP_SUBMIT,
)

@Composable
fun CreateGroupScreen(container: AppContainer, onBack: () -> Unit, onCreated: (groupId: String) -> Unit) {
    val vm: CreateGroupViewModel = screenViewModel(CreateGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    SingleFieldFormScreen(CREATE_SPEC, state, onBack, vm::submit) { onCreated(it.id) }
}

@Composable
fun JoinGroupScreen(container: AppContainer, onBack: () -> Unit, onJoined: (groupId: String) -> Unit) {
    val vm: JoinGroupViewModel = screenViewModel(JoinGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    SingleFieldFormScreen(JOIN_SPEC, state, onBack, vm::submit) { onJoined(it.id) }
}
