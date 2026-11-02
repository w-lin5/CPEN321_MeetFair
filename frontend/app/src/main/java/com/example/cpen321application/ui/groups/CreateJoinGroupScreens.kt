package com.example.cpen321application.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
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

fun Modifier.dashedBorder(
    strokeWidth: Dp = 1.5.dp,
    color: Color = TealPrimary,
    cornerRadius: Dp = 24.dp,
) = this.drawWithContent {
    drawContent()
    val stroke = Stroke(
        width = strokeWidth.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    )
    drawRoundRect(
        color = color,
        style = stroke,
        cornerRadius = CornerRadius(cornerRadius.toPx())
    )
}

@Composable
fun <T> MockupFormCard(
    spec: FormSpec,
    state: FormState<T>,
    onSubmit: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dashedBorder(strokeWidth = 1.5.dp, color = TealPrimary, cornerRadius = 24.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = TealContainer),
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

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(spec.label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = TealPrimary,
                    unfocusedBorderColor = TealPrimary.copy(alpha = 0.6f),
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(spec.inputTag),
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.ERROR_TEXT)
                )
            }

            Button(
                onClick = { onSubmit(input) },
                enabled = !state.submitting && input.isNotBlank(),
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
    }
}

@Composable
fun CreateGroupScreen(container: AppContainer, onBack: () -> Unit, onCreated: (groupId: String) -> Unit) {
    val vm: CreateGroupViewModel = screenViewModel(CreateGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    state.result?.let { onCreated(it.id) }

    ScreenColumn(title = "", onBack = onBack) {
        MockupFormCard(CREATE_SPEC, state, vm::submit)
    }
}

@Composable
fun JoinGroupScreen(container: AppContainer, onBack: () -> Unit, onJoined: (groupId: String) -> Unit) {
    val vm: JoinGroupViewModel = screenViewModel(JoinGroupViewModel.factory(container))
    val state by vm.state.collectAsState()
    state.result?.let { onJoined(it.id) }

    ScreenColumn(title = "", onBack = onBack) {
        MockupFormCard(JOIN_SPEC, state, vm::submit)
    }
}

@Preview(showBackground = true)
@Composable
fun MockupGroupScreensPreview() {
    CPEN321ApplicationTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            MockupFormCard(
                spec = CREATE_SPEC,
                state = FormState<Group>(),
                onSubmit = {},
            )

            MockupFormCard(
                spec = JOIN_SPEC,
                state = FormState<Group>(),
                onSubmit = {},
            )
        }
    }
}
