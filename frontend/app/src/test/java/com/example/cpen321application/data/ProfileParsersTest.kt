package com.example.cpen321application.data

import com.example.cpen321application.model.CommuteSource
import com.example.cpen321application.model.GroupRole
import com.example.cpen321application.ui.Route
import com.example.cpen321application.ui.decodeRoute
import com.example.cpen321application.ui.encode
import com.example.cpen321application.ui.fairness.formatCredit
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
                    { "groupId": "g1", "name": "Study Crew", "role": "ORGANIZER", "fairCredit": 750 },
                    { "groupId": "g2", "name": "Climbing", "role": "MEMBER", "fairCredit": -180 }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Ada Lovelace", profile.displayName)
        assertFalse(profile.hasCustomDisplayName)
        assertNull(profile.photoUrl)
        assertEquals(GroupRole.ORGANIZER, profile.groups[0].role)
        assertEquals(750L, profile.groups[0].fairCredit)
        assertEquals(-180L, profile.groups[1].fairCredit)
    }

    @Test
    fun parseGroupFairness_readsMembersAndLedger() {
        val fairness = parseGroupFairness(
            JSONObject(
                """
                {
                  "groupId": "g1",
                  "members": [
                    { "userId": "u1", "name": "Ada", "balance": 600 },
                    { "userId": "u2", "name": null, "balance": -600 }
                  ],
                  "ledger": [
                    { "id": "e1", "userId": "u1", "meetupId": "m2", "actualSeconds": 900, "normalizedBurden": 900,
                      "expectedBurden": 1200, "delta": -300, "source": "ESTIMATED", "createdAt": "2026-10-20T18:00:00.000Z" },
                    { "id": "e2", "userId": "u2", "meetupId": "m2", "actualSeconds": 1500, "normalizedBurden": 1500,
                      "expectedBurden": 1200, "delta": 300, "source": "TRACKED" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(600L, fairness.balanceOf("u1"))
        assertEquals(0L, fairness.balanceOf("nobody"))
        assertNull(fairness.members[1].name)
        assertEquals(listOf(-300L), fairness.entriesFor("u1").map { it.delta })
        assertEquals(CommuteSource.ESTIMATED, fairness.ledger[0].source)
        assertEquals(-300L, fairness.ledger[0].delta)
        // A missing timestamp degrades to blank rather than failing the whole ledger
        assertEquals("", fairness.ledger[1].recordedAt)
    }

    @Test
    fun profileRoutes_roundTrip() {
        assertEquals(Route.Profile, decodeRoute(Route.Profile.encode()))
        assertEquals(Route.GroupFairness("g1"), decodeRoute(Route.GroupFairness("g1").encode()))
    }

    @Test
    fun formatCredit_showsSignedMinutes() {
        assertEquals("+12 min", formatCredit(744))
        assertEquals("-5 min", formatCredit(-300))
        assertEquals("+0 min", formatCredit(0))
    }
}
