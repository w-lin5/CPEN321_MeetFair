package com.example.cpen321application.ui.meetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cpen321application.AppContainer
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.data.MeetupEvent
import com.example.cpen321application.data.MeetupRepository
import com.example.cpen321application.data.TripRepository
import com.example.cpen321application.model.Meetup
import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.MeetupTrip
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Trips are reloaded on every live event; this slower poll covers a dropped socket
private const val TRIP_POLL_INTERVAL_MS = 15_000L
private const val CLOCK_TICK_MS = 1_000L

// A shared position older than this is no longer shown as "live"
internal const val POSITION_MAX_AGE_SECONDS = 90L

// Answers that mean the user may no longer see this meetup: signed out, removed from the group, or deleted
private val ACCESS_LOST_CODES = setOf(401, 403, 404)

/** Seconds from [startIso] to [end]; null if the server sent a date that cannot be parsed. */
internal fun secondsBetween(startIso: String, end: Instant): Long? = try {
    end.epochSecond - Instant.parse(startIso).epochSecond
} catch (_: DateTimeParseException) {
    null
}

/** What the live-location screen shows; [now] ticks every second so timers and stale positions update. */
data class LiveLocationState(
    val meetup: Meetup? = null,
    val trips: List<MeetupTrip> = emptyList(),
    val error: String? = null,
    // True while "pause sharing" or "I arrived" is being saved
    val busy: Boolean = false,
    val now: Instant = Instant.now(),
) {
    fun tripOf(userId: String?): MeetupTrip? = trips.find { it.userId == userId }

    /** The user joined this finalized meetup, its start time has come, and they have not arrived yet. */
    fun canStartTrip(userId: String?): Boolean {
        val current = meetup ?: return false
        val joined = current.participants.any { it.userId == userId }
        val due = current.status == MeetupStatus.FINALIZED && (secondsBetween(current.scheduledAt, now) ?: -1) >= 0
        return joined && due && tripOf(userId)?.hasArrived != true
    }
}

/** One-shot outcomes the screen has to act on. */
sealed interface LiveLocationEffect {
    /** The user can no longer see this meetup, so this device must stop sharing its location for it. */
    data object AccessLost : LiveLocationEffect

    /** The meetup was deleted; [notifyParticipant] is true if the user had entered details for it. */
    data class Deleted(val notifyParticipant: Boolean) : LiveLocationEffect
}

/** Loads a finalized meetup and everyone's trips, keeps them live, and saves the user's pause and arrival. */
class LiveLocationViewModel(
    private val meetups: MeetupRepository,
    private val trips: TripRepository,
    private val events: () -> Flow<MeetupEvent>,
    private val meetupId: String,
    val userId: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveLocationState())
    val state: StateFlow<LiveLocationState> = _state.asStateFlow()

    private val _effects = Channel<LiveLocationEffect>(Channel.BUFFERED)
    val effects: Flow<LiveLocationEffect> = _effects.receiveAsFlow()

    // Polling and live events may ask for a reload at the same time; one at a time keeps the newest result
    private val reloadMutex = Mutex()

    /** Keeps the screen up to date until cancelled; call it while the screen is visible. */
    suspend fun observe() = coroutineScope {
        loadMeetup()
        launch {
            while (true) {
                reloadTrips()
                delay(TRIP_POLL_INTERVAL_MS)
            }
        }
        launch {
            while (true) {
                _state.update { it.copy(now = Instant.now()) }
                delay(CLOCK_TICK_MS)
            }
        }
        events().collect { event ->
            when (event) {
                MeetupEvent.Joined, MeetupEvent.TripUpdated -> reloadTrips()
                is MeetupEvent.Deleted -> _effects.send(LiveLocationEffect.Deleted(event.notifyParticipant))
                is MeetupEvent.Unavailable ->
                    _state.update { it.copy(error = "Live connection unavailable. Refreshing periodically.") }
                else -> Unit
            }
        }
    }

    fun reload() {
        viewModelScope.launch { reloadTrips() }
    }

    /** Stops location updates; the commute timer keeps running until [arrive]. */
    fun pauseSharing() = save(
        call = { trips.stopSharing(meetupId) },
        failureMessage = { it },
    )

    fun arrive() = save(
        call = { trips.arrive(meetupId) },
        failureMessage = { "Arrival was not saved: $it. Tap I arrived to retry." },
    )

    private fun save(call: suspend () -> ApiResult<*>, failureMessage: (String) -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> reloadTrips()
                is ApiResult.Failure -> _state.update { it.copy(error = failureMessage(result.error.message)) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun loadMeetup() {
        when (val result = meetups.get(meetupId)) {
            is ApiResult.Success -> _state.update { it.copy(meetup = result.data) }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    private suspend fun reloadTrips() = reloadMutex.withLock {
        when (val result = trips.list(meetupId)) {
            is ApiResult.Success -> _state.update { it.copy(trips = result.data, error = null) }
            is ApiResult.Failure -> {
                val accessLost = result.error.httpCode in ACCESS_LOST_CODES
                _state.update {
                    if (accessLost) it.copy(meetup = null, trips = emptyList(), error = result.error.message)
                    else it.copy(error = result.error.message)
                }
                if (accessLost) _effects.send(LiveLocationEffect.AccessLost)
            }
        }
    }

    companion object {
        fun factory(c: AppContainer, meetupId: String) = viewModelFactory {
            initializer {
                LiveLocationViewModel(
                    meetups = c.meetupRepository,
                    trips = c.tripRepository,
                    events = { c.realtimeClient.meetupEvents(meetupId) },
                    meetupId = meetupId,
                    userId = c.sessionStore.currentUserId(),
                )
            }
        }
    }
}
