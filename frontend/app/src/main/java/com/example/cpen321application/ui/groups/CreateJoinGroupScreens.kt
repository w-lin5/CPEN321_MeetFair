package com.example.cpen321application.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.ui.common.FormSpec
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.meetFairTextFieldColors
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Submits a new group name; the created group is exposed through [FormState.result]. */
class CreateGroupViewModel(private val groups: GroupRepository) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Group>())
    val state: StateFlow<FormState<Group>> = _state.asStateFlow()

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun submit(name: String) {
        if (_state.value.submitting) return
        _state.value = FormState(submitting = true)
        viewModelScope.launch {
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

    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    fun submit(inviteCode: String) {
        if (_state.value.submitting) return
        _state.value = FormState(submitting = true)
        viewModelScope.launch {
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
    submitLabel = "Create group →",
    inputTag = TestTags.GROUP_NAME_INPUT,
    submitTag = TestTags.CREATE_GROUP_SUBMIT,
)

private val JOIN_SPEC = FormSpec(
    title = "Join group",
    label = "Invite code",
    submitLabel = "Join group →",
    inputTag = TestTags.INVITE_CODE_INPUT,
    submitTag = TestTags.JOIN_GROUP_SUBMIT,
)

@Composable
fun <T> MockupFormCard(
    spec: FormSpec,
    state: FormState<T>,
    onSubmit: (String) -> Unit,
    onClearError: () -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }

    MeetFairCard(
        containerColor = TealContainer,
        borderColor = TealPrimary,
        borderWidth = 1.5.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = TealPrimary,
            )

            MockupInputField(
                spec = spec,
                value = input,
                onValueChange = {
                    input = it
                    onClearError()
                },
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.ERROR_TEXT)
                )
            }

            MockupSubmitButton(
                spec = spec,
                enabled = !state.submitting && input.isNotBlank(),
                onSubmit = { onSubmit(input) },
            )
        }
    }
}

@Composable
private fun MockupInputField(
    spec: FormSpec,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(spec.label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = CircleShape,
        colors = meetFairTextFieldColors(unfocusedAlpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(spec.inputTag),
    )
}

@Composable
private fun MockupSubmitButton(
    spec: FormSpec,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    Button(
        onClick = onSubmit,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = RosePrimary,
            contentColor = Color.White,
            disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(spec.submitTag),
    ) {
        Text(
            text = spec.submitLabel,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun CreateGroupScreen(container: AppContainer, onBack: () -> Unit, onCreated: (groupId: String) -> Unit) {
    val vm: CreateGroupViewModel = screenViewModel(CreateGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    state.result?.let { onCreated(it.id) }

    ScreenColumn(title = "", onBack = onBack) {
        MockupFormCard(CREATE_SPEC, state, vm::submit, onClearError = vm::clearError)
    }
}

@Composable
fun JoinGroupScreen(container: AppContainer, onBack: () -> Unit, onJoined: (groupId: String) -> Unit) {
    val vm: JoinGroupViewModel = screenViewModel(JoinGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    state.result?.let { onJoined(it.id) }

    ScreenColumn(title = "", onBack = onBack) {
        MockupFormCard(JOIN_SPEC, state, vm::submit, onClearError = vm::clearError)
    }
}

