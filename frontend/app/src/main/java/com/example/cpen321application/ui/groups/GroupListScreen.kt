package com.example.cpen321application.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
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
import com.example.cpen321application.model.Group
import com.example.cpen321application.model.GroupMember
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairHeader
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.RosePrimary
import com.example.cpen321application.ui.theme.TealContainer
import com.example.cpen321application.ui.theme.TealPrimary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val PlusIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Plus",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(Color.White)) {
        moveTo(19f, 13f)
        horizontalLineTo(13f)
        verticalLineTo(19f)
        horizontalLineTo(11f)
        verticalLineTo(13f)
        horizontalLineTo(5f)
        verticalLineTo(11f)
        horizontalLineTo(11f)
        verticalLineTo(5f)
        horizontalLineTo(13f)
        verticalLineTo(11f)
        horizontalLineTo(19f)
        verticalLineTo(13f)
        close()
    }.build()

private val LinkIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Link",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(fill = SolidColor(TealPrimary)) {
        moveTo(3.9f, 12f)
        curveTo(3.9f, 10.29f, 5.29f, 8.9f, 7f, 8.9f)
        horizontalLineTo(11f)
        verticalLineTo(7f)
        horizontalLineTo(7f)
        curveTo(4.24f, 7f, 2f, 9.24f, 2f, 12f)
        curveTo(2f, 14.76f, 4.24f, 17f, 7f, 17f)
        horizontalLineTo(11f)
        verticalLineTo(15.1f)
        horizontalLineTo(7f)
        curveTo(5.29f, 15.1f, 3.9f, 13.71f, 3.9f, 12f)
        close()
        moveTo(8f, 13f)
        horizontalLineTo(16f)
        verticalLineTo(11f)
        horizontalLineTo(8f)
        verticalLineTo(13f)
        close()
        moveTo(17f, 7f)
        horizontalLineTo(13f)
        verticalLineTo(8.9f)
        horizontalLineTo(17f)
        curveTo(18.71f, 8.9f, 20.1f, 10.29f, 20.1f, 12f)
        curveTo(20.1f, 13.71f, 18.71f, 15.1f, 17f, 15.1f)
        horizontalLineTo(13f)
        verticalLineTo(17f)
        horizontalLineTo(17f)
        curveTo(19.76f, 17f, 22f, 14.76f, 22f, 12f)
        curveTo(22f, 9.24f, 19.76f, 7f, 17f, 7f)
        close()
    }.build()

data class GroupWithAction(
    val group: Group,
    val actionNeeded: String? = null,
)

/** Loads the groups the signed-in user belongs to along with any required action indicators. */
class GroupListViewModel(
    private val groups: GroupRepository,
    private val meetups: MeetupRepository,
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
                is ApiResult.Success -> {
                    val groupList = result.data
                    val items = groupList.map { group ->
                        val action = determineGroupAction(group.id)
                        GroupWithAction(group, action)
                    }
                    _state.value = UiState.Success(items)
                }
                is ApiResult.Failure -> {
                    _state.value = UiState.Error(result.error.message)
                }
            }
        }
    }

    private suspend fun determineGroupAction(groupId: String): String? {
        val meetupResult = meetups.listForGroup(groupId)
        if (meetupResult !is ApiResult.Success) return null
        val meetupList = meetupResult.data

        // 1. Check for voting required
        val hasVoting = meetupList.any { it.status == MeetupStatus.VOTING }
        if (hasVoting) return "Vote required"

        // 2. Check for setup details needed
        val needsDetails = meetupList.any { meetup ->
            meetup.status == MeetupStatus.SETUP &&
                currentUserId != null &&
                meetup.participants.none { it.userId == currentUserId }
        }
        if (needsDetails) return "Enter details"

        return null
    }

    companion object {
        fun factory(c: AppContainer) = viewModelFactory {
            initializer {
                GroupListViewModel(
                    c.groupRepository,
                    c.meetupRepository,
                    c.sessionStore.currentUserId(),
                )
            }
        }
    }
}

@Composable
fun GroupListScreen(
    container: AppContainer,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpen: (String) -> Unit,
    onProfile: () -> Unit,
) {
    val vm: GroupListViewModel = screenViewModel(GroupListViewModel.factory(container))
    val state by vm.state.collectAsState()
    val notify = LocalNotify.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(container) {
        container.realtimeClient.groupEvents().collect { event ->
            vm.onGroupEvent(event)
            if (event is GroupEvent.Deleted) notify("A group was deleted by its organizer.")
        }
    }

    GroupListContent(
        state = state,
        onCreate = onCreate,
        onJoin = onJoin,
        onOpen = onOpen,
        onProfile = onProfile,
        onRetry = vm::load,
    )
}

@Composable
fun GroupListContent(
    state: UiState<List<GroupWithAction>>,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onOpen: (String) -> Unit,
    onProfile: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MeetFairHeader(onProfile = onProfile)

        Text(
            text = "Your groups",
            style = MaterialTheme.typography.headlineLarge,
            color = TealPrimary,
            fontWeight = FontWeight.ExtraBold,
        )

        GroupActionButtons(onCreate = onCreate, onJoin = onJoin)

        UiStateContent(state, onRetry = onRetry, emptyMessage = "No groups yet. Create one or join with a code.") { groups ->
            GroupSections(groups, onOpen)
        }
    }
}

@Composable
private fun GroupActionButtons(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onCreate,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = TealPrimary,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag(TestTags.CREATE_GROUP_BUTTON),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = PlusIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text("Create group", fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onJoin,
            shape = CircleShape,
            border = BorderStroke(1.dp, TealPrimary),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag(TestTags.JOIN_GROUP_BUTTON),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = LinkIcon,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Text("Join group", color = TealPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GroupSections(groups: List<GroupWithAction>, onOpen: (String) -> Unit) {
    val needsYouGroups = groups.filter { it.actionNeeded != null }
    val caughtUpGroups = groups.filter { it.actionNeeded == null }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (needsYouGroups.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "NEEDS YOU",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = RosePrimary,
                        letterSpacing = 1.2.sp,
                    )

                    Surface(
                        shape = CircleShape,
                        color = RosePrimary,
                        modifier = Modifier.size(20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = needsYouGroups.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }

                needsYouGroups.forEach { item ->
                    GroupCard(item, onOpen)
                }
            }
        }

        if (caughtUpGroups.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "ALL CAUGHT UP",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp,
                )

                caughtUpGroups.forEach { item ->
                    GroupCard(item, onOpen)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(item: GroupWithAction, onOpen: (String) -> Unit) {
    val group = item.group
    val memberCountText = if (group.members.size == 1) "1 member" else "${group.members.size} members"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(group.id) }
            .testTag(TestTags.GROUP_ITEM),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, TealContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GroupIdentityRow(
                groupName = group.name,
                memberCountText = memberCountText,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (item.actionNeeded != null) {
                GroupActionBadge(action = item.actionNeeded)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupIdentityRow(
    groupName: String,
    memberCountText: String,
    modifier: Modifier = Modifier,
) {
    val initial = groupName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = TealContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = TealPrimary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = groupName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TealPrimary,
            )
            Text(
                text = memberCountText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupActionBadge(action: String) {
    Surface(
        shape = CircleShape,
        color = RosePrimary.copy(alpha = 0.15f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = RosePrimary,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = RosePrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

