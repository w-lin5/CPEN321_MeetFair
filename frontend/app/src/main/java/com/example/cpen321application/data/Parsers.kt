package com.example.cpen321application.data

import com.example.cpen321application.model.CalendarAttendee
import com.example.cpen321application.model.CalendarEvent
import com.example.cpen321application.model.User
import org.json.JSONArray
import org.json.JSONObject

data class AuthSession(val token: String, val user: User)

internal fun parseUser(o: JSONObject) = User(o.getString("id"), o.getString("email"), o.getString("name"))

internal fun parseAuth(text: String): AuthSession {
    val o = JSONObject(text)
    return AuthSession(o.getString("token"), parseUser(o.getJSONObject("user")))
}

internal fun parseCalendarEvent(o: JSONObject): CalendarEvent {
    val id = o.optString("id").ifEmpty { o.optString("_id", "") }
    val title = o.optString("title").ifEmpty { o.optString("summary").ifEmpty { "Untitled Event" } }
    val description = o.optString("description").ifEmpty { null }
    val location = o.optString("location").ifEmpty { null }

    val startTime = when {
        o.has("startTime") -> o.optString("startTime").ifEmpty { null }
        o.has("start") -> {
            val startObj = o.optJSONObject("start")
            if (startObj != null) {
                startObj.optString("dateTime").ifEmpty { startObj.optString("date").ifEmpty { null } }
            } else {
                o.optString("start").ifEmpty { null }
            }
        }
        else -> null
    }

    val endTime = when {
        o.has("endTime") -> o.optString("endTime").ifEmpty { null }
        o.has("end") -> {
            val endObj = o.optJSONObject("end")
            if (endObj != null) {
                endObj.optString("dateTime").ifEmpty { endObj.optString("date").ifEmpty { null } }
            } else {
                o.optString("end").ifEmpty { null }
            }
        }
        else -> null
    }

    return CalendarEvent(
        id = id,
        externalId = o.optString("externalId").ifEmpty { o.optString("googleEventId").ifEmpty { null } },
        source = o.optString("source").ifEmpty { "google" },
        title = title,
        description = description,
        startTime = startTime,
        endTime = endTime,
        timezone = o.optString("timezone").ifEmpty { "UTC" },
        location = location,
        allDay = o.optBoolean("allDay", false),
        recurrence = o.optJSONArray("recurrence")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotEmpty) }
        } ?: emptyList(),
        attendees = o.optJSONArray("attendees")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { attendee ->
                    CalendarAttendee(
                        email = attendee.optString("email"),
                        displayName = attendee.optString("displayName").ifEmpty { null },
                        responseStatus = attendee.optString("responseStatus").ifEmpty { "needsAction" },
                    )
                }
            }
        } ?: emptyList(),
        busy = o.optBoolean("busy", true),
        syncedAt = o.optString("syncedAt").ifEmpty { null },
    )
}

internal fun parseCalendarEvents(text: String): List<CalendarEvent> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    val array: JSONArray = if (trimmed.startsWith("[")) {
        try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            JSONArray()
        }
    } else if (trimmed.startsWith("{")) {
        try {
            val obj = JSONObject(trimmed)
            when {
                obj.has("events") -> obj.getJSONArray("events")
                obj.has("items") -> obj.getJSONArray("items")
                obj.has("data") -> obj.getJSONArray("data")
                obj.has("calendarEvents") -> obj.getJSONArray("calendarEvents")
                else -> JSONArray()
            }
        } catch (_: Exception) {
            JSONArray()
        }
    } else {
        JSONArray()
    }

    val list = mutableListOf<CalendarEvent>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i)
        if (item != null) {
            list.add(parseCalendarEvent(item))
        }
    }
    return list
}

internal fun parseSyncResponse(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return "Calendar synced successfully"
    if (trimmed.startsWith("{")) {
        val obj = JSONObject(trimmed)
        if (obj.has("message")) return obj.getString("message")
        if (obj.has("status")) return obj.getString("status")
        if (obj.has("summary")) return obj.getString("summary")
    }
    return if (trimmed.length < 100) trimmed else "Calendar synced successfully"
}
