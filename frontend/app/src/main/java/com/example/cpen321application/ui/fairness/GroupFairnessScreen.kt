package com.example.cpen321application.ui.fairness

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.ScreenColumn
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Hero Balance Card
                MeetFairCard(
                    containerColor = TealContainer,
                    borderWidth = 0.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "YOUR FAIR CREDIT",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary.copy(alpha = 0.7f),
                            letterSpacing = 1.2.sp,
                        )

                        Text(
                            text = "Your Fair Credit: ${formatCredit(data.balanceOf(userId))}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TealPrimary,
                            modifier = Modifier.testTag(TestTags.FAIR_CREDIT_TEXT),
                        )

                        Text(
                            text = "Positive credit means you travelled more than the group average. Voting uses your balance to set your vote weight; it does not spend credit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = "Group balances",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
            }
        }

        items(data.members, key = { it.userId }) { MemberRow(it) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your history",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )

                if (mine.isEmpty()) {
                    Text(
                        text = "No settled meetups yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TestTags.FAIRNESS_HISTORY_EMPTY),
                    )
                }
            }
        }

        items(mine, key = { it.meetupId }) { FairnessRow(it) }
    }
}

@Composable
private fun MemberRow(member: MemberCredit) {
    val initial = (member.name ?: "U").take(1).uppercase()

    MeetFairCard(
        modifier = Modifier.testTag(TestTags.FAIRNESS_MEMBER_ITEM),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = CircleShape,
                    color = TealPrimary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = initial,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }

                Text(
                    text = member.name ?: "Unknown member",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Surface(
                shape = CircleShape,
                color = TealContainer,
            ) {
                Text(
                    text = "Fair Credit: ${formatCredit(member.balance)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FairnessRow(entry: FairnessEntry) {
    val source = if (entry.source == CommuteSource.TRACKED) "tracked" else "Maps estimate"
    var expanded by rememberSaveable(entry.meetupId) { mutableStateOf(false) }

    MeetFairCard(
        modifier = Modifier
            .clickable { expanded = !expanded }
            .testTag(TestTags.FAIRNESS_ENTRY_ITEM),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.meetupTitle ?: "Meetup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                    modifier = Modifier.weight(1f),
                )

                Surface(
                    shape = CircleShape,
                    color = TealContainer,
                ) {
                    Text(
                        text = formatCredit(entry.delta),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = TealPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            entry.placeName?.let {
                Text(
                    text = "Place: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.recordedAt.isNotBlank()) {
                Text(
                    text = isoToDisplay(entry.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Fair Credit change: ${formatCredit(entry.delta)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (expanded) "Tap to hide calculation" else "Tap to see how this was calculated",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = TealContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Your trip: ${formatMinutes(entry.actualSeconds)} ($source)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Your adjusted travel burden: ${entry.normalizedBurden} seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Group average adjusted burden: ${entry.expectedBurden} seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Your burden minus the group average = ${entry.delta} seconds of Fair Credit.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = TealPrimary,
                        )
                        Text(
                            text = "A positive change earns credit; a negative change lowers your balance. The calculation is rounded to whole seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun minutes(seconds: Long): Long = (seconds / SECONDS_PER_MINUTE).roundToLong()

private fun formatMinutes(seconds: Long): String = "${minutes(seconds)} min"

/** Seconds shown as signed minutes so the direction is obvious, e.g. "+12 min" or "-5 min". */
internal fun formatCredit(seconds: Long): String = String.format(Locale.US, "%+d min", minutes(seconds))
