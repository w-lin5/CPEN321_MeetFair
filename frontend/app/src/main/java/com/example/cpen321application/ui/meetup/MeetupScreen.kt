package com.example.cpen321application.ui.meetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.CalendarAuthorizer
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetupStatusNotifications
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens

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
internal data class MeetupNavigation(
    val onBack: () -> Unit,
    val onEnterDetails: () -> Unit,
    val onStartVoting: () -> Unit,
    val onOpenVoting: () -> Unit,
    val onOpenLiveLocation: () -> Unit,
)

/** The confirmation dialog currently shown, if any. */
internal enum class Confirm { None, Settle, Leave, Delete }

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
