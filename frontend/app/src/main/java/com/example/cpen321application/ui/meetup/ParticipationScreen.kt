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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.tooling.preview.Preview
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
        ScreenColumn("Your details", onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Starting location Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = TealContainer),
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

                    if (lat.isNotBlank() && lng.isNotBlank()) {
                        Text(
                            text = when {
                                resolvingAddress || needsAddress -> "Finding location name..."
                                selectedLocationName.isNotBlank() -> selectedLocationName
                                else -> "Location selected on map"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary,
                        )
                    } else {
                        Text(
                            text = "No starting location selected yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = { choosingLocation = true },
                        enabled = !state.submitting,
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
                            text = if (lat.isBlank()) "Pick Location From Map" +
                                    "" else "Change location",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Optional Place Name Text Field
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                enabled = !state.submitting,
                label = { Text("Place name (optional)") },
                placeholder = { Text("Place name (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = TealPrimary,
                    unfocusedBorderColor = TealPrimary.copy(alpha = 0.3f),
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.LABEL_INPUT),
            )

            ModeChips(mode) { mode = it }

            PreferenceChips(preferences) { preferences = it }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.ERROR_TEXT),
                )
            }

            Button(
                onClick = { onSubmit(lat, lng, label, mode, preferences) },
                enabled = !state.submitting && !resolvingAddress && !needsAddress && lat.isNotBlank() && lng.isNotBlank(),
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
}

@Composable
private fun ModeChips(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Travel mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TravelMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    label = {
                        Text(
                            text = mode.name,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealPrimary,
                        selectedLabelColor = Color.White,
                        containerColor = TealContainer,
                        labelColor = TealPrimary,
                    ),
                    modifier = Modifier.testTag(TestTags.modeChip(mode.name)),
                )
            }
        }
    }
}

@Composable
private fun PreferenceChips(selected: Set<Preference>, onChange: (Set<Preference>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Preferences",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TealPrimary,
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Preference.entries.forEach { preference ->
                FilterChip(
                    selected = preference in selected,
                    onClick = { onChange(if (preference in selected) selected - preference else selected + preference) },
                    label = {
                        Text(
                            text = preference.name,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TealPrimary,
                        selectedLabelColor = Color.White,
                        containerColor = TealContainer,
                        labelColor = TealPrimary,
                    ),
                    modifier = Modifier.testTag(TestTags.preferenceChip(preference.name)),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ParticipationScreenPreview() {
    CPEN321ApplicationTheme {
        ParticipationContent(
            state = FormState(),
            onSubmit = { _, _, _, _, _ -> },
            onBack = {},
        )
    }
}
