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
import com.example.cpen321application.ui.meetup.CreateMeetupScreen
import com.example.cpen321application.ui.meetup.MeetupScreen
import com.example.cpen321application.ui.meetup.ParticipationScreen
import com.example.cpen321application.ui.profile.GroupFairnessScreen
import com.example.cpen321application.ui.profile.ProfileScreen

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

/** Dispatches by feature; each feature's routes are rendered by its own function. */
@Composable
private fun RouteContent(container: AppContainer, navigator: Navigator, route: Route) {
    when (route) {
        is Route.Groups -> GroupRoutes(container, navigator, route)
        is Route.Meetups -> MeetupRoutes(container, navigator, route)
        is Route.Profiles -> ProfileRoutes(container, navigator, route)
    }
}

@Composable
private fun ProfileRoutes(container: AppContainer, navigator: Navigator, route: Route.Profiles) {
    when (route) {
        Route.Profile -> ProfileScreen(
            container,
            onBack = navigator::pop,
            onOpenGroupFairness = { navigator.push(Route.GroupFairness(it)) },
        )
        is Route.GroupFairness -> GroupFairnessScreen(container, route.groupId, onBack = navigator::pop)
    }
}

@Composable
private fun MeetupRoutes(container: AppContainer, navigator: Navigator, route: Route.Meetups) {
    when (route) {
        is Route.CreateMeetup -> CreateMeetupScreen(container, route.groupId, onBack = navigator::pop, onCreated = {
            navigator.pop()
            navigator.push(Route.Meetup(it))
        })
        is Route.Meetup -> MeetupScreen(
            container,
            route.meetupId,
            onBack = navigator::pop,
            onEnterDetails = { navigator.push(Route.Participation(route.meetupId)) },
        )
        is Route.Participation -> ParticipationScreen(container, route.meetupId, onBack = navigator::pop)
    }
}

@Composable
private fun GroupRoutes(container: AppContainer, navigator: Navigator, route: Route.Groups) {
    when (route) {
        Route.GroupList -> GroupListScreen(
            container,
            onCreate = { navigator.push(Route.CreateGroup) },
            onJoin = { navigator.push(Route.JoinGroup) },
            onOpen = { navigator.push(Route.GroupDetail(it)) },
            onProfile = { navigator.push(Route.Profile) },
        )
        Route.CreateGroup -> CreateGroupScreen(container, onBack = navigator::pop, onCreated = {
            navigator.pop()
            navigator.push(Route.GroupDetail(it))
        })
        Route.JoinGroup -> JoinGroupScreen(container, onBack = navigator::pop, onJoined = {
            navigator.pop()
            navigator.push(Route.GroupDetail(it))
        })
        is Route.GroupDetail -> GroupDetailScreen(
            container,
            route.groupId,
            onBack = navigator::pop,
            onNewMeetup = { navigator.push(Route.CreateMeetup(route.groupId)) },
            onOpenMeetup = { navigator.push(Route.Meetup(it)) },
        )
    }
}
