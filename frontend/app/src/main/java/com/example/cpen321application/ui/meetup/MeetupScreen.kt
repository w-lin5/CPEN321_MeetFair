package com.example.cpen321application.ui.meetup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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
import com.example.cpen321application.data.CalendarAuthorizer
import com.example.cpen321application.data.GroupRepository
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.MeetupTrip
import com.example.cpen321application.data.VotingRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.VotesSnapshot
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.MeetupStatusNotifications
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toUiState
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.core.net.toUri

// Icons

internal fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.Black), pathBuilder = path)
        .build()

private val LockIcon = icon("Lock") {
    moveTo(18f, 8f); horizontalLineTo(17f); verticalLineTo(6f); curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
    curveTo(9.24f, 1f, 7f, 3.24f, 7f, 6f); verticalLineTo(8f); horizontalLineTo(6f)
    curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f); verticalLineTo(20f); curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
    horizontalLineTo(18f); curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f); verticalLineTo(10f)
    curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f); close()
    moveTo(9f, 6f); curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f); curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
    verticalLineTo(8f); horizontalLineTo(9f); verticalLineTo(6f); close()
}

private val DirectionsArrowIcon = icon("DirectionsArrow") {
    moveTo(21f, 3f); lineTo(3f, 10.53f); verticalLineTo(11.51f); lineTo(9.84f, 14.16f)
    lineTo(12.49f, 21f); horizontalLineTo(13.47f); lineTo(21f, 3f); close()
}

