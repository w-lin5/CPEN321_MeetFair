package com.example.cpen321application.data

import com.example.cpen321application.model.CommuteSource
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import com.example.cpen321application.ui.profile.formatDebt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileParsersTest {
    @Test
    fun parseUserProfile_readsAccountAndGroups() {
        val profile = parseUserProfile(
            JSONObject(
                """
                {
                  "id": "u1", "email": "ada@example.com", "googleName": "Ada Lovelace",
                  "displayName": "Ada Lovelace", "hasCustomDisplayName": false, "photoUrl": null,
                  "groups": [
                    { "groupId": "g1", "name": "Study Crew", "role": "ORGANIZER", "fairnessDebt": 12.5 },
                    { "groupId": "g2", "name": "Climbing", "role": "MEMBER", "fairnessDebt": -3 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Ada Lovelace", profile.displayName)
        assertFalse(profile.hasCustomDisplayName)
        assertNull(profile.photoUrl)
        assertEquals(GroupRole.ORGANIZER, profile.groups[0].role)
        assertEquals(12.5, profile.groups[0].fairnessDebt, 0.0)
        assertEquals(-3.0, profile.groups[1].fairnessDebt, 0.0)
    }

    @Test
    fun parseFairnessHistory_readsEntries() {
        val history = parseFairnessHistory(
            JSONObject(
                """
                {
                  "groupId": "g1", "fairnessDebt": 10,
                  "history": [
                    { "id": "e1", "meetupId": "m2", "commuteMinutes": 15, "groupAverageMinutes": 20,
                      "extraMinutes": -5, "source": "ESTIMATED", "createdAt": "2026-10-20T18:00:00.000Z" },
                    { "id": "e2", "meetupId": "m1", "commuteMinutes": 40, "groupAverageMinutes": 25,
                      "extraMinutes": 15, "source": "TRACKED" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(10.0, history.fairnessDebt, 0.0)
        assertEquals(listOf("m2", "m1"), history.history.map { it.meetupId })
        assertEquals(CommuteSource.ESTIMATED, history.history[0].source)
        assertEquals("2026-10-20T18:00:00.000Z", history.history[0].recordedAt)
        // A missing timestamp degrades to blank rather than failing the whole history
        assertEquals("", history.history[1].recordedAt)
    }

    @Test
    fun profileRoutes_roundTrip() {
        assertEquals(Route.Profile, decodeRoute(Route.Profile.encode()))
        assertEquals(Route.GroupFairness("g1"), decodeRoute(Route.GroupFairness("g1").encode()))
    }

    @Test
    fun formatDebt_showsSign() {
        assertEquals("+12 min", formatDebt(12.4))
        assertEquals("-5 min", formatDebt(-5.0))
        assertEquals("+0 min", formatDebt(0.0))
    }
}
