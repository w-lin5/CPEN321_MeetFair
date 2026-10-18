package com.example.cpen321application.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.fairness.formatDebt
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
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
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Profile", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { profile ->
            ProfileContent(profile, save, vm::saveDisplayName, onOpenGroupFairness)
        }
    }
}

@Composable
private fun ProfileContent(
    profile: UserProfile,
    save: FormState<UserProfile>,
    onSave: (String?) -> Unit,
    onOpenGroupFairness: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        // Several composables in one lazy item would overlap, so each item has a single Column root
        item { AccountSection(profile, save, onSave) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text("Fairness by group", style = MaterialTheme.typography.titleMedium)
                if (profile.groups.isEmpty()) {
                    Text("You are not in any group yet.", modifier = Modifier.testTag(TestTags.PROFILE_GROUPS_EMPTY))
                }
            }
        }
        items(profile.groups, key = { it.groupId }) { ProfileGroupRow(it, onOpenGroupFairness) }
    }
}

@Composable
private fun AccountSection(profile: UserProfile, save: FormState<UserProfile>, onSave: (String?) -> Unit) {
    // Re-keyed on the saved name so the field shows the server's value after each save
    var input by rememberSaveable(profile.displayName) { mutableStateOf(profile.displayName) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(
            profile.displayName,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(TestTags.PROFILE_NAME_TEXT),
        )
        Text(profile.email)
        if (profile.hasCustomDisplayName) Text("Google name: ${profile.googleName}")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.DISPLAY_NAME_INPUT),
        )
        save.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            Button(
                onClick = { onSave(input) },
                enabled = !save.submitting && input.isNotBlank() && input.trim() != profile.displayName,
                modifier = Modifier.testTag(TestTags.SAVE_DISPLAY_NAME_BUTTON),
            ) { Text("Save") }
            if (profile.hasCustomDisplayName) {
                TextButton(
                    onClick = { onSave(null) },
                    enabled = !save.submitting,
                    modifier = Modifier.testTag(TestTags.RESET_DISPLAY_NAME_BUTTON),
                ) { Text("Use Google name") }
            }
        }
    }
}

@Composable
private fun ProfileGroupRow(group: ProfileGroup, onOpen: (String) -> Unit) {
    val role = if (group.role == GroupRole.ORGANIZER) " (organizer)" else ""
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(group.groupId) }
            .testTag(TestTags.profileGroupItem(group.groupId)),
    ) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            Text("${group.name}$role")
            Text("Fairness debt: ${formatDebt(group.fairnessDebt)}")
        }
    }
}
