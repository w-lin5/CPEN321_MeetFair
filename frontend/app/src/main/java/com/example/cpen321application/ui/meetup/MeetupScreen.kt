package com.example.cpen321application.ui.meetup

import android.content.Intent
import android.net.Uri
import com.example.cpen321application.model.PlaceCandidate
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.R
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.CalendarEventResult
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MyVote
import com.example.cpen321application.model.VoteTally
import com.example.cpen321application.model.VotesSnapshot
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.MeetupStatusNotifications
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealPrimary
import com.example.cpen321application.ui.voting.AddToCalendarButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val LockIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Lock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(TealPrimary)) {
        moveTo(18f, 8f)
        horizontalLineTo(17f)
        verticalLineTo(6f)
        curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
        curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f)
        verticalLineTo(8f)
        horizontalLineTo(6f)
        curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f)
        verticalLineTo(20f)
        curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
        horizontalLineTo(18f)
        curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
        verticalLineTo(10f)
        curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f)
        close()
        moveTo(9f, 6f)
        curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f)
        curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
        verticalLineTo(8f)
        horizontalLineTo(9f)
        verticalLineTo(6f)
        close()
    }.build()

private val CalendarIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Calendar",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(TealPrimary)) {
        moveTo(19f, 4f)
        horizontalLineTo(18f)
        verticalLineTo(2f)
        horizontalLineTo(16f)
        verticalLineTo(4f)
        horizontalLineTo(8f)
        verticalLineTo(2f)
        horizontalLineTo(6f)
        verticalLineTo(4f)
        horizontalLineTo(5f)
        curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f)
        lineTo(3f, 20f)
        curveTo(3f, 21.1f, 3.89f, 22f, 5f, 22f)
        horizontalLineTo(19f)
        curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
        verticalLineTo(6f)
        curveTo(21f, 4.9f, 20.1f, 4f, 19f, 4f)
        close()
        moveTo(19f, 20f)
        horizontalLineTo(5f)
        verticalLineTo(10f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        close()
    }.build()

private val DirectionsArrowIcon: ImageVector
    get() = ImageVector.Builder(
        name = "DirectionsArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(TealPrimary)) {
        moveTo(21f, 3f)
        lineTo(3f, 10.53f)
        verticalLineTo(11.51f)
        lineTo(9.84f, 14.16f)
        lineTo(12.49f, 21f)
        horizontalLineTo(13.47f)
        lineTo(21f, 3f)
        close()
    }.build()

private val BarChartIcon: ImageVector
    get() = ImageVector.Builder(
        name = "BarChart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(TealPrimary)) {
        moveTo(5f, 9.2f)
        horizontalLineTo(8f)
        verticalLineTo(19f)
        horizontalLineTo(5f)
        verticalLineTo(9.2f)
        close()
        moveTo(10.6f, 5f)
        horizontalLineTo(13.4f)
        verticalLineTo(19f)
        horizontalLineTo(10.6f)
        verticalLineTo(5f)
        close()
        moveTo(16.2f, 13f)
        horizontalLineTo(19f)
        verticalLineTo(19f)
        horizontalLineTo(16.2f)
        verticalLineTo(13f)
        close()
    }.build()

private val TimerIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Timer",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White)) {
        moveTo(15f, 1f)
        horizontalLineTo(9f)
        verticalLineTo(3f)
        horizontalLineTo(15f)
        verticalLineTo(1f)
        close()
        moveTo(11f, 14f)
        horizontalLineTo(13f)
        verticalLineTo(9f)
        horizontalLineTo(11f)
        verticalLineTo(14f)
        close()
        moveTo(19.03f, 7.39f)
        lineTo(20.45f, 5.97f)
        curveTo(20.02f, 5.46f, 19.55f, 5.01f, 19.04f, 4.6f)
        lineTo(17.62f, 6.02f)
        curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f)
        curveTo(7.03f, 4f, 3f, 8.03f, 3f, 13f)
        curveTo(3f, 17.97f, 7.02f, 22f, 12f, 22f)
        curveTo(16.97f, 22f, 21f, 17.97f, 21f, 13f)
        curveTo(21f, 10.88f, 20.26f, 8.93f, 19.03f, 7.39f)
        close()
        moveTo(12f, 20f)
        curveTo(8.13f, 20f, 5f, 16.87f, 5f, 13f)
        curveTo(5f, 9.13f, 8.13f, 6f, 12f, 6f)
        curveTo(15.87f, 6f, 19f, 9.13f, 19f, 13f)
        curveTo(19f, 16.87f, 15.87f, 20f, 12f, 20f)
        close()
    }.build()

