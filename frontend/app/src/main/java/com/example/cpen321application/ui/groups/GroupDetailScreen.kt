package com.example.cpen321application.ui.groups

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.Instant
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.DeleteConfirmation
import com.example.cpen321application.ui.common.FormState
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairCard
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.common.toFormState
import com.example.cpen321application.ui.meetup.MeetupStatusSpec
import com.example.cpen321application.ui.meetup.getStatusSpec
import com.example.cpen321application.ui.meetup.isoToDisplay
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.StatusGreen
import com.example.cpen321application.ui.theme.StatusGreenLight
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val CopyIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f)
        lineTo(4f, 1f)
        curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
        lineTo(2f, 17f)
        horizontalLineTo(4f)
        lineTo(4f, 3f)
        horizontalLineTo(16f)
        verticalLineTo(1f)
        close()
        moveTo(19f, 5f)
        lineTo(8f, 5f)
        curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
        lineTo(6f, 21f)
        curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
        horizontalLineTo(19f)
        curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
        lineTo(21f, 7f)
        curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
        close()
        moveTo(19f, 21f)
        lineTo(8f, 21f)
        lineTo(8f, 7f)
        horizontalLineTo(19f)
        verticalLineTo(21f)
        close()
    }.build()

/** A group together with its meetups, loaded as one unit so the screen has a single loading/error state. */
data class GroupDetailData(
    val group: Group,
    val meetups: List<Meetup>,
    val hasTrackingStage: Boolean = false,
)
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

    fun leaveGroup() {
        if (_leaving.value.submitting || _leaving.value.result != null) return
        if (_deletion.value.submitting || _removal.value.pending) return
        _leaving.value = FormState(submitting = true)
        viewModelScope.launch { _leaving.value = groups.leave(groupId).toFormState() }
    }
    private var deleted = false
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()
    private var loadJob: Job? = null

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

    private suspend fun isTrackingStage(meetupList: List<Meetup>): Boolean {
        return meetupList.any { meetup ->
            meetup.status == MeetupStatus.FINALIZED && (
                runCatching { !Instant.now().isBefore(Instant.parse(meetup.scheduledAt)) }.getOrDefault(false) ||
                    trips.list(meetup.id).let { tripResult ->
                        tripResult is ApiResult.Success && tripResult.data.isNotEmpty()
                    }
                )
        }
    }

    fun load() {
        if (deleted) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            val groupRes = groups.detail(groupId)
            if (groupRes is ApiResult.Failure && groupRes.error.httpCode == 404) {
                deleted = true
                _missing.value = true
                _state.value = UiState.Empty
            } else if (groupRes is ApiResult.Success) {
                val meetupRes = meetups.listForGroup(groupId)
                if (meetupRes is ApiResult.Success) {
                    val tracking = isTrackingStage(meetupRes.data)
                    _state.value = UiState.Success(GroupDetailData(groupRes.data, meetupRes.data, tracking))
                } else if (meetupRes is ApiResult.Failure) {
                    _state.value = UiState.Error(meetupRes.error.message)
                }
            } else if (groupRes is ApiResult.Failure) {
                _state.value = UiState.Error(groupRes.error.message)
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, groupId: String) = viewModelFactory {
            initializer { GroupDetailViewModel(c.groupRepository, c.meetupRepository, c.tripRepository, groupId) }
        }
    }
}

