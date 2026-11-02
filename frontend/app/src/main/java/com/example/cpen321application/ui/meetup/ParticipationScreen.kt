package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.Preference
import com.example.cpen321application.model.StartLocation
import com.example.cpen321application.model.TravelMode
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

/** Submits the caller's start location, travel mode and preferences (replacing any earlier entry). */
class ParticipationViewModel(private val meetups: MeetupRepository, private val meetupId: String) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Meetup>())
    val state: StateFlow<FormState<Meetup>> = _state.asStateFlow()

    fun submit(lat: String, lng: String, label: String, mode: TravelMode, preferences: Set<Preference>) {
        if (_state.value.submitting) return
        val latitude = lat.trim().toDoubleOrNull()
        val longitude = lng.trim().toDoubleOrNull()
        if (latitude == null || longitude == null) {
            _state.value = FormState(error = "Latitude and longitude must be numbers")
        } else {
            viewModelScope.launch {
                _state.value = FormState(submitting = true)
                val location = StartLocation(latitude, longitude, label.trim())
                _state.value = meetups.submitParticipation(meetupId, location, mode, preferences).toFormState()
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) =
            viewModelFactory { initializer { ParticipationViewModel(c.meetupRepository, meetupId) } }
    }
}

@Composable
fun ParticipationScreen(container: AppContainer, meetupId: String, onBack: () -> Unit) {
    val vm: ParticipationViewModel = screenViewModel(ParticipationViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    ParticipationContent(
        state = state,
        onSubmit = { lat, lng, label, mode, preferences ->
            vm.submit(lat, lng, label, mode, preferences)
        },
        onBack = onBack,
    )
}

private data class ParticipationDraft(
    val lat: String,
    val lng: String,
    val label: String,
    val selectedLocationName: String,
    val resolvingAddress: Boolean,
    val needsAddress: Boolean,
    val mode: TravelMode,
    val preferences: Set<Preference>,
)

private data class ParticipationActions(
    val onLabelChange: (String) -> Unit,
    val onModeChange: (TravelMode) -> Unit,
    val onPreferencesChange: (Set<Preference>) -> Unit,
    val onPickLocation: () -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun ParticipationContent(
    state: FormState<Meetup>,
    onSubmit: (lat: String, lng: String, label: String, mode: TravelMode, preferences: Set<Preference>) -> Unit,
    onBack: () -> Unit,
) {
    var lat by rememberSaveable { mutableStateOf("") }
    var lng by rememberSaveable { mutableStateOf("") }
    var choosingLocation by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    var selectedLocationName by rememberSaveable { mutableStateOf("") }
    var needsAddress by rememberSaveable { mutableStateOf(false) }
    var resolvingAddress by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(TravelMode.WALK) }
    var preferences by rememberSaveable { mutableStateOf(emptySet<Preference>()) }

    LaunchedEffect(state.result) { if (state.result != null) onBack() }
    LaunchedEffect(lat, lng, needsAddress) {
        if (needsAddress && lat.isNotBlank() && lng.isNotBlank()) {
            resolvingAddress = true
            try {
                selectedLocationName = locationAddress(context, lat.toDouble(), lng.toDouble()).orEmpty()
            } catch (_: Exception) {
                // Ignore reverse geocoding errors
            } finally {
                needsAddress = false
                resolvingAddress = false
            }
        }
    }

    if (choosingLocation) {
        StartLocationPicker(
            initialLat = lat.toDoubleOrNull(),
            initialLng = lng.toDoubleOrNull(),
            onDismiss = { choosingLocation = false },
            onConfirm = { latitude, longitude, name ->
                lat = latitude.toString()
                lng = longitude.toString()
                selectedLocationName = name
                needsAddress = name.isBlank()
                choosingLocation = false
            },
        )
    } else {
        val draft = ParticipationDraft(lat, lng, label, selectedLocationName, resolvingAddress, needsAddress, mode, preferences)
        val actions = ParticipationActions(
            onLabelChange = { label = it },
            onModeChange = { mode = it },
            onPreferencesChange = { preferences = it },
            onPickLocation = { choosingLocation = true },
            onSubmit = { onSubmit(lat, lng, label, mode, preferences) },
            onBack = onBack,
        )
        ParticipationFormView(draft, state, actions)
    }
}

@Composable
private fun ParticipationFormView(
    draft: ParticipationDraft,
    state: FormState<Meetup>,
    actions: ParticipationActions,
) {
    val hasCoords = draft.lat.isNotBlank() && draft.lng.isNotBlank()
    val displayText = when {
        draft.resolvingAddress || draft.needsAddress -> "Finding location name..."
        draft.selectedLocationName.isNotBlank() -> draft.selectedLocationName
        hasCoords -> "Location selected on map"
        else -> "No starting location selected yet."
    }
    val canSubmit = !state.submitting && !draft.resolvingAddress && !draft.needsAddress && hasCoords

    ScreenColumn("Your details", actions.onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StartingLocationCard(
                hasCoordinates = hasCoords,
                displayText = displayText,
                submitting = state.submitting,
                onPickLocation = actions.onPickLocation,
            )
            PlaceNameField(
                value = draft.label,
                onValueChange = actions.onLabelChange,
                enabled = !state.submitting,
            )
            ModeChips(draft.mode, actions.onModeChange)
            PreferenceChips(draft.preferences, actions.onPreferencesChange)
            ParticipationSubmitSection(
                error = state.error,
                canSubmit = canSubmit,
                onSubmit = actions.onSubmit,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StartingLocationCard(
    hasCoordinates: Boolean,
    displayText: String,
    submitting: Boolean,
    onPickLocation: () -> Unit,
) {
    MeetFairCard(
        containerColor = TealContainer,
        borderWidth = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = RosePrimary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "STARTING LOCATION",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary.copy(alpha = 0.8f),
                    letterSpacing = 1.2.sp,
                )
            }

            Text(
                text = displayText,
                style = if (hasCoordinates) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (hasCoordinates) FontWeight.Bold else FontWeight.Normal,
                color = if (hasCoordinates) TealPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onPickLocation,
                enabled = !submitting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TealPrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(TestTags.LOCATION_PICKER_BUTTON),
            ) {
                Text(
                    text = if (hasCoordinates) "Change location" else "Pick Location From Map",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PlaceNameField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text("Place name (optional)") },
        placeholder = { Text("Place name (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = CircleShape,
        colors = meetFairTextFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LABEL_INPUT),
    )
}

@Composable
private fun ParticipationSubmitSection(
    error: String?,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
) {
    error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TestTags.ERROR_TEXT),
        )
    }

    Button(
        onClick = onSubmit,
        enabled = canSubmit,
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
            .testTag(TestTags.PARTICIPATION_SUBMIT),
    ) {
        Text(
            text = "Save details →",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun <T> ChipSelectionRow(
    title: String,
    items: Iterable<T>,
    isSelected: (T) -> Boolean,
    onToggle: (T) -> Unit,
    tag: (T) -> String,
    label: (T) -> String = { it.toString() },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                FilterChip(
                    selected = isSelected(item),
                    onClick = { onToggle(item) },
                    label = { Text(text = label(item), fontWeight = FontWeight.Bold) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealPrimary,
                        selectedLabelColor = Color.White,
                        containerColor = TealContainer,
                        labelColor = TealPrimary,
                    ),
                    modifier = Modifier.testTag(tag(item)),
                )
            }
        }
    }
}

@Composable
private fun ModeChips(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    ChipSelectionRow(
        title = "Travel mode",
        items = TravelMode.entries,
        isSelected = { it == selected },
        onToggle = onSelect,
        tag = { TestTags.modeChip(it.name) },
        label = { it.name },
    )
}

@Composable
private fun PreferenceChips(selected: Set<Preference>, onChange: (Set<Preference>) -> Unit) {
    ChipSelectionRow(
        title = "Preferences",
        items = Preference.entries,
        isSelected = { it in selected },
        onToggle = { onChange(if (it in selected) selected - it else selected + it) },
        tag = { TestTags.preferenceChip(it.name) },
        label = { it.name },
    )
}

