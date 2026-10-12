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

/** Every destination in the signed in app. New screens must also be added to [encode], [decodeRoute] and RouteContent. */
sealed interface Route {
    data object GroupList : Route
}

/** Stable string form of a route, used only for saving the back stack. */
fun Route.encode(): String = when (this) {
    Route.GroupList -> "groups"
}

/** Inverse of [encode]; returns null for unrecognised text so stale saved state is dropped (instead of crashing). */
fun decodeRoute(text: String): Route? = if (text == "groups") Route.GroupList else null

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
