package com.example.cpen321application.model

data class User(val id: String, val email: String, val name: String)

data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val location: String? = null,
)