private val BarChartIcon = icon("BarChart") {
    moveTo(5f, 9.2f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(5f); verticalLineTo(9.2f); close()
    moveTo(10.6f, 5f); horizontalLineTo(13.4f); verticalLineTo(19f); horizontalLineTo(10.6f); verticalLineTo(5f); close()
    moveTo(16.2f, 13f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(16.2f); verticalLineTo(13f); close()
}

private val TimerIcon = icon("Timer") {
    moveTo(15f, 1f); horizontalLineTo(9f); verticalLineTo(3f); horizontalLineTo(15f); verticalLineTo(1f); close()
    moveTo(11f, 14f); horizontalLineTo(13f); verticalLineTo(9f); horizontalLineTo(11f); verticalLineTo(14f); close()
    moveTo(19.03f, 7.39f); lineTo(20.45f, 5.97f); curveTo(20.02f, 5.46f, 19.55f, 5.01f, 19.04f, 4.6f)
    lineTo(17.62f, 6.02f); curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f); curveTo(7.03f, 4f, 3f, 8.03f, 3f, 13f)
    curveTo(3f, 17.97f, 7.02f, 22f, 12f, 22f); curveTo(16.97f, 22f, 21f, 17.97f, 21f, 13f)
    curveTo(21f, 10.88f, 20.26f, 8.93f, 19.03f, 7.39f); close()
    moveTo(12f, 20f); curveTo(8.13f, 20f, 5f, 16.87f, 5f, 13f); curveTo(5f, 9.13f, 8.13f, 6f, 12f, 6f)
    curveTo(15.87f, 6f, 19f, 9.13f, 19f, 13f); curveTo(19f, 16.87f, 15.87f, 20f, 12f, 20f); close()
}

private val FlagIcon = icon("Flag") {
    moveTo(14.4f, 6f); lineTo(14f, 4f); horizontalLineTo(5f); verticalLineTo(21f); horizontalLineTo(7f)
    verticalLineTo(14f); horizontalLineTo(12.6f); lineTo(13f, 16f); horizontalLineTo(20f); verticalLineTo(6f)
    horizontalLineTo(14.4f); close()
}

private val SwapHorizontalIcon = icon("SwapHorizontal") {
    moveTo(6.99f, 11f); lineTo(3f, 15f); lineTo(6.99f, 19f); verticalLineTo(16f); horizontalLineTo(14f)
    verticalLineTo(14f); horizontalLineTo(6.99f); verticalLineTo(11f); close()
    moveTo(17.01f, 13f); lineTo(21f, 9f); lineTo(17.01f, 5f); verticalLineTo(8f); horizontalLineTo(10f)
    verticalLineTo(10f); horizontalLineTo(17.01f); verticalLineTo(13f); close()
}

// ViewModel

/** Long-running actions the screen can trigger. Only one runs at a time. */
enum class MeetupAction { CALENDAR, DELETE, LEAVE, SETTLE }

/** State of the single in-flight (or last failed) action. */
data class ActionState(
    val running: MeetupAction? = null,
    val error: String? = null,
    val calendarAdded: Boolean = false,
)

/** Data loaded alongside the meetup itself. */
data class MeetupExtras(
    val organizerName: String? = null,
    val votes: VotesSnapshot? = null,
    val ownTrip: MeetupTrip? = null,
)

/** One-shot UI effects emitted by the ViewModel. */
sealed interface MeetupEffect {
    data class Notify(val message: String) : MeetupEffect
    object Exit : MeetupEffect
}

/** Loads one meetup for the status hub, keeps it live via realtime events, and runs its actions. */
class MeetupViewModel(
    private val meetups: MeetupRepository,
    private val groups: GroupRepository,
    private val voting: VotingRepository,
    private val meetupId: String,
    val userId: String?,
    private val events: () -> Flow<MeetupEvent>,
    /** Returns every trip of this meetup, or null if the request failed. */
    private val loadTrips: suspend () -> List<MeetupTrip>?,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Meetup>>(UiState.Loading)
    val state: StateFlow<UiState<Meetup>> = _state.asStateFlow()

    private val _extras = MutableStateFlow(MeetupExtras())
    val extras: StateFlow<MeetupExtras> = _extras.asStateFlow()

    private val _action = MutableStateFlow(ActionState())
    val action: StateFlow<ActionState> = _action.asStateFlow()

    private val _effects = Channel<MeetupEffect>(Channel.BUFFERED)
    val effects: Flow<MeetupEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var exited = false


    fun load() {
        refresh(showLoading = true)
        viewModelScope.launch { refreshTrip() }
    }

    private fun refresh(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) _state.value = UiState.Loading
            val result = meetups.get(meetupId)
            if (result is ApiResult.Success) {
                _state.value = result.toUiState()
                loadOrganizer(result.data)
                if (result.data.status == MeetupStatus.VOTING) {
                    val votesResult = voting.votes(meetupId)
                    if (votesResult is ApiResult.Success) _extras.update { it.copy(votes = votesResult.data) }
                }
            } else if (_state.value !is UiState.Success) {
                _state.value = result.toUiState()
            }
        }
    }

    private fun loadOrganizer(meetup: Meetup) {
        viewModelScope.launch {
            val detail = groups.detail(meetup.groupId)
            if (detail is ApiResult.Success) {
                val name = detail.data.members.find { it.userId == meetup.organizerId }?.name
                _extras.update { it.copy(organizerName = name) }
            }
        }
    }

    private suspend fun refreshTrip() {
        val trips = loadTrips() ?: return
        _extras.update { it.copy(ownTrip = trips.find { trip -> trip.userId == userId }) }
    }


    /** Collects realtime events until cancelled; call from a lifecycle-bound effect. */
    suspend fun listen() {
        events().collect { event ->
            when (event) {
                is MeetupEvent.Joined -> { refresh(showLoading = false); refreshTrip() }
                is MeetupEvent.RecommendationsReady -> refresh(showLoading = false)
                is MeetupEvent.StatusChanged -> applyStatusChange(event)
                is MeetupEvent.TripUpdated -> refreshTrip()
                is MeetupEvent.Deleted ->
                    if (_action.value.running != MeetupAction.DELETE) {
                        exit(if (event.notifyParticipant) "This meetup has been deleted by its organizer." else null)
                    }
                else -> Unit
            }
        }
    }

    private fun applyStatusChange(event: MeetupEvent.StatusChanged) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        loadJob?.cancel()
        _state.value = UiState.Success(current.copy(status = event.status, winner = event.winner ?: current.winner))
    }

    private suspend fun exit(message: String?) {
        if (exited) return
        exited = true
        if (message != null) _effects.send(MeetupEffect.Notify(message))
        _effects.send(MeetupEffect.Exit)
    }

    private fun <T> runAction(
        kind: MeetupAction,
        call: suspend () -> ApiResult<T>,
        onSuccess: suspend (T) -> Unit,
    ) {
        if (_action.value.running != null) return
        _action.update { it.copy(running = kind, error = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    onSuccess(result.data)
                    _action.update { it.copy(running = null) }
                }
                is ApiResult.Failure -> _action.update { it.copy(running = null, error = result.error.message) }
            }
        }
    }

    fun settle() = runAction(MeetupAction.SETTLE, call = { meetups.settle(meetupId) }) { settled ->
        loadJob?.cancel()
        _state.value = UiState.Success(settled)
        _effects.send(MeetupEffect.Notify("Fair Credit updated. View balances in the group's Fair Credit page."))
    }

    fun leaveMeetup() = runAction(MeetupAction.LEAVE, call = { meetups.leave(meetupId) }) {
        refresh(showLoading = false)
    }

    fun deleteMeetup() {
        if (exited) return
        runAction(MeetupAction.DELETE, call = { meetups.delete(meetupId) }) { exit("Meetup deleted.") }
    }

    /** [accessToken] is the short-lived calendar.events token from CalendarAuthorizer. */
    fun addToCalendar(accessToken: String) =
        runAction(MeetupAction.CALENDAR, call = { meetups.addToCalendar(meetupId, accessToken) }) {
            _action.update { it.copy(calendarAdded = true) }
        }

    fun setCalendarError(message: String) {
        _action.update { it.copy(error = message) }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) = viewModelFactory {
            initializer {
                MeetupViewModel(
                    meetups = c.meetupRepository,
                    groups = c.groupRepository,
                    voting = c.votingRepository,
                    meetupId = meetupId,
                    userId = c.sessionStore.currentUserId(),
                    events = { c.realtimeClient.meetupEvents(meetupId) },
                    loadTrips = {
                        val result = c.tripRepository.list(meetupId)
                        if (result is ApiResult.Success) result.data else null
                    },
                )
            }
        }
    }
}