@Composable
fun GroupDetailScreen(
    container: AppContainer,
    groupId: String,
    onBack: () -> Unit,
    onNewMeetup: () -> Unit,
    onOpenMeetup: (String) -> Unit,
    onOpenFairness: () -> Unit = {},
) {
    val vm: GroupDetailViewModel = screenViewModel(GroupDetailViewModel.factory(container, groupId))
    val state by vm.state.collectAsState()
    val removal by vm.removal.collectAsState()
    val deletion by vm.deletion.collectAsState()
    val leaving by vm.leaving.collectAsState()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val notify = LocalNotify.current
    val missing by vm.missing.collectAsState()
    var exited by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(onBack = onBack)

    LaunchedEffect(container, groupId) {
        container.realtimeClient.groupEvents().collect { event ->
            vm.onGroupEvent(event)
            when {
                event is GroupEvent.Deleted && event.groupId == groupId && !vm.deletion.value.submitting &&
                    vm.deletion.value.result == null && !exited -> {
                    exited = true
                    notify("This group has been deleted by its organizer.")
                    onBack()
                }
                event is GroupEvent.MeetupDeleted && event.groupId == groupId && event.notifyParticipant ->
                    notify("A meetup was deleted. The list has been updated.")
            }
        }
    }
    LaunchedEffect(deletion.result) {
        if (deletion.result != null && !exited) { exited = true; notify("Group deleted."); onBack() }
    }
    LaunchedEffect(missing) {
        if (missing && !exited) { exited = true; notify("This group is no longer available."); onBack() }
    }
    LaunchedEffect(leaving.result) {
        if (leaving.result != null && !exited) { exited = true; onBack() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(Dimens.ScreenPadding),
    ) {
        UiStateContent(state, onRetry = vm::load, emptyMessage = "") { data ->
            GroupInfo(
                data = data,
                currentUserId = container.sessionStore.currentUserId(),
                onNewMeetup = onNewMeetup,
                onOpenMeetup = onOpenMeetup,
                onOpenFairness = onOpenFairness,
                canRemove = data.group.organizerId == container.sessionStore.currentUserId(),
                removal = removal,
                onRemove = { selectedMemberId = it.userId },
                deletion = deletion,
                onDelete = { confirmDelete = true },
                leaving = leaving,
                onLeave = { confirmLeave = true },
                onBack = onBack,
            )

            if (confirmLeave) {
                val userId = container.sessionStore.currentUserId()
                val voting = data.meetups.any { meetup ->
                    meetup.status.name == "VOTING" && meetup.participants.any { it.userId == userId }
                }
                AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text(if (voting) "Cannot leave group" else "Leave group?") },
                    text = { Text(if (voting) "A meetup you joined is still voting. Wait until its voting ends before leaving."
                        else "Your details in meetups that have not started voting will be deleted. Completed voting and Fair Credit history will remain.") },
                    confirmButton = { TextButton(onClick = {
                        confirmLeave = false
                        if (!voting) vm.leaveGroup()
                    }) { Text(if (voting) "OK" else "Leave") } },
                    dismissButton = { if (!voting) TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
                )
            }
            if (confirmDelete) {
                DeleteConfirmation(
                    title = "Delete group?",
                    description = "Delete ${data.group.name} and all its meetups, participation details, votes and group Fair Credit data? This cannot be undone. External calendar events will remain.",
                    votingWarning = if (data.meetups.any { it.status.name == "VOTING" })
                        "Warning: this group still has meetups with voting in progress. Deleting it will end those votes." else null,
                    onDismiss = { confirmDelete = false },
                    onDelete = { confirmDelete = false; vm.deleteGroup() },
                )
            }
            val selected = data.group.members.firstOrNull { it.userId == selectedMemberId }
            if (selected != null) {
                val activeParticipation = data.meetups.any { meetup ->
                    (meetup.status.name == "VOTING" || meetup.status.name == "FINALIZED") &&
                        meetup.participants.any { it.userId == selected.userId }
                }
                AlertDialog(
                    onDismissRequest = { selectedMemberId = null },
                    title = { Text(if (activeParticipation) "Cannot remove member" else "Remove member?") },
                    text = {
                        Text(if (activeParticipation)
                            "This member submitted details for a meetup whose voting has started. Settle that meetup before removing them."
                        else "Remove ${selected.name ?: "this member"} from the group? Their meetup details and votes in this group will be deleted, and they will lose access.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedMemberId = null
                            if (!activeParticipation) vm.removeMember(selected.userId)
                        }) { Text(if (activeParticipation) "OK" else "Remove") }
                    },
                    dismissButton = {
                        if (!activeParticipation) {
                            TextButton(onClick = { selectedMemberId = null }) { Text("Cancel") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupTrackingBadge() {
    Surface(
        shape = CircleShape,
        color = StatusGreenLight,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StatusGreen,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Tracking live",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = StatusGreen,
            )
        }
    }
}

@Composable
private fun GroupInfo(
    data: GroupDetailData,
    currentUserId: String?,
    onNewMeetup: () -> Unit,
    onOpenMeetup: (String) -> Unit,
    onOpenFairness: () -> Unit,
    canRemove: Boolean,
    removal: MemberRemovalState,
    onRemove: (GroupMember) -> Unit,
    deletion: FormState<Unit>,
    onDelete: () -> Unit,
    leaving: FormState<Unit>,
    onLeave: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Top Bar & Title
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
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
                                if (canRemove) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (deletion.submitting) "Deleting..." else "Delete group",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onDelete()
                                        },
                                        enabled = !deletion.submitting && !removal.pending,
                                        modifier = Modifier.testTag("delete_group"),
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (leaving.submitting) "Leaving..." else "Leave group",
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onLeave()
                                        },
                                        enabled = !leaving.submitting,
                                        modifier = Modifier.testTag("leave_group"),
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "GROUP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.5.sp,
                            )
                            Text(
                                text = data.group.name,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = TealPrimary,
                            )
                        }
                        if (data.hasTrackingStage) {
                            GroupTrackingBadge()
                        }
                    }

                    leaving.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            // Invite Code Card
            item {
                MeetFairCard(
                    containerColor = TealContainer,
                    borderWidth = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "INVITE CODE",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TealPrimary.copy(alpha = 0.7f),
                                letterSpacing = 1.2.sp,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = data.group.inviteCode,
                                modifier = Modifier.testTag(TestTags.INVITE_CODE_TEXT),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.5.sp,
                                ),
                                color = TealPrimary,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invite code", data.group.inviteCode)))
                                        notify("Invite code copied")
                                    }
                                },
                                shape = CircleShape,
                                color = Color.White,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = CopyIcon,
                                        contentDescription = "Copy invite code",
                                        tint = TealPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, "Join my MeetFair group with invite code: ${data.group.inviteCode}")
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, null))
                                },
                                shape = CircleShape,
                                color = Color.White,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share invite code",
                                        tint = TealPrimary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Members Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Members",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = TealPrimary,
                        )
                        Text(
                            text = "${data.group.members.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (removal.pending) Text("Removing member...")
                    removal.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
                    }
                }
            }

            // Member Rows
            items(data.group.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    canRemove = canRemove,
                    removalPending = removal.pending,
                    deletionSubmitting = deletion.submitting,
                    onRemove = onRemove,
                )
            }
            item {
                OutlinedButton(
                    onClick = onOpenFairness,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, TealPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_fair_credit_button"),
                ) {
                    Text(
                        text = "View Group's Fair Credits",
                        fontWeight = FontWeight.Bold,
                        color = TealPrimary,
                    )
                }
            }

            // Meetups Header
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Meetups",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
            }

            if (data.meetups.isEmpty()) {
                item { Text("No meetups yet.", modifier = Modifier.testTag(TestTags.MEETUPS_EMPTY)) }
            }

            // Meetup Rows
            items(data.meetups, key = { it.id }) { meetup ->
                MeetupRow(meetup, data.group.members, currentUserId, onOpenMeetup)
            }

            // Bottom space for floating action button
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        // Floating Action Button for New Meetup
        Button(
            onClick = onNewMeetup,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = RosePrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp)
                .testTag(TestTags.NEW_MEETUP_BUTTON),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                )
                Text(
                    text = "New meetup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    canRemove: Boolean,
    removalPending: Boolean,
    deletionSubmitting: Boolean,
    onRemove: (GroupMember) -> Unit,
) {
    val isOrganizer = member.role == GroupRole.ORGANIZER

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(TestTags.MEMBER_ITEM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MemberIdentity(
            name = member.name,
            modifier = Modifier.weight(1f),
        )

        MemberTrailingAction(
            isOrganizer = isOrganizer,
            canRemove = canRemove,
            removalPending = removalPending,
            deletionSubmitting = deletionSubmitting,
            userId = member.userId,
            onRemove = { onRemove(member) },
        )
    }
}

