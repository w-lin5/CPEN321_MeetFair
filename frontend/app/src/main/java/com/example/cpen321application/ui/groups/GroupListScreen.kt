package com.example.cpen321application.ui.groups

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.GroupEvent
import com.example.cpen321application.ui.common.LocalNotify
import com.example.cpen321application.ui.common.MeetFairHeader
import com.example.cpen321application.ui.common.TestTags
import com.example.cpen321application.ui.common.UiState
import com.example.cpen321application.ui.common.UiStateContent
import com.example.cpen321application.ui.screenViewModel
import com.example.cpen321application.ui.theme.Dimens
import com.example.cpen321application.ui.theme.TealPrimary

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
            .padding(Dimens.ScreenPadding),
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

        Box(modifier = Modifier.weight(1f)) {
            UiStateContent(
                state,
                onRetry = onRetry,
                emptyMessage = "No groups yet. Create one or join with a code.",
            ) { groups ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GroupSections(groups, onOpen)
                }
            }
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
