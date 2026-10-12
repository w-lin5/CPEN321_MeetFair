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
import com.example.cpen321application.ui.groups.GroupListScreen

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
            RouteContent(container, entry.route)
        }
    }
}

@Composable
private fun RouteContent(container: AppContainer, route: Route) {
    when (route) {
        Route.GroupList -> GroupListScreen(container)
    }
}
