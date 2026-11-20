package com.example.cpen321application.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSharingPolicyTest {
    @Test fun stationaryUpdatesWaitForHeartbeat() {
        assertTrue(LocationSharingPolicy.shouldUpload(null, 0, 0f, false))
        assertFalse(LocationSharingPolicy.shouldUpload(0, 5_000, 0f, false))
        assertFalse(LocationSharingPolicy.shouldUpload(0, 29_999, 3f, false))
        assertTrue(LocationSharingPolicy.shouldUpload(0, 30_000, 0f, false))
    }

    @Test fun movementAndBetterFixesRespectMinimumInterval() {
        assertFalse(LocationSharingPolicy.shouldUpload(0, 4_999, 100f, true))
        assertTrue(LocationSharingPolicy.shouldUpload(0, 5_000, 10f, false))
        assertTrue(LocationSharingPolicy.shouldUpload(0, 5_000, 0f, true))
        assertFalse(LocationSharingPolicy.shouldUpload(0, 5_000, 9f, false))
    }
}
