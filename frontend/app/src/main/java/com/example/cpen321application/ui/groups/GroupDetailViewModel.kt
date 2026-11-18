package com.example.cpen321application.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.AppError
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.TripRepository
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.toFormState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val HTTP_NOT_FOUND = 404

/** A group together with its meetups, loaded as one unit so the screen has a single loading/error state. */
data class GroupDetailData(
    val group: Group,
    val meetups: List<Meetup>,
    val hasTrackingStage: Boolean = false,
)

/** Progress of the organizer's "remove member" action. */
data class MemberRemovalState(val pending: Boolean = false, val error: String? = null)

/** Loads one group (with members' names) and its meetups. */
class GroupDetailViewModel(
    private val groups: GroupRepository,
    private val meetups: MeetupRepository,
    private val trips: TripRepository,
    private val groupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<GroupDetailData>>(UiState.Loading)
    val state: StateFlow<UiState<GroupDetailData>> = _state.asStateFlow()

    private val _removal = MutableStateFlow(MemberRemovalState())
    val removal: StateFlow<MemberRemovalState> = _removal.asStateFlow()
    private val _deletion = MutableStateFlow(FormState<Unit>())
    val deletion: StateFlow<FormState<Unit>> = _deletion.asStateFlow()
    private val _leaving = MutableStateFlow(FormState<Unit>())
    val leaving: StateFlow<FormState<Unit>> = _leaving.asStateFlow()
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    private var deleted = false
    private var loadJob: Job? = null

    // Leaving, deleting and removing a member exclude each other while one of them is in flight
    fun leaveGroup() {
        if (_leaving.value.submitting || _leaving.value.result != null) return
        if (_deletion.value.submitting || _removal.value.pending) return
        _leaving.value = FormState(submitting = true)
        viewModelScope.launch { _leaving.value = groups.leave(groupId).toFormState() }
    }

    fun onGroupEvent(event: GroupEvent) {
        when (event) {
            GroupEvent.Connected -> load()
            is GroupEvent.Deleted -> if (event.groupId == groupId) {
                deleted = true
                loadJob?.cancel()
                _state.value = UiState.Error("This group has been deleted by its organizer.")
            }
            is GroupEvent.MeetupDeleted -> if (event.groupId == groupId) {
                load()
            }
        }
    }

    fun deleteGroup() {
        if (_deletion.value.submitting) return
        _deletion.value = FormState(submitting = true)
        viewModelScope.launch { _deletion.value = groups.delete(groupId).toFormState() }
    }

    fun removeMember(memberId: String) {
        if (_removal.value.pending || _deletion.value.submitting || _leaving.value.submitting) return
        _removal.value = MemberRemovalState(pending = true)
        viewModelScope.launch {
            when (val result = groups.removeMember(groupId, memberId)) {
                is ApiResult.Failure -> _removal.value = MemberRemovalState(error = result.error.message)
                is ApiResult.Success -> {
                    load()
                    _removal.value = MemberRemovalState()
                }
            }
        }
    }


    fun load() {
        if (deleted) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            // The group and its meetups are independent requests, so they run side by side
            val (group, groupMeetups) = coroutineScope {
                val group = async { groups.detail(groupId) }
                val groupMeetups = async { meetups.listForGroup(groupId) }
                group.await() to groupMeetups.await()
            }
            _state.value = when (group) {
                is ApiResult.Failure -> onGroupFailure(group.error)
                is ApiResult.Success -> when (groupMeetups) {
                    is ApiResult.Failure -> UiState.Error(groupMeetups.error.message)
                    is ApiResult.Success -> UiState.Success(
                        GroupDetailData(group.data, groupMeetups.data, hasLiveTracking(groupMeetups.data, trips)),
                    )
                }
            }
        }
    }

    // A group that no longer exists closes the page (through [missing]) instead of showing an error
    private fun onGroupFailure(error: AppError): UiState<GroupDetailData> {
        if (error.httpCode != HTTP_NOT_FOUND) return UiState.Error(error.message)
        deleted = true
        _missing.value = true
        return UiState.Empty
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) = viewModelFactory {
            initializer { GroupDetailViewModel(c.groupRepository, c.meetupRepository, c.tripRepository, groupId) }
        }
    }
}
