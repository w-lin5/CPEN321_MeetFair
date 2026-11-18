package com.example.cpen321application.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.TripRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A group on the list with the badge that tells the user what needs their attention there, if anything. */
data class GroupWithAction(
    val group: Group,
    val actionNeeded: String? = null,
)

/** Loads the groups the signed-in user belongs to along with any required action indicators. */
class GroupListViewModel(
    private val groups: GroupRepository,
    private val meetups: MeetupRepository,
    private val trips: TripRepository,
    private val currentUserId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<GroupWithAction>>>(UiState.Loading)
    val state: StateFlow<UiState<List<GroupWithAction>>> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun onGroupEvent(event: GroupEvent) {
        if (event is GroupEvent.Connected || event is GroupEvent.Deleted) load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            when (val result = groups.list()) {
                // Each group's badge needs its meetups; the groups are looked up side by side
                is ApiResult.Success -> _state.value = UiState.Success(
                    coroutineScope {
                        result.data.map { group -> async { GroupWithAction(group, actionNeeded(group.id)) } }.awaitAll()
                    },
                )
                is ApiResult.Failure -> _state.value = UiState.Error(result.error.message)
            }
        }
    }

    // The most pressing thing the user can do in the group, shown as a badge on its card; null if nothing
    private suspend fun actionNeeded(groupId: String): String? {
        val listed = meetups.listForGroup(groupId)
        val groupMeetups = if (listed is ApiResult.Success) listed.data else return null
        val needsDetails = currentUserId != null && groupMeetups.any { meetup ->
            meetup.status == MeetupStatus.SETUP && meetup.participants.none { it.userId == currentUserId }
        }
        return when {
            hasLiveTracking(groupMeetups, trips) -> "Tracking live"
            groupMeetups.any { it.status == MeetupStatus.VOTING } -> "Vote required"
            needsDetails -> "Enter details"
            else -> null
        }
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory {
            initializer {
                GroupListViewModel(
                    c.groupRepository,
                    c.meetupRepository,
                    c.tripRepository,
                    c.sessionStore.currentUserId(),
                )
            }
        }
    }
}
