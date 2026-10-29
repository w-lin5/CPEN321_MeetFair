package com.example.cpen321application.data

import com.example.cpen321application.model.MeetupStatus
import com.example.cpen321application.model.PlaceCandidate
import com.example.cpen321application.model.VoteTally
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import java.net.URI
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONException
import org.json.JSONObject

/** Something the server pushed (or failed to push) for one meetup. */
sealed interface MeetupEvent {
    data class Deleted(val notifyParticipant: Boolean) : MeetupEvent
    /** The meetup room was (re)joined; reload over REST so nothing published before the join is missed. */
    data object Joined : MeetupEvent
    data object TripUpdated : MeetupEvent

    /** Fresh tally: sent every time a participant votes, changes or locks a vote. */
    data class VoteUpdated(val tally: VoteTally) : MeetupEvent

    /** The meetup moved to a new status; [winner] is set once it is FINALIZED. */
    data class StatusChanged(val status: MeetupStatus, val winner: PlaceCandidate?) : MeetupEvent

    /** Recommendations were generated (the meetup moved to VOTING). */
    data object RecommendationsReady : MeetupEvent

    /** Live updates are unavailable for now; the socket keeps retrying in the background. */
    data class Unavailable(val message: String) : MeetupEvent
}

// Event names shared with backend/src/realtime/events.ts
private const val EVENT_JOIN = "meetup:join"
private const val EVENT_VOTE_UPDATED = "vote:updated"
private const val EVENT_STATUS = "meetup:status"
private const val EVENT_RECOMMENDATIONS_READY = "recommendations:ready"
private const val EVENT_MEETUP_UPDATED = "meetup:updated"
private const val EVENT_MEETUP_DELETED = "meetup:deleted"

sealed interface GroupEvent {
    data object Connected : GroupEvent
    data class Deleted(val groupId: String) : GroupEvent
    data class MeetupDeleted(val groupId: String, val notifyParticipant: Boolean = false) : GroupEvent
}

/** Socket.IO client for live updates, authenticated with the session token. */
class RealtimeClient(private val baseUrl: String, private val sessionStore: SessionStore) {
    /** Account-scoped updates work from the group list without joining a meetup room. */
    fun groupEvents(): Flow<GroupEvent> = callbackFlow {
        val token = sessionStore.currentToken()
        if (token == null) { close(); return@callbackFlow }
        val options = IO.Options.builder().setAuth(mapOf("token" to token))
            .setTransports(arrayOf(WebSocket.NAME)).build()
        val socket = IO.socket(URI.create(baseUrl), options)
        socket.on(Socket.EVENT_CONNECT) { trySend(GroupEvent.Connected) }
        socket.on("group:deleted") { args ->
            val id = (args.firstOrNull() as? JSONObject)?.optString("groupId").orEmpty()
            if (id.isNotBlank()) trySend(GroupEvent.Deleted(id))
        }
        socket.on("group:meetup-deleted") { args ->
            val id = (args.firstOrNull() as? JSONObject)?.optString("groupId").orEmpty()
            val payload = args.firstOrNull() as? JSONObject
            if (id.isNotBlank()) trySend(GroupEvent.MeetupDeleted(id, payload?.optBoolean("notifyParticipant", false) == true))
        }
        socket.connect()
        awaitClose { socket.off(); socket.disconnect() }
    }

    /**
     * Live updates for one meetup's room.
     * Cold flow: connects on collect, disconnects when the collector is cancelled.
     */
    fun meetupEvents(meetupId: String): Flow<MeetupEvent> = callbackFlow {
        val token = sessionStore.currentToken()
        if (token == null) {
            trySend(MeetupEvent.Unavailable("Not signed in"))
            close()
            return@callbackFlow
        }

        val options = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf(WebSocket.NAME))
            .build()
        val socket = IO.socket(URI.create(baseUrl), options)
        val joinPayload = JSONObject().put("meetupId", meetupId)

        // Join on every (re)connect: rooms are lost when the connection drops
        socket.on(Socket.EVENT_CONNECT) {
            socket.emit(EVENT_JOIN, arrayOf<Any>(joinPayload), Ack { args -> trySend(joinAckToEvent(args)) })
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { trySend(MeetupEvent.Unavailable("Live updates unavailable")) }
        socket.on(EVENT_VOTE_UPDATED) { args ->
            trySend(parseEvent(args) { MeetupEvent.VoteUpdated(parseVoteTally(it)) })
        }
        socket.on(EVENT_STATUS) { args -> trySend(parseEvent(args, ::parseStatusEvent)) }
        socket.on(EVENT_RECOMMENDATIONS_READY) { trySend(MeetupEvent.RecommendationsReady) }
        // Membership/participation changed; reload the meetup and tally over REST.
        socket.on(EVENT_MEETUP_UPDATED) { trySend(MeetupEvent.Joined) }
        socket.on("trip:updated") { trySend(MeetupEvent.TripUpdated) }
        socket.on(EVENT_MEETUP_DELETED) { args ->
            val payload = args.firstOrNull() as? JSONObject
            val participants = payload?.optJSONArray("participantIds")
            val participant = participants != null && (0 until participants.length()).any {
                participants.optString(it) == sessionStore.currentUserId()
            }
            trySend(MeetupEvent.Deleted(participant))
        }
        socket.connect()

        awaitClose {
            socket.off()
            socket.disconnect()
        }
    }
}

// Socket.IO passes each payload as the first listener argument; malformed payloads must not crash the app
private fun parseEvent(args: Array<out Any?>, transform: (JSONObject) -> MeetupEvent): MeetupEvent {
    val payload = args.firstOrNull() as? JSONObject ?: return MeetupEvent.Unavailable("Unexpected live update")
    return try {
        transform(payload)
    } catch (e: JSONException) {
        MeetupEvent.Unavailable("Unexpected live update: ${e.message}")
    }
}

internal fun parseStatusEvent(o: JSONObject) = MeetupEvent.StatusChanged(
    status = MeetupStatus.fromWire(o.stringOrNull("status")),
    winner = o.optJSONObject("winner")?.let(::parsePlaceCandidate),
)

internal fun joinAckToEvent(args: Array<out Any?>): MeetupEvent = parseEvent(args) { ack ->
    if (ack.optBoolean("ok")) {
        MeetupEvent.Joined
    } else {
        MeetupEvent.Unavailable(ack.stringOrNull("error") ?: "Could not join live updates")
    }
}