private val FlagIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Flag",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White)) {
        moveTo(14.4f, 6f)
        lineTo(14f, 4f)
        horizontalLineTo(5f)
        verticalLineTo(21f)
        horizontalLineTo(7f)
        verticalLineTo(14f)
        horizontalLineTo(12.6f)
        lineTo(13f, 16f)
        horizontalLineTo(20f)
        verticalLineTo(6f)
        horizontalLineTo(14.4f)
        close()
    }.build()

private data class FormattedDateParts(val date: String, val time: String)

private fun parseFormattedDateParts(iso: String): FormattedDateParts = try {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val date = zdt.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    val time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    FormattedDateParts(date, time)
} catch (_: Exception) {
    FormattedDateParts("Oct 28, 2026", "11:20 AM")
}

/** Loads one meetup for the status hub and adds it to the user's Google Calendar once finalized. */
class MeetupViewModel(
    private val meetups: MeetupRepository,
    private val groups: GroupRepository,
    private val voting: VotingRepository,
    private val meetupId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Meetup>>(UiState.Loading)
    val state: StateFlow<UiState<Meetup>> = _state.asStateFlow()

    private val _organizerName = MutableStateFlow<String?>(null)
    val organizerName: StateFlow<String?> = _organizerName.asStateFlow()

    private val _votes = MutableStateFlow<VotesSnapshot?>(null)
    val votes: StateFlow<VotesSnapshot?> = _votes.asStateFlow()

    private val _calendar = MutableStateFlow(FormState<CalendarEventResult>())
    val calendar: StateFlow<FormState<CalendarEventResult>> = _calendar.asStateFlow()
    private val _deletion = MutableStateFlow(FormState<Unit>())
    val deletion: StateFlow<FormState<Unit>> = _deletion.asStateFlow()
    private val _leaving = MutableStateFlow(FormState<Unit>())
    val leaving: StateFlow<FormState<Unit>> = _leaving.asStateFlow()
    private var loadJob: Job? = null

    fun leaveMeetup() {
        if (_leaving.value.submitting || _deletion.value.submitting) return
        _leaving.value = FormState(submitting = true)
        viewModelScope.launch {
            when (val result = meetups.leave(meetupId)) {
                is ApiResult.Failure -> _leaving.value = FormState(error = result.error.message)
                is ApiResult.Success -> {
                    _state.value = meetups.get(meetupId).toUiState()
                    _leaving.value = FormState()
                }
            }
        }
    }

    fun onStatusChanged(event: MeetupEvent.StatusChanged) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        loadJob?.cancel()
        _state.value = UiState.Success(current.copy(status = event.status, winner = event.winner ?: current.winner))
    }

    fun deleteMeetup() {
        if (_deletion.value.submitting || _deletion.value.result != null || _leaving.value.submitting) return
        _deletion.value = FormState(submitting = true)
        viewModelScope.launch { _deletion.value = meetups.delete(meetupId).toFormState() }
    }

    fun load() {
        refresh(showLoading = true)
    }

    fun refreshLive() {
        refresh(showLoading = false)
    }

    private fun refresh(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) _state.value = UiState.Loading
            val result = meetups.get(meetupId)
            if (result is ApiResult.Success) {
                _state.value = result.toUiState()
                viewModelScope.launch {
                    when (val groupRes = groups.detail(result.data.groupId)) {
                        is ApiResult.Success -> {
                            _organizerName.value = groupRes.data.members.find { it.userId == result.data.organizerId }?.name
                        }
                        is ApiResult.Failure -> {}
                    }
                }
                if (result.data.status == MeetupStatus.VOTING) {
                    when (val v = voting.votes(meetupId)) {
                        is ApiResult.Success -> _votes.value = v.data
                        is ApiResult.Failure -> {}
                    }
                }
            } else if (_state.value !is UiState.Success) {
                _state.value = result.toUiState()
            }
        }
    }

    /** [accessToken] is the short-lived calendar.events token from CalendarAuthorizer. */
    fun addToCalendar(accessToken: String) {
        if (_calendar.value.submitting) return
        _calendar.value = FormState(submitting = true)
        viewModelScope.launch { _calendar.value = meetups.addToCalendar(meetupId, accessToken).toFormState() }
    }

    fun setCalendarError(message: String) {
        _calendar.value = FormState(error = message)
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) =
            viewModelFactory { initializer { MeetupViewModel(c.meetupRepository, c.groupRepository, c.votingRepository, meetupId) } }
    }
}

