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

enum class TravelMode {
    WALK,
    TRANSIT,
    DRIVE,
    ;

    companion object {
        fun fromWire(value: String?): TravelMode = entries.firstOrNull { it.name == value } ?: WALK
    }
}

// Placeholder values, mirrored from the backend and still undecided
enum class Preference {
    STUDY,
    COFFEE,
    FOOD,
    OUTDOORS,
    SOCIAL,
    ;

    companion object {
        // Unknown values are dropped by callers (mapNotNull)
        fun fromWire(value: String?): Preference? = entries.firstOrNull { it.name == value }
    }
}

enum class MeetupStatus {
    SETUP,
    VOTING,
    FINALIZED,
    SETTLED,
    ;

    companion object {
        fun fromWire(value: String?): MeetupStatus = entries.firstOrNull { it.name == value } ?: SETUP
    }
}

data class StartLocation(val lat: Double, val lng: Double, val label: String)

data class Participant(
    val userId: String,
    val startLocation: StartLocation,
    val travelMode: TravelMode,
    val preferences: List<Preference>,
)

// scheduledAt stays an ISO-8601 instant string; the UI formats it in the device time zone
data class Meetup(
    val id: String,
    val groupId: String,
    val organizerId: String,
    val title: String,
    val scheduledAt: String,
    val status: MeetupStatus,
    val participants: List<Participant>,
)
