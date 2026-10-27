package com.example.cpen321application.ui.meetup

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingDateTimePickerTest {
    @Test fun requiresTimeStrictlyAfterNowUsingTheChosenTimezone() {
        val now = Instant.parse("2026-11-05T22:30:00Z")
        val zone = ZoneOffset.ofHours(8)
        assertFalse(isFutureMeetingTime(LocalDateTime.of(2026, 11, 6, 6, 29), now, zone))
        assertFalse(isFutureMeetingTime(LocalDateTime.of(2026, 11, 6, 6, 30), now, zone))
        assertTrue(isFutureMeetingTime(LocalDateTime.of(2026, 11, 6, 6, 31), now, zone))
    }
    @Test fun calendarDatesRoundTripWithoutTimezoneShifts() {
        for (date in listOf(LocalDate.of(2028, 2, 29), LocalDate.of(2026, 12, 31))) {
            assertEquals(date, calendarDate(calendarDateMillis(date)))
        }
    }

    @Test fun selectedLocalDateAndWheelTimeAreSubmittedAsUtcInstant() {
        val selected = LocalDateTime.of(2026, 11, 5, 23, 59)
        assertEquals(selected.atZone(ZoneId.systemDefault()).toInstant().toString(), localInputToIso("2026-11-05T23:59"))
    }
}
