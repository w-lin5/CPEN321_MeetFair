package com.example.cpen321application.ui.meetup

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
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
import com.example.cpen321application.ui.theme.Dimens
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

// A map or Places picker is deferred until a Maps key is available, so coordinates are typed in
@Composable
fun ParticipationScreen(container: AppContainer, meetupId: String, onBack: () -> Unit) {
    val vm: ParticipationViewModel = screenViewModel(ParticipationViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    var lat by rememberSaveable { mutableStateOf("") }
    var lng by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(TravelMode.WALK) }
    var preferences by rememberSaveable { mutableStateOf(emptySet<Preference>()) }

    LaunchedEffect(state.result) { if (state.result != null) onBack() }

    ScreenColumn("Your details", onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
        ) {
            LocationFields(lat, lng, label, onLat = { lat = it }, onLng = { lng = it }, onLabel = { label = it })
            ModeChips(mode) { mode = it }
            PreferenceChips(preferences) { preferences = it }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
            }
            Button(
                onClick = { vm.submit(lat, lng, label, mode, preferences) },
                enabled = !state.submitting && lat.isNotBlank() && lng.isNotBlank(),
                modifier = Modifier.testTag(TestTags.PARTICIPATION_SUBMIT),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun LocationFields(
    lat: String,
    lng: String,
    label: String,
    onLat: (String) -> Unit,
    onLng: (String) -> Unit,
    onLabel: (String) -> Unit,
) {
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    OutlinedTextField(
        value = lat,
        onValueChange = onLat,
        label = { Text("Start latitude") },
        singleLine = true,
        keyboardOptions = decimal,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LAT_INPUT),
    )
    OutlinedTextField(
        value = lng,
        onValueChange = onLng,
        label = { Text("Start longitude") },
        singleLine = true,
        keyboardOptions = decimal,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LNG_INPUT),
    )
    OutlinedTextField(
        value = label,
        onValueChange = onLabel,
        label = { Text("Place name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.LABEL_INPUT),
    )
}

@Composable
private fun ModeChips(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    Text("Travel mode", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        TravelMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.name) },
                modifier = Modifier.testTag(TestTags.modeChip(mode.name)),
            )
        }
    }
}

@Composable
private fun PreferenceChips(selected: Set<Preference>, onChange: (Set<Preference>) -> Unit) {
    Text("Preferences", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        Preference.entries.forEach { preference ->
            FilterChip(
                selected = preference in selected,
                onClick = { onChange(if (preference in selected) selected - preference else selected + preference) },
                label = { Text(preference.name) },
                modifier = Modifier.testTag(TestTags.preferenceChip(preference.name)),
            )
        }
    }
}
