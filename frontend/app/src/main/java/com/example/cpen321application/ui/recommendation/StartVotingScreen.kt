package com.example.cpen321application.ui.recommendation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.Recommendation
import com.example.cpen321application.ui.common.ErrorView
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = TealContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = TealPrimary,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag(TestTags.LOADING),
                        )
                        Text(
                            text = "Generating place candidates...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary,
                        )
                    }
                }
            }

            state.error?.let { errorMsg ->
                ErrorView(
                    message = errorMsg,
                    onRetry = vm::generateFromPlaces,
                    buttonTestTag = TestTags.START_VOTING_SUBMIT,
                )
            }
        }
    }
}