@Composable
private fun MemberIdentity(
    name: String?,
    modifier: Modifier = Modifier,
) {
    val initial = (name ?: "U").take(1).uppercase()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
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
            text = name ?: "Unknown",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemberTrailingAction(
    isOrganizer: Boolean,
    canRemove: Boolean,
    removalPending: Boolean,
    deletionSubmitting: Boolean,
    userId: String,
    onRemove: () -> Unit,
) {
    if (isOrganizer) {
        Surface(
            shape = CircleShape,
            color = RosePrimary.copy(alpha = 0.15f),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(
                text = "Organizer",
                color = RosePrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    } else if (canRemove) {
        TextButton(
            onClick = onRemove,
            enabled = !removalPending && !deletionSubmitting,
            modifier = Modifier.testTag("remove_member_$userId"),
        ) {
            Text("Remove", color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class MeetupDateParts(val month: String, val day: String, val time: String)

private fun parseMeetupDateParts(iso: String): MeetupDateParts = try {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val month = zdt.format(DateTimeFormatter.ofPattern("MMM", Locale.US)).uppercase()
    val day = zdt.format(DateTimeFormatter.ofPattern("dd", Locale.US))
    val time = zdt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    MeetupDateParts(month, day, time)
} catch (_: Exception) {
    MeetupDateParts("OCT", "28", isoToDisplay(iso))
}

@Composable
private fun MeetupDateBadge(
    parts: MeetupDateParts,
    spec: MeetupStatusSpec,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = spec.backgroundColor,
        modifier = Modifier.size(width = 56.dp, height = 56.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = parts.month,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = spec.color.copy(alpha = 0.8f),
            )
            Text(
                text = parts.day,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = spec.color,
            )
        }
    }
}

@Composable
private fun MeetupStatusChip(
    spec: MeetupStatusSpec,
) {
    Surface(
        shape = CircleShape,
        color = spec.backgroundColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.color,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = spec.color,
            )
        }
    }
}

@Composable
private fun MeetupDetails(
    title: String,
    time: String,
    organizerDisplay: String,
    spec: MeetupStatusSpec,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Organizer: $organizerDisplay",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MeetupStatusChip(spec = spec)
    }
}

@Composable
private fun MeetupRow(
    meetup: Meetup,
    members: List<GroupMember>,
    currentUserId: String?,
    onOpen: (String) -> Unit,
) {
    val parts = parseMeetupDateParts(meetup.scheduledAt)
    val spec = getStatusSpec(meetup.status)
    val organizer = members.find { it.userId == meetup.organizerId }
    val organizerDisplay = if (meetup.organizerId == currentUserId) "You" else (organizer?.name ?: "Organizer")

    MeetFairCard(
        modifier = Modifier
            .clickable { onOpen(meetup.id) }
            .testTag(TestTags.MEETUP_ITEM),
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                MeetupDateBadge(parts = parts, spec = spec)
                MeetupDetails(
                    title = meetup.title,
                    time = parts.time,
                    organizerDisplay = organizerDisplay,
                    spec = spec,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open meetup",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

