package com.example.cpen321application.ui.recommendation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The group votes on exactly this many places (backend tuning.recommendationCount)
internal const val CANDIDATE_COUNT = 3

// Typed-in places have no Google Places id yet, so each gets a stable id from its position
private const val MANUAL_PLACE_ID_PREFIX = "manual-"

/** One place as the organizer typed it; coordinates stay text until the form is submitted. */
data class CandidateInput(
    val name: String = "",
    val address: String = "",
    val lat: String = "",
    val lng: String = "",
) {
    val isFilled: Boolean get() = name.isNotBlank() && lat.isNotBlank() && lng.isNotBlank()
}

/** Typed-in places as vote candidates; null if a name is blank or a coordinate is not a number. */
internal fun toCandidates(inputs: List<CandidateInput>): List<PlaceCandidate>? {
    val candidates = inputs.mapIndexed { index, input ->
        val lat = input.lat.trim().toDoubleOrNull()
        val lng = input.lng.trim().toDoubleOrNull()
        if (input.name.isBlank() || lat == null || lng == null) {
            null
        } else {
            PlaceCandidate("$MANUAL_PLACE_ID_PREFIX${index + 1}", input.name.trim(), input.address.trim(), lat, lng)
        }
    }
    return if (candidates.contains(null)) null else candidates.filterNotNull()
}

/**
 * Organizer opens voting: the typed-in places become the meetup's candidates and the meetup moves to VOTING.
 * Interim step until the recommendation engine (feature 3) generates the candidates on the server.
 */
class StartVotingViewModel(private val voting: VotingRepository, private val meetupId: String) : ViewModel() {
    private val _inputs = MutableStateFlow(List(CANDIDATE_COUNT) { CandidateInput() })
    val inputs: StateFlow<List<CandidateInput>> = _inputs.asStateFlow()

    private val _state = MutableStateFlow(FormState<Recommendation>())
    val state: StateFlow<FormState<Recommendation>> = _state.asStateFlow()

    fun update(index: Int, input: CandidateInput) {
        _inputs.update { list -> list.mapIndexed { i, old -> if (i == index) input else old } }
    }

    fun submit() {
        if (_state.value.submitting) return
        val candidates = toCandidates(_inputs.value)
        if (candidates == null) {
            _state.value = FormState(error = "Every place needs a name, and latitude and longitude must be numbers")
        } else {
            viewModelScope.launch {
                _state.value = FormState(submitting = true)
                _state.value = voting.generateRecommendations(meetupId, candidates).toFormState()
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) =
            viewModelFactory { initializer { StartVotingViewModel(c.votingRepository, meetupId) } }
    }
}

@Composable
fun StartVotingScreen(container: AppContainer, meetupId: String, onBack: () -> Unit, onStarted: () -> Unit) {
    val vm: StartVotingViewModel = screenViewModel(StartVotingViewModel.factory(container, meetupId))
    val inputs by vm.inputs.collectAsState()
    val state by vm.state.collectAsState()

    LaunchedEffect(state.result) { if (state.result != null) onStarted() }

    ScreenColumn("Start voting", onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
        ) {
            Text(
                "Enter the $CANDIDATE_COUNT places the group will vote on. Once voting starts, members " +
                    "can no longer change their details, and only members who entered them can vote.",
            )
            inputs.forEachIndexed { index, input -> CandidateFields(index, input) { vm.update(index, it) } }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
            }
            Button(
                onClick = vm::submit,
                enabled = !state.submitting && inputs.all { it.isFilled },
                modifier = Modifier.testTag(TestTags.START_VOTING_SUBMIT),
            ) { Text(if (state.submitting) "Starting…" else "Start voting") }
        }
    }
}

@Composable
private fun CandidateFields(index: Int, input: CandidateInput, onChange: (CandidateInput) -> Unit) {
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
            Text("Place ${index + 1}", style = MaterialTheme.typography.titleMedium)
            CandidateField(input.name, "Name", TestTags.candidateNameInput(index)) {
                onChange(input.copy(name = it))
            }
            CandidateField(input.address, "Address (optional)", TestTags.candidateAddressInput(index)) {
                onChange(input.copy(address = it))
            }
            CandidateField(input.lat, "Latitude", TestTags.candidateLatInput(index), decimal) {
                onChange(input.copy(lat = it))
            }
            CandidateField(input.lng, "Longitude", TestTags.candidateLngInput(index), decimal) {
                onChange(input.copy(lng = it))
            }
        }
    }
}

@Composable
private fun CandidateField(
    value: String,
    label: String,
    tag: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
