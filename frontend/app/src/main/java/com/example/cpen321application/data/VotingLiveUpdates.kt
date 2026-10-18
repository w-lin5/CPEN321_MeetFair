package com.example.cpen321application.data

import com.example.cpen321application.model.VotingResults
import com.example.cpen321application.model.VotingSession
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

/** Something the server pushed (or failed to push) for one voting session. */
sealed interface VotingEvent {
    /** Fresh totals: sent on join, then every time a member creates or changes a vote. */
    data class Results(val results: VotingResults) : VotingEvent

    /** The organizer closed voting; [session] carries the winner. */
    data class Closed(val session: VotingSession, val results: VotingResults) : VotingEvent

    /** Live updates are unavailable for now; the socket keeps retrying in the background. */
    data class Unavailable(val message: String) : VotingEvent
}

// Event names shared with backend/src/realtime/broadcaster.ts
private const val EVENT_JOIN = "voting:join"
private const val EVENT_RESULTS = "voting:results"
private const val EVENT_CLOSED = "voting:closed"

/** Socket.IO subscription to one voting session's live results, authenticated with the session token. */
class VotingLiveUpdates(private val baseUrl: String, private val sessionStore: SessionStore) {
    /** Cold flow: connects on collect, disconnects when the collector is cancelled. */
    fun events(sessionId: String): Flow<VotingEvent> = callbackFlow {
        val token = sessionStore.currentToken()
        if (token == null) {
            trySend(VotingEvent.Unavailable("Not signed in"))
            close()
            return@callbackFlow
        }

        val options = IO.Options.builder()
            .setAuth(mapOf("token" to token))
            .setTransports(arrayOf(WebSocket.NAME))
            .build()
        val socket = IO.socket(URI.create(baseUrl), options)
        val joinPayload = JSONObject().put("sessionId", sessionId)

        // Join on every (re)connect: rooms are lost when the connection drops, and the ack carries
        // current totals so votes cast while disconnected are not missed
        socket.on(Socket.EVENT_CONNECT) {
            socket.emit(EVENT_JOIN, arrayOf<Any>(joinPayload), Ack { args -> trySend(joinAckToEvent(args)) })
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) { trySend(VotingEvent.Unavailable("Live updates unavailable")) }
        socket.on(EVENT_RESULTS) { args -> trySend(parseEvent(args) { VotingEvent.Results(parseVotingResults(it)) }) }
        socket.on(EVENT_CLOSED) { args ->
            trySend(
                parseEvent(args) {
                    VotingEvent.Closed(
                        parseVotingSession(it.getJSONObject("session")),
                        parseVotingResults(it.getJSONObject("results")),
                    )
                },
            )
        }
        socket.connect()

        awaitClose {
            socket.off()
            socket.disconnect()
        }
    }
}

// Socket.IO passes each payload as the first listener argument; malformed payloads must not crash the app
private fun parseEvent(args: Array<out Any?>, transform: (JSONObject) -> VotingEvent): VotingEvent {
    val payload = args.firstOrNull() as? JSONObject ?: return VotingEvent.Unavailable("Unexpected live update")
    return try {
        transform(payload)
    } catch (e: JSONException) {
        VotingEvent.Unavailable("Unexpected live update: ${e.message}")
    }
}

internal fun joinAckToEvent(args: Array<out Any?>): VotingEvent = parseEvent(args) { ack ->
    if (ack.optBoolean("ok")) {
        VotingEvent.Results(parseVotingResults(ack.getJSONObject("results")))
    } else {
        VotingEvent.Unavailable(ack.stringOrNull("error") ?: "Could not join live voting")
    }
}
