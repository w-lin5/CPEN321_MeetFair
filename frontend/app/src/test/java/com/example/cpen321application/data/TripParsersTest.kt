package com.example.cpen321application.data

import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TripParsersTest {
    @Test fun activeTripCarriesMemberAvatarAndLatestPosition() {
        val trip = parseTrip(JSONObject("""{
            "userId":"member", "name":"Ada", "photoUrl":"https://example.com/avatar.png",
            "status":"ACTIVE", "sharing":true, "startedAt":"2030-01-01T12:00:00Z",
            "actualSeconds":null, "arrivedAt":null,
            "location":{"lat":49.2,"lng":-123.2,"accuracyM":10,"updatedAt":"2030-01-01T12:01:00Z"}
        }"""))
        assertEquals("Ada", trip.name)
        assertEquals("https://example.com/avatar.png", trip.photoUrl)
        assertEquals(49.2, trip.location!!.lat, 0.0001)
        assertNull(trip.actualSeconds)
    }

    @Test fun arrivalPreservesServerDurationAndRemovesCoordinate() {
        val trip = parseTrip(JSONObject("""{
            "userId":"member", "status":"ARRIVED", "sharing":false,
            "startedAt":"2030-01-01T12:00:00Z", "arrivedAt":"2030-01-01T12:02:05Z",
            "actualSeconds":125, "location":null
        }"""))
        assertEquals(125L, trip.actualSeconds)
        assertNull(trip.location)
        assertFalse(trip.sharing)
    }

    @Test fun mapRouteSurvivesNavigationStateRestore() {
        val route = Route.LiveLocation("6523f0c2a1b2c3d4e5f60718")
        assertEquals(route, decodeRoute(route.encode()))
    }
}
