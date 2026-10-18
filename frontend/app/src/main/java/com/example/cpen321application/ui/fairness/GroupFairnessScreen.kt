package com.example.cpen321application.ui.fairness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import com.example.cpen321application.data.FairnessRepository
import com.example.cpen321application.model.CommuteSource
import com.example.cpen321application.model.FairnessEntry
import com.example.cpen321application.model.FairnessHistory
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the signed-in user's fairness debt and commute history in one group. */
class GroupFairnessViewModel(private val fairness: FairnessRepository, private val groupId: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<FairnessHistory>>(UiState.Loading)
    val state: StateFlow<UiState<FairnessHistory>> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = fairness.fairnessHistory(groupId).toUiState()
        }
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) =
            viewModelFactory { initializer { GroupFairnessViewModel(c.fairnessRepository, groupId) } }
    }
}

@Composable
fun GroupFairnessScreen(container: AppContainer, groupId: String, onBack: () -> Unit) {
    val vm: GroupFairnessViewModel = screenViewModel(GroupFairnessViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Fairness history", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { FairnessContent(it) }
    }
}

@Composable
private fun FairnessContent(data: FairnessHistory) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text(
                    "Fairness debt: ${formatDebt(data.fairnessDebt)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TestTags.FAIRNESS_DEBT_TEXT),
                )
                Text("Positive means you have travelled more than the group average; it raises your vote weight.")
                if (data.history.isEmpty()) {
                    Text("No finished meetups yet.", modifier = Modifier.testTag(TestTags.FAIRNESS_HISTORY_EMPTY))
                }
            }
        }
        items(data.history, key = { it.meetupId }) { FairnessRow(it) }
    }
}

@Composable
private fun FairnessRow(entry: FairnessEntry) {
    val source = if (entry.source == CommuteSource.TRACKED) "tracked" else "Maps estimate"
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.FAIRNESS_ENTRY_ITEM)) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            if (entry.recordedAt.isNotBlank()) Text(isoToDisplay(entry.recordedAt))
            Text("Your commute: ${formatMinutes(entry.commuteMinutes)} ($source)")
            Text("Group average: ${formatMinutes(entry.groupAverageMinutes)}")
            Text("Extra: ${formatDebt(entry.extraMinutes)}")
        }
    }
}

private fun formatMinutes(minutes: Double): String = String.format(Locale.US, "%.0f min", minutes)

/** Signed so the direction is obvious, e.g. "+12 min" or "-5 min". */
internal fun formatDebt(minutes: Double): String = String.format(Locale.US, "%+.0f min", minutes)
