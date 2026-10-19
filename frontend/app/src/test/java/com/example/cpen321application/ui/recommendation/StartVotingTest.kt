package com.example.cpen321application.ui.recommendation

import com.example.cpen321application.data.serverErrorText
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartVotingTest {
    private val filled = listOf(
        CandidateInput(" Koerner Library ", "1958 Main Mall", "49.2667", " -123.2551 "),
        CandidateInput("Loafe Cafe", "", "49.2654", "-123.2496"),
        CandidateInput("Nest", " 6133 University Blvd ", "49.2665", "-123.2499"),
    )

    @Test
    fun toCandidates_trimsTextAndNumbersPlacesByPosition() {
        val candidates = toCandidates(filled)!!

        assertEquals(listOf("manual-1", "manual-2", "manual-3"), candidates.map { it.placeId })
        assertEquals("Koerner Library", candidates[0].name)
        assertEquals(-123.2551, candidates[0].lng, 0.0)
        assertEquals("6133 University Blvd", candidates[2].address)
    }

    @Test
    fun toCandidates_rejectsBlankNameOrNonNumericCoordinate() {
        assertNull(toCandidates(filled.mapIndexed { i, c -> if (i == 1) c.copy(name = "  ") else c }))
        assertNull(toCandidates(filled.mapIndexed { i, c -> if (i == 2) c.copy(lat = "north") else c }))
    }

    @Test
    fun isFilled_needsNameAndBothCoordinates() {
        assertTrue(CandidateInput("Cafe", "", "1", "2").isFilled)
        assertFalse(CandidateInput("Cafe", "Somewhere", "1", "").isFilled)
        assertFalse(CandidateInput("", "", "1", "2").isFilled)
    }

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