/** Status hub: shows the meetup and whatever the current status lets the user do next. */
@Composable
fun MeetupScreen(
    container: AppContainer,
    meetupId: String,
    onBack: () -> Unit,
    onEnterDetails: () -> Unit,
    onStartVoting: () -> Unit,
    onOpenVoting: () -> Unit,
    onOpenLiveLocation: () -> Unit = {},
) {
    val vm: MeetupViewModel = screenViewModel(MeetupViewModel.factory(container, meetupId))
    val state by vm.state.collectAsState()
    val votes by vm.votes.collectAsState()
    MeetupStatusNotifications((state as? UiState.Success)?.data)
    val calendar by vm.calendar.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val notify = LocalNotify.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var exited by rememberSaveable { mutableStateOf(false) }
    val userId = container.sessionStore.currentUserId()
    var now by remember { mutableStateOf(java.time.Instant.now()) }
    var ownTrip by remember { mutableStateOf<com.example.cpen321application.data.MeetupTrip?>(null) }
    val refreshTrip: suspend () -> Unit = {
        val result = container.tripRepository.list(meetupId)
        if (result is ApiResult.Success) ownTrip = result.data.find { it.userId == userId }
    }
    LaunchedEffect(meetupId) {
        refreshTrip()
        while (true) { now = java.time.Instant.now(); kotlinx.coroutines.delay(1000) }
    }
    val actions = MeetupActions(onEnterDetails, onStartVoting, onOpenVoting, onOpenLiveLocation)
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    BackHandler(onBack = onBack)

    LaunchedEffect(container, meetupId) {
        container.realtimeClient.meetupEvents(meetupId).collect { event ->
            if (event is MeetupEvent.Joined || event is MeetupEvent.RecommendationsReady) vm.refreshLive()
            if (event is MeetupEvent.StatusChanged) vm.onStatusChanged(event)
            if (event is MeetupEvent.TripUpdated || event is MeetupEvent.Joined) refreshTrip()
            if (event is MeetupEvent.Deleted && !vm.deletion.value.submitting &&
                vm.deletion.value.result == null && !exited) {
                exited = true
                if (event.notifyParticipant) notify("This meetup has been deleted by its organizer.")
                onBack()
            }
        }
    }
    LaunchedEffect(deletion.result) {
        if (deletion.result != null && !exited) { exited = true; notify("Meetup deleted."); onBack() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Dimens.ScreenPadding),
    ) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { meetup ->
            if (confirmLeave) {
                AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text("Leave meetup?") },
                    text = { Text("Your starting location and preferences for this meetup will be deleted and will no longer affect place searches. You can join again by entering your details before voting starts.") },
                    confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leaveMeetup() }) { Text("Leave") } },
                    dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
                )
            }
            if (confirmDelete) {
                DeleteConfirmation(
                    title = "Delete meetup?",
                    description = "Delete ${meetup.title}, its participation details, candidates and votes? This cannot be undone. Settled Fair Credit and external calendar events will remain.",
                    votingWarning = if (meetup.status == MeetupStatus.VOTING)
                        "Warning: this meetup currently has voting in progress. Deleting it will end the vote." else null,
                    onDismiss = { confirmDelete = false },
                    onDelete = { confirmDelete = false; vm.deleteMeetup() },
                )
            }

            val submitted = meetup.participants.any { it.userId == userId }
            val isOrganizer = meetup.organizerId == userId
            val isSetup = meetup.status == MeetupStatus.SETUP

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Top Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(TestTags.BACK_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TealPrimary,
                        )
                    }

                    if ((isSetup && submitted) || isOrganizer) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = TealPrimary,
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                if (isSetup && submitted) {
                                    DropdownMenuItem(
                                        text = { Text(if (leaving.submitting) "Leaving..." else "Leave meetup") },
                                        onClick = { showMenu = false; confirmLeave = true },
                                        enabled = !leaving.submitting && !deletion.submitting,
                                        modifier = Modifier.testTag("leave_meetup"),
                                    )
                                }
                                if (isOrganizer) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (deletion.submitting) "Deleting..." else "Delete meetup",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = { showMenu = false; confirmDelete = true },
                                        enabled = !deletion.submitting,
                                        modifier = Modifier.testTag("delete_meetup"),
                                    )
                                }
                            }
                        }
                    }
                }

                // Status Chip
                val spec = getStatusSpec(meetup.status)
                Surface(
                    shape = CircleShape,
                    color = spec.backgroundColor,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag(TestTags.MEETUP_STATUS_TEXT),
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = spec.color,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = spec.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = spec.color,
                        )
                        Text(
                            text = "Status: ${meetup.status.name}",
                            fontSize = 0.sp,
                            color = Color.Transparent,
                        )
                    }
                }

                // Title
                Text(
                    text = meetup.title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                    modifier = Modifier.testTag(TestTags.MEETUP_TITLE_TEXT),
                )

                val organizerName by vm.organizerName.collectAsState()
                val organizerDisplay = if (isOrganizer) "You" else (organizerName ?: "Organizer")
                Text(
                    text = "Meeting Organizer: $organizerDisplay",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("meetup_organizer_text"),
                )

                leaving.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                // Meetup Details Cards & Actions
                MeetupInfoCard(
                    meetup = meetup,
                    userId = userId,
                    votes = votes,
                    actions = actions,
                    ownTrip = ownTrip,
                    container = container,
                    calendarSection = {
                        val alreadyAdded = meetup.participants.any { it.userId == userId && it.calendarEventId != null }
                        AddToCalendarButton(
                            container.calendarAuthorizer,
                            calendar,
                            alreadyAdded,
                            onToken = vm::addToCalendar,
                            onError = vm::setCalendarError,
                        )
                    },
                )
            }
        }
    }
}