// UI model

/** Everything the screen renders, with derived values defined once. */
internal data class MeetupUi(
    val meetup: Meetup,
    val userId: String?,
    val extras: MeetupExtras,
) {
    val votes get() = extras.votes
    val ownTrip get() = extras.ownTrip
    val isOrganizer get() = meetup.organizerId == userId
    val submitted get() = meetup.participants.any { it.userId == userId }
    val hasCalendarEvent get() = meetup.participants.any { it.userId == userId && it.calendarEventId != null }
    val organizerDisplay get() = if (isOrganizer) "you" else extras.organizerName ?: "Organizer"
    val showOptionsMenu get() = (meetup.status == MeetupStatus.SETUP && submitted) || isOrganizer
}

/** Navigation callbacks for the next steps the meetup screen can offer. */
private data class MeetupNavigation(
    val onBack: () -> Unit,
    val onEnterDetails: () -> Unit,
    val onStartVoting: () -> Unit,
    val onOpenVoting: () -> Unit,
    val onOpenLiveLocation: () -> Unit,
)

/** The confirmation dialog currently shown, if any. */
internal enum class Confirm { None, Settle, Leave, Delete }

// Screen

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
    val extras by vm.extras.collectAsState()
    val action by vm.action.collectAsState()
    val notify by rememberUpdatedState(LocalNotify.current)
    val goBack by rememberUpdatedState(onBack)

    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm) { vm.listen() }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is MeetupEffect.Notify -> notify(effect.message)
                MeetupEffect.Exit -> goBack()
            }
        }
    }
    BackHandler(onBack = onBack)
    MeetupStatusNotifications((state as? UiState.Success)?.data)

    val navigation = MeetupNavigation(onBack, onEnterDetails, onStartVoting, onOpenVoting, onOpenLiveLocation)
    val startTrip: @Composable () -> Unit = {
        StartTripButton(container, meetupId, onStarted = onOpenLiveLocation)
    }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.ScreenPadding)) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { meetup ->
            MeetupBody(
                ui = MeetupUi(meetup, vm.userId, extras),
                action = action,
                vm = vm,
                navigation = navigation,
                authorizer = container.calendarAuthorizer,
                startTrip = startTrip,
            )
        }
    }
}

