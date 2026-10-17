package com.example.cpen321application.model

data class User(val id: String, val email: String, val name: String)

enum class GroupRole {
    ORGANIZER,
    MEMBER,
    ;

    companion object {
        // Unknown values from a newer backend degrade to MEMBER instead of crashing
        fun fromWire(value: String?): GroupRole = entries.firstOrNull { it.name == value } ?: MEMBER
    }
}

// name is null in list responses; the detail response resolves it
data class GroupMember(val userId: String, val name: String?, val role: GroupRole)

data class Group(
    val id: String,
    val name: String,
    val inviteCode: String,
    val organizerId: String,
    val members: List<GroupMember>,
)