/** Navigation callbacks for the next steps the meetup screen can offer. */
private data class MeetupActions(
    val onEnterDetails: () -> Unit,
    val onStartVoting: () -> Unit,
    val onOpenVoting: () -> Unit,
    val onOpenLiveLocation: () -> Unit,
)

@Composable
private fun MeetupInfoCard(
    meetup: Meetup,
    userId: String?,
    votes: VotesSnapshot?,
    actions: MeetupActions,
    ownTrip: com.example.cpen321application.data.MeetupTrip?,
    container: AppContainer,
    calendarSection: @Composable () -> Unit,
) {
    val submitted = meetup.participants.any { it.userId == userId }
    val setup = meetup.status == MeetupStatus.SETUP
    val decided = meetup.status == MeetupStatus.FINALIZED || meetup.status == MeetupStatus.SETTLED

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MeetupDateTimeCard(scheduledAt = meetup.scheduledAt)

        if (decided || meetup.winner != null) {
            MeetupMeetingSpotCard(
                winner = meetup.winner,
                showCalendar = submitted,
                calendarSection = calendarSection,
            )
        }

        if (setup) {
            MeetupSetupStatusCard(
                submitted = submitted,
                participantCount = meetup.participants.size,
            )
            MeetupSetupActionButtons(
                isOrganizer = meetup.organizerId == userId,
                submitted = submitted,
                onEnterDetails = actions.onEnterDetails,
                onStartVoting = actions.onStartVoting,
            )
        }

        if (meetup.status == MeetupStatus.VOTING) {
            MeetupVotingStatusCard(votes = votes)
            MeetupVotingActionButton(onOpenVoting = actions.onOpenVoting)
        }

        if (decided) {
            if (submitted) {
                MeetupTripStatusSection(
                    meetupId = meetup.id,
                    ownTrip = ownTrip,
                    container = container,
                    onOpenLiveLocation = actions.onOpenLiveLocation,
                )
            }
            MeetupFinalizedActionButtons(
                onOpenLiveLocation = actions.onOpenLiveLocation,
                onOpenVoting = actions.onOpenVoting,
            )
        }
    }
}

@Composable
private fun MeetupDateTimeCard(scheduledAt: String) {
    val dateParts = parseFormattedDateParts(scheduledAt)
    MeetFairCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TealContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = CalendarIcon,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = dateParts.date,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
                Text(
                    text = dateParts.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeetupDirectionsButton(winner: PlaceCandidate) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val uri = if (winner.lat != 0.0 || winner.lng != 0.0) {
                Uri.parse("geo:0,0?q=${winner.lat},${winner.lng}(${Uri.encode(winner.name)})")
            } else {
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode("${winner.name}, ${winner.address}")}")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode("${winner.name}, ${winner.address}")}"))
                context.startActivity(fallback)
            }
        },
        shape = CircleShape,
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier.testTag("open_directions_button"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = DirectionsArrowIcon,
                contentDescription = null,
                tint = TealPrimary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Directions",
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
        }
    }
}

