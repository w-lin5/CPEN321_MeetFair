package com.example.cpen321application.ui.recommendation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Organizer generates Google Places candidates and opens voting. */
class StartVotingViewModel(private val voting: VotingRepository, private val meetupId: String) : ViewModel() {
    private val _state = MutableStateFlow(FormState<Recommendation>())
    val state: StateFlow<FormState<Recommendation>> = _state.asStateFlow()

    init {
        generateFromPlaces()
    }

    fun generateFromPlaces() {
        if (_state.value.submitting || _state.value.result != null) return
        _state.value = FormState(submitting = true)
        viewModelScope.launch {
            _state.value = voting.generateFromPlaces(meetupId).toFormState()
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
    val state by vm.state.collectAsState()
    val notify = LocalNotify.current

    LaunchedEffect(state.result) {
        if (state.result != null) { notify("Voting has started."); onStarted() }
    }

    ScreenColumn("Start voting", onBack) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
        ) {
            if (state.submitting) {
                CircularProgressIndicator()
                Text("Generating places...")
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
                Button(
                    onClick = vm::generateFromPlaces,
                    enabled = !state.submitting,
                    modifier = Modifier.testTag(TestTags.START_VOTING_SUBMIT),
                ) { Text("Retry") }
            }
        }
    }
}
