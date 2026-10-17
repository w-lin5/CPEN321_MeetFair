package com.example.cpen321application.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.cpen321application.AppContainer
import com.example.cpen321application.ui.auth.SignInScreen
import com.example.cpen321application.ui.groups.CreateGroupScreen
import com.example.cpen321application.ui.groups.GroupDetailScreen
import com.example.cpen321application.ui.groups.GroupListScreen
import com.example.cpen321application.ui.groups.JoinGroupScreen

/** Token present -> app, token absent (sign-out or 401 status code) -> sign-in. */
@Composable
fun MeetFairApp(container: AppContainer) {
    val token by container.sessionStore.token.collectAsState()
    val current = token
    if (current == null) SignInScreen(container) else AuthedApp(container, current)
}

/** Signed in shell: owns the saveable back stack and routes system back to [Navigator.pop]. */
@Composable
private fun AuthedApp(container: AppContainer, token: String) {
    val navigator = rememberSaveable(saver = Navigator.Saver) { Navigator.initial() }
    BackHandler(enabled = navigator.stack.size > 1) { navigator.pop() }
    val entry = navigator.current
    key(entry.id) {
        CompositionLocalProvider(LocalEntryKey provides "${token.hashCode()}-${entry.id}") {
            RouteContent(container, navigator, entry.route)
        }
    }
}

@Composable
private fun RouteContent(container: AppContainer, navigator: Navigator, route: Route) {
    when (route) {
        Route.GroupList -> GroupListScreen(
            container,
            onCreate = { navigator.push(Route.CreateGroup) },
            onJoin = { navigator.push(Route.JoinGroup) },
            onOpen = { navigator.push(Route.GroupDetail(it)) },
        )
        Route.CreateGroup -> CreateGroupScreen(container, onBack = navigator::pop, onCreated = {
            navigator.pop()
            navigator.push(Route.GroupDetail(it))
        })
        Route.JoinGroup -> JoinGroupScreen(container, onBack = navigator::pop, onJoined = {
            navigator.pop()
            navigator.push(Route.GroupDetail(it))
        })
        is Route.GroupDetail -> GroupDetailScreen(container, route.groupId, onBack = navigator::pop)
    }
}