@Composable
private fun MeetupMeetingSpotCard(
    winner: PlaceCandidate?,
    showCalendar: Boolean,
    calendarSection: @Composable () -> Unit,
) {
    MeetFairCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.meetfair_map_meetup),
                contentDescription = "Meetup map graphic",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "MEETING SPOT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            text = winner?.name ?: "Pending selection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TealPrimary,
                            modifier = Modifier.testTag(TestTags.MEETUP_WINNER_TEXT),
                        )
                    }

                    if (winner != null) {
                        MeetupDirectionsButton(winner = winner)
                    }
                }

                if (showCalendar) calendarSection()
            }
        }
    }
}

@Composable
private fun MeetupSetupStatusCard(submitted: Boolean, participantCount: Int) {
    MeetFairCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TealContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LockIcon,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (submitted) "Your details are in" else "You have not submitted your details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                    modifier = Modifier.testTag(TestTags.PARTICIPATION_STATUS_TEXT),
                )
                Text(
                    text = "$participantCount member(s) have submitted their details.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeetupVotingStatusCard(votes: VotesSnapshot?) {
    val myVote = votes?.myVote
    val isLocked = myVote?.locked == true
    val hasSelection = myVote != null
    val votedCount = votes?.tally?.lockedCount ?: 0

    MeetFairCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TealContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = BarChartIcon,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        isLocked -> "Your vote is in"
                        hasSelection -> "Vote selected (not locked yet)"
                        else -> "You have not voted yet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                    modifier = Modifier.testTag("voting_status_text"),
                )
                Text(
                    text = "$votedCount member(s) have locked their vote.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeetupSetupActionButtons(
    isOrganizer: Boolean,
    submitted: Boolean,
    onEnterDetails: () -> Unit,
    onStartVoting: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onEnterDetails,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = TealPrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag(TestTags.PARTICIPATION_BUTTON),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (submitted) Icons.Default.Edit else Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (submitted) "Edit my details" else "Enter my details",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White,
                )
            }
        }

        if (isOrganizer) {
            Button(
                onClick = onStartVoting,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = RosePrimary,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag(TestTags.START_VOTING_BUTTON),
            ) {
                Text(
                    text = "Start voting",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                )
            }
        }
    }
}

@Composable
private fun MeetupVotingActionButton(onOpenVoting: () -> Unit) {
    Button(
        onClick = onOpenVoting,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TealPrimary,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag(TestTags.OPEN_VOTING_BUTTON),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = BarChartIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Vote on a spot",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun MeetupTripArrivedCard(trip: com.example.cpen321application.data.MeetupTrip) {
    MeetFairCard(
        containerColor = StatusGreenLight,
        borderWidth = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(
                    shape = CircleShape,
                    color = StatusGreen,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = FlagIcon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "You arrived",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = StatusGreen,
                    )
                    Text(
                        text = "Actual commute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatTripDuration(trip.actualSeconds ?: 0),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = StatusGreen,
            )
        }
    }
}

@Composable
private fun MeetupTripOnWayCard() {
    MeetFairCard(
        containerColor = TealContainer,
        borderWidth = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = TealPrimary,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TimerIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "On your way",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
                Text(
                    text = "We're timing your commute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeetupTripStatusSection(
    meetupId: String,
    ownTrip: com.example.cpen321application.data.MeetupTrip?,
    container: AppContainer,
    onOpenLiveLocation: () -> Unit,
) {
    when {
        ownTrip?.status == "ARRIVED" -> MeetupTripArrivedCard(trip = ownTrip)
        ownTrip != null -> MeetupTripOnWayCard()
        else -> StartTripButton(
            container = container,
            meetupId = meetupId,
            label = "I'm leaving",
            onStarted = onOpenLiveLocation,
        )
    }
}

@Composable
private fun MeetupFinalizedActionButtons(
    onOpenLiveLocation: () -> Unit,
    onOpenVoting: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onOpenLiveLocation,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = TealContainer,
                contentColor = TealPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "View live locations",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = TealPrimary,
                )
            }
        }

        OutlinedButton(
            onClick = onOpenVoting,
            shape = CircleShape,
            border = BorderStroke(1.dp, TealPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag(TestTags.OPEN_VOTING_BUTTON),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = BarChartIcon,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "See voting results",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = TealPrimary,
                )
            }
        }
    }
}

