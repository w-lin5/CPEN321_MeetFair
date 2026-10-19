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
import com.example.cpen321application.model.GroupFairness
import com.example.cpen321application.model.MemberCredit
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SECONDS_PER_MINUTE = 60.0

/** Loads every member's Fair Credit in one group and the group's ledger. */
class GroupFairnessViewModel(private val fairness: FairnessRepository, private val groupId: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<GroupFairness>>(UiState.Loading)
    val state: StateFlow<UiState<GroupFairness>> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = fairness.groupFairness(groupId).toUiState()
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
    val userId = container.sessionStore.currentUserId()
    LaunchedEffect(Unit) { vm.load() }

    ScreenColumn("Fair Credit", onBack) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { FairnessContent(it, userId) }
    }
}

@Composable
private fun FairnessContent(data: GroupFairness, userId: String?) {
    val mine = data.entriesFor(userId)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        // Several composables in one lazy item would overlap, so each item has a single Column root
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text(
                    "Your Fair Credit: ${formatCredit(data.balanceOf(userId))}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TestTags.FAIR_CREDIT_TEXT),
                )
                Text("Positive means you have travelled more than the group average; it raises your vote weight.")
                Text("Group", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(data.members, key = { it.userId }) { MemberRow(it) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
                Text("Your history", style = MaterialTheme.typography.titleMedium)
                if (mine.isEmpty()) {
                    Text("No settled meetups yet.", modifier = Modifier.testTag(TestTags.FAIRNESS_HISTORY_EMPTY))
                }
            }
        }
        items(mine, key = { it.meetupId }) { FairnessRow(it) }
    }
}

@Composable
private fun MemberRow(member: MemberCredit) {
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.FAIRNESS_MEMBER_ITEM)) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            Text(member.name ?: "Unknown member")
            Text("Fair Credit: ${formatCredit(member.balance)}")
        }
    }
}

@Composable
private fun FairnessRow(entry: FairnessEntry) {
    val source = if (entry.source == CommuteSource.TRACKED) "tracked" else "Maps estimate"
    Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.FAIRNESS_ENTRY_ITEM)) {
        Column(Modifier.padding(Dimens.ScreenPadding)) {
            if (entry.recordedAt.isNotBlank()) Text(isoToDisplay(entry.recordedAt))
            Text("Your trip: ${formatMinutes(entry.actualSeconds)} ($source)")
            Text("Group average: ${formatMinutes(entry.expectedBurden)}")
            Text("Credit change: ${formatCredit(entry.delta)}")
        }
    }
}

private fun minutes(seconds: Long): Long = (seconds / SECONDS_PER_MINUTE).roundToLong()

private fun formatMinutes(seconds: Long): String = "${minutes(seconds)} min"

/** Seconds shown as signed minutes so the direction is obvious, e.g. "+12 min" or "-5 min". */
internal fun formatCredit(seconds: Long): String = String.format(Locale.US, "%+d min", minutes(seconds))