@Composable
private fun MeetupBody(
    ui: MeetupUi,
    action: ActionState,
    vm: MeetupViewModel,
    navigation: MeetupNavigation,
    authorizer: CalendarAuthorizer,
    startTrip: @Composable () -> Unit,
) {
    val meetup = ui.meetup
    val status = meetup.status
    val isDone = status == MeetupStatus.FINALIZED || status == MeetupStatus.SETTLED
    var confirm by rememberSaveable { mutableStateOf(Confirm.None) }
    var showMenu by remember { mutableStateOf(false) }

    MeetupDialogs(confirm, meetup, onDismiss = { confirm = Confirm.None }) { chosen ->
        confirm = Confirm.None
        when (chosen) {
            Confirm.Settle -> vm.settle()
            Confirm.Leave -> vm.leaveMeetup()
            Confirm.Delete -> vm.deleteMeetup()
            Confirm.None -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MeetupHeader(ui, onBack = navigation.onBack, onMenuClick = { showMenu = true }) {
            MeetupOptionsMenu(
                expanded = showMenu,
                ui = ui,
                running = action.running,
                onDismiss = { showMenu = false },
                onLeave = { showMenu = false; confirm = Confirm.Leave },
                onDelete = { showMenu = false; confirm = Confirm.Delete },
            )
        }
        action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status == MeetupStatus.SETTLED) {
            Text("Fair Credit settled. View updated balances and history in the group's Fair Credit page.")
        }
        MeetupDateTimeCard(meetup.scheduledAt) {
            SmallCalendarButton(
                authorizer = authorizer,
                submitting = action.running == MeetupAction.CALENDAR,
                added = ui.hasCalendarEvent || action.calendarAdded,
                onToken = vm::addToCalendar,
                onError = vm::setCalendarError,
            )
        }
        if (isDone || meetup.winner != null) MeetupMeetingSpotCard(meetup.winner)
        when (status) {
            MeetupStatus.SETUP -> MeetupSetupStatusCard(ui.submitted, meetup.participants.size)
            MeetupStatus.VOTING -> MeetupVotingStatusCard(ui.votes)
            else -> Unit
        }
        if (isDone) MeetupNavCard(navigation.onOpenLiveLocation, navigation.onOpenVoting)
        MeetupBottomBar(ui, action.running, navigation, onSettle = { confirm = Confirm.Settle }, startTrip = startTrip)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// Meeting spot and navigation

@Composable
private fun MeetupDirectionsButton(winner: PlaceCandidate) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val webUrl = "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode("${winner.name}, ${winner.address}")}"
            val preferred = if (winner.lat != 0.0 || winner.lng != 0.0) {
                "geo:0,0?q=${winner.lat},${winner.lng}(${Uri.encode(winner.name)})".toUri()
            } else {
                webUrl.toUri()
            }
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, preferred))
            } catch (_: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, webUrl.toUri()))
            }
        },
        shape = CircleShape,
        border = BorderStroke(1.dp, TealPrimary),
        modifier = Modifier.testTag("open_directions_button"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(imageVector = DirectionsArrowIcon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(16.dp))
            Text(text = "Directions", fontWeight = FontWeight.Bold, color = TealPrimary)
        }
    }
}

@Composable
private fun MeetupMeetingSpotCard(winner: PlaceCandidate?) {
    MeetFairCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.meetfair_map_meetup),
                contentDescription = "Meetup map graphic",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                if (winner != null) MeetupDirectionsButton(winner)
            }
        }
    }
}

@Composable
private fun MeetupNavCard(onOpenLiveLocation: () -> Unit, onOpenVoting: () -> Unit) {
    MeetFairCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            MeetupNavRow(icon = Icons.Default.LocationOn, label = "View live locations", onClick = onOpenLiveLocation, testTag = null)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            MeetupNavRow(icon = BarChartIcon, label = "See voting results", onClick = onOpenVoting, testTag = TestTags.OPEN_VOTING_BUTTON)
        }
    }
}

@Composable
private fun MeetupNavRow(icon: ImageVector, label: String, onClick: () -> Unit, testTag: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(20.dp))
            Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = TealPrimary)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// Status cards

