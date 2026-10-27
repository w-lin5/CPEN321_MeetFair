package com.example.cpen321application.ui.recommendation

import com.example.cpen321application.data.serverErrorText
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StartVotingTest {
    @Test
    fun startVotingRoute_roundTrips() {
        val route = Route.StartVoting("6523f0c2a1b2c3d4e5f60718")

        assertEquals(route, decodeRoute(route.encode()))
    }

    @Test
    fun serverErrorText_appendsValidationDetails() {
        val invalid = JSONObject("""{ "error": "Invalid request", "details": ["lat must be between -90 and 90"] }""")
        val forbidden = JSONObject("""{ "error": "Not a member of this group" }""")

        assertEquals("Invalid request: lat must be between -90 and 90", serverErrorText(invalid, 400))
        assertEquals("Not a member of this group", serverErrorText(forbidden, 403))
        assertEquals("HTTP 502", serverErrorText(JSONObject("{}"), 502))
    }
}
