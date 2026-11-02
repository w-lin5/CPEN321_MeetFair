package com.example.cpen321application.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ProfileRepository
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.ProfileGroup
import com.example.cpen321application.model.UserProfile
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.meetFairTextFieldColors
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.fairness.formatCredit
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the profile and saves display name edits; a successful save replaces the loaded profile. */
class ProfileViewModel(private val profiles: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<UserProfile>>(UiState.Loading)
    val state: StateFlow<UiState<UserProfile>> = _state.asStateFlow()

    private val _save = MutableStateFlow(FormState<UserProfile>())
    val save: StateFlow<FormState<UserProfile>> = _save.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = profiles.get().toUiState()
        }
    }

    /** Null or blank resets to the Google name. */
    fun saveDisplayName(displayName: String?) {
        if (_save.value.submitting) return
        _save.value = FormState(submitting = true)
        viewModelScope.launch {
            val result = profiles.updateDisplayName(displayName).toFormState()
            _save.value = result
            result.result?.let { _state.value = UiState.Success(it) }
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory { initializer { ProfileViewModel(c.profileRepository) } }
    }
}

@Composable
fun ProfileScreen(container: AppContainer, onBack: () -> Unit, onOpenGroupFairness: (String) -> Unit) {
    val vm: ProfileViewModel = screenViewModel(ProfileViewModel.factory(container))
    val state by vm.state.collectAsState()
    val save by vm.save.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Profile", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { profile ->
            ProfileContent(
                profile = profile,
                save = save,
                onSave = vm::saveDisplayName,
                onOpenGroupFairness = onOpenGroupFairness,
                onSignOut = { scope.launch { container.authRepository.signOut() } }
            )
        }
    }
}

@Composable
private fun ProfileContent(
    profile: UserProfile,
    save: FormState<UserProfile>,
    onSave: (String?) -> Unit,
    onOpenGroupFairness: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        // Several composables in one lazy item would overlap, so each item has a single Column root
        item { AccountSection(profile, save, onSave, onSignOut) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text(
                    text = "Fairness by group",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                )
                if (profile.groups.isEmpty()) {
                    Text("You are not in any group yet.", modifier = Modifier.testTag(TestTags.PROFILE_GROUPS_EMPTY))
                }
            }
        }
        items(profile.groups, key = { it.groupId }) { ProfileGroupRow(it, onOpenGroupFairness) }
    }
}

@Composable
private fun AccountSection(
    profile: UserProfile,
    save: FormState<UserProfile>,
    onSave: (String?) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        DisplayNameForm(
            profile = profile,
            save = save,
            onSave = onSave,
        )

        Spacer(modifier = Modifier.height(8.dp))

        ProfileDetailsRow(
            profile = profile,
            isSubmitting = save.submitting,
            onSave = onSave,
            onSignOut = onSignOut,
        )
    }
}

@Composable
private fun DisplayNameForm(
    profile: UserProfile,
    save: FormState<UserProfile>,
    onSave: (String?) -> Unit,
) {
    // Re-keyed on the saved name so the field shows the server's value after each save
    var input by rememberSaveable(profile.displayName) { mutableStateOf(profile.displayName) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(
            text = "Display name",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            shape = CircleShape,
            colors = meetFairTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.DISPLAY_NAME_INPUT),
        )

        save.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TestTags.ERROR_TEXT),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onSave(input) },
            enabled = !save.submitting && input.isNotBlank() && input.trim() != profile.displayName,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RosePrimary,
                disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag(TestTags.SAVE_DISPLAY_NAME_BUTTON),
        ) {
            Text(
                text = "Save",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ProfileDetailsRow(
    profile: UserProfile,
    isSubmitting: Boolean,
    onSave: (String?) -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(TestTags.PROFILE_NAME_TEXT),
            )
            Text(
                text = profile.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.hasCustomDisplayName) {
                Text(
                    text = "Google name: ${profile.googleName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (profile.hasCustomDisplayName) {
            TextButton(
                onClick = { onSave(null) },
                enabled = !isSubmitting,
                modifier = Modifier.testTag(TestTags.RESET_DISPLAY_NAME_BUTTON),
            ) {
                Text("Use Google name", color = TealPrimary)
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = onSignOut,
            shape = CircleShape,
            modifier = Modifier.testTag(TestTags.SIGN_OUT_BUTTON),
        ) {
            Text("Sign out", color = TealPrimary)
        }
    }
}

@Composable
private fun ProfileGroupRow(group: ProfileGroup, onOpen: (String) -> Unit) {
    val role = if (group.role == GroupRole.ORGANIZER) " (organizer)" else ""
    MeetFairCard(
        modifier = Modifier
            .clickable { onOpen(group.groupId) }
            .testTag(TestTags.profileGroupItem(group.groupId)),
        shape = RoundedCornerShape(16.dp),
        containerColor = TealContainer,
        borderWidth = 0.dp,
    ) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            Text(
                text = "${group.name}$role",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
            Text(
                text = "Fair Credit: ${formatCredit(group.fairCredit)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