@Composable
private fun StatusInfoCard(icon: ImageVector, title: String, subtitle: String, titleTag: String) {
    MeetFairCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = TealContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null, tint = TealPrimary, modifier = Modifier.size(24.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                    modifier = Modifier.testTag(titleTag),
                )
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MeetupSetupStatusCard(submitted: Boolean, participantCount: Int) {
    StatusInfoCard(
        icon = LockIcon,
        title = if (submitted) "Your details are in" else "You have not submitted your details",
        subtitle = "$participantCount member(s) have submitted their details.",
        titleTag = TestTags.PARTICIPATION_STATUS_TEXT,
    )
}

@Composable
private fun MeetupVotingStatusCard(votes: VotesSnapshot?) {
    val myVote = votes?.myVote
    StatusInfoCard(
        icon = BarChartIcon,
        title = when {
            myVote?.locked == true -> "Your vote is in"
            myVote != null -> "Vote selected (not locked yet)"
            else -> "You have not voted yet"
        },
        subtitle = "${votes?.tally?.lockedCount ?: 0} member(s) have locked their vote.",
        titleTag = "voting_status_text",
    )
}

// Trip cards

@Composable
private fun MeetupTripStatusCard(trip: MeetupTrip) {
    if (trip.status == "ARRIVED") MeetupTripArrivedCard(trip) else MeetupTripOnWayCard()
}

@Composable
private fun MeetupTripArrivedCard(trip: MeetupTrip) {
    MeetFairCard(containerColor = StatusGreenLight, borderWidth = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(shape = CircleShape, color = StatusGreen, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = FlagIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "You arrived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = StatusGreen)
                    Text(text = "Actual commute", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    MeetFairCard(containerColor = TealContainer, borderWidth = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = TealPrimary, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = TimerIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "On your way", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = TealPrimary)
                Text(text = "We're timing your commute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Bottom bar

@Composable
private fun MeetupBottomBar(
    ui: MeetupUi,
    running: MeetupAction?,
    navigation: MeetupNavigation,
    onSettle: () -> Unit,
    startTrip: @Composable () -> Unit,
) {
    val trip = ui.ownTrip
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        when (ui.meetup.status) {
            MeetupStatus.SETUP -> MeetupSetupActionButtons(
                isOrganizer = ui.isOrganizer,
                submitted = ui.submitted,
                onEnterDetails = navigation.onEnterDetails,
                onStartVoting = navigation.onStartVoting,
            )
            MeetupStatus.VOTING -> MeetupVotingActionButton(navigation.onOpenVoting)
            MeetupStatus.FINALIZED -> {
                if (trip == null) startTrip() else MeetupTripStatusCard(trip)
                if (ui.isOrganizer) {
                    SettleFairCreditButton(
                        submitting = running == MeetupAction.SETTLE,
                        enabled = running == null,
                        isPrimary = trip?.status == "ARRIVED",
                        onClick = onSettle,
                    )
                }
            }
            MeetupStatus.SETTLED -> if (trip != null) MeetupTripStatusCard(trip)
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
            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.PARTICIPATION_BUTTON),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.START_VOTING_BUTTON),
            ) {
                Text(text = "Start voting", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            }
        }
    }
}

@Composable
private fun MeetupVotingActionButton(onOpenVoting: () -> Unit) {
    Button(
        onClick = onOpenVoting,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(54.dp).testTag(TestTags.OPEN_VOTING_BUTTON),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = BarChartIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(text = "Vote on a spot", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
        }
    }
}

@Composable
private fun SettleFairCreditButton(
    submitting: Boolean,
    enabled: Boolean,
    isPrimary: Boolean,
    onClick: () -> Unit,
) {
    val buttonModifier = Modifier.fillMaxWidth().height(54.dp).testTag("settle_fair_credit")
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = SwapHorizontalIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = if (submitting) "Settling..." else "Settle Fair Credit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
    if (isPrimary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RosePrimary,
                contentColor = Color.White,
                disabledContainerColor = RosePrimary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
            modifier = buttonModifier,
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(1.dp, RosePrimary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RosePrimary),
            modifier = buttonModifier,
        ) { label() }
    }
}
