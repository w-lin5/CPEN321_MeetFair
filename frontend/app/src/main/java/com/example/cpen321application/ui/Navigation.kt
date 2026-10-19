package com.example.cpen321application.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Every destination in the signed in app.
 * New screens must also be added to [encode], [decodeRoute] and RouteContent.
 */
sealed interface Route {
    /** Feature grouping so MeetFairApp can render each feature's routes in its own function. */
    sealed interface Groups : Route
    sealed interface Meetups : Route
    sealed interface Profiles : Route

    data object GroupList : Groups
    data object CreateGroup : Groups
    data object JoinGroup : Groups
    data class GroupDetail(val groupId: String) : Groups

    data class CreateMeetup(val groupId: String) : Meetups
    data class Meetup(val meetupId: String) : Meetups
    data class Participation(val meetupId: String) : Meetups
    data class Voting(val meetupId: String) : Meetups

    data object Profile : Profiles
    data class GroupFairness(val groupId: String) : Profiles
}

// Route arguments are encoded as "name:arg"; Mongo hex ids never contain the separator
private const val ARG_SEP = ":"
private const val GROUPS = "groups"
private const val CREATE_GROUP = "create_group"
private const val JOIN_GROUP = "join_group"
private const val GROUP = "group"
private const val CREATE_MEETUP = "create_meetup"
private const val MEETUP = "meetup"
private const val PARTICIPATION = "participation"
private const val VOTING = "voting"
private const val PROFILE = "profile"
private const val GROUP_FAIRNESS = "group_fairness"

/** Stable string form of a route, used only for saving the back stack. */
fun Route.encode(): String = when (this) {
    Route.GroupList -> GROUPS
    Route.CreateGroup -> CREATE_GROUP
    Route.JoinGroup -> JOIN_GROUP
    is Route.GroupDetail -> "$GROUP$ARG_SEP$groupId"
    is Route.CreateMeetup -> "$CREATE_MEETUP$ARG_SEP$groupId"
    is Route.Meetup -> "$MEETUP$ARG_SEP$meetupId"
    is Route.Participation -> "$PARTICIPATION$ARG_SEP$meetupId"
    is Route.Voting -> "$VOTING$ARG_SEP$meetupId"
    Route.Profile -> PROFILE
    is Route.GroupFairness -> "$GROUP_FAIRNESS$ARG_SEP$groupId"
}

/** Inverse of [encode]; returns null for unrecognised text so stale saved state is dropped (instead of crashing). */
fun decodeRoute(text: String): Route? {
    val parts = text.split(ARG_SEP, limit = 2)
    val arg = parts.getOrNull(1)
    return when (parts.first()) {
        GROUPS -> Route.GroupList
        CREATE_GROUP -> Route.CreateGroup
        JOIN_GROUP -> Route.JoinGroup
        GROUP -> arg?.let { Route.GroupDetail(it) }
        CREATE_MEETUP -> arg?.let { Route.CreateMeetup(it) }
        MEETUP -> arg?.let { Route.Meetup(it) }
        PARTICIPATION -> arg?.let { Route.Participation(it) }
        VOTING -> arg?.let { Route.Voting(it) }
        PROFILE -> Route.Profile
        GROUP_FAIRNESS -> arg?.let { Route.GroupFairness(it) }
        else -> null
    }
}

data class Entry(val id: Int, val route: Route)

private const val ENTRY_SEP = "|"

/** Minimal back stack; survives rotation through [Saver]. */
class Navigator(initial: List<Entry>) {
    val stack = mutableStateListOf<Entry>().apply { addAll(initial) }
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    // The visible screen (top of the stack)
    val current: Entry get() = stack.last()
    
    fun push(route: Route) {
        stack.add(Entry(nextId++, route))
    }

    /** Pops the top entry; the root entry is never removed. */
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        fun initial() = Navigator(listOf(Entry(0, Route.GroupList)))

        val Saver: Saver<Navigator, Any> = listSaver<Navigator, String>(
            save = { nav -> nav.stack.map { "${it.id}$ENTRY_SEP${it.route.encode()}" } },
            restore = { saved ->
                val entries = saved.mapNotNull(::decodeEntry)
                if (entries.isEmpty()) null else Navigator(entries)
            },
        )
    }
}

private fun decodeEntry(text: String): Entry? {
    val parts = text.split(ENTRY_SEP, limit = 2)
    val id = parts.firstOrNull()?.toIntOrNull()
    val route = parts.getOrNull(1)?.let(::decodeRoute)
    return if (id == null || route == null) null else Entry(id, route)
}

/** Unique per back-stack entry, so a re-opened screen never inherits a stale ViewModel. */
val LocalEntryKey = staticCompositionLocalOf { "" }

/**
 * Returns a ViewModel scoped to the current back-stack entry. The activity is the owner so state
 * survives configuration changes; the entry key keeps each stack entry's instance separate.
 */
@Composable
inline fun <reified VM : ViewModel> screenViewModel(factory: ViewModelProvider.Factory): VM {
    val owner = checkNotNull(LocalActivity.current as? ComponentActivity) {
        "screenViewModel requires a ComponentActivity host"
    }
    return ViewModelProvider(owner, factory)[LocalEntryKey.current + VM::class.java.name, VM::class.java]
}
