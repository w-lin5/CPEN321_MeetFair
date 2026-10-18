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
    // Set once this participant added the finalized meetup to their Google Calendar
    val calendarEventId: String? = null,
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

// One group on the profile, with the user's accumulated fairness debt there (minutes; negative = travelled less)
data class ProfileGroup(
    val groupId: String,
    val name: String,
    val role: GroupRole,
    val fairnessDebt: Double,
)

// displayName is already resolved: the custom name if set, otherwise the Google name
data class UserProfile(
    val id: String,
    val email: String,
    val googleName: String,
    val displayName: String,
    val hasCustomDisplayName: Boolean,
    val photoUrl: String?,
    val groups: List<ProfileGroup>,
)

enum class CommuteSource {
    TRACKED,
    ESTIMATED,
    ;

    companion object {
        fun fromWire(value: String?): CommuteSource = entries.firstOrNull { it.name == value } ?: ESTIMATED
    }
}

data class FairnessEntry(
    val meetupId: String,
    val commuteMinutes: Double,
    val groupAverageMinutes: Double,
    val extraMinutes: Double,
    val source: CommuteSource,
    val recordedAt: String,
)

data class FairnessHistory(val groupId: String, val fairnessDebt: Double, val history: List<FairnessEntry>)

enum class VotingStatus {
    OPEN,
    CLOSED,
    ;

    companion object {
        // The backend sends lowercase "open" / "closed"
        fun fromWire(value: String?): VotingStatus = if (value == "closed") CLOSED else OPEN
    }
}

// One place the group can vote for; [id] is what votes refer to, [placeId] is the Google Places id
data class Candidate(
    val id: String,
    val placeId: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)

// myCandidateId is the caller's current vote (null until they vote)
data class VotingSession(
    val id: String,
    val meetupId: String,
    val candidates: List<Candidate>,
    val createdBy: String,
    val status: VotingStatus,
    val winnerCandidateId: String?,
    val myCandidateId: String?,
)

data class Vote(
    val id: String,
    val sessionId: String,
    val userId: String,
    val candidateId: String,
    val weight: Double,
)

data class CandidateVoteTotal(
    val candidateId: String,
    val voteCount: Int,
    val totalWeight: Double,
)

// Weights are floating point; totals closer than this are treated as equal
private const val WEIGHT_EPSILON = 1e-9

data class VotingResults(
    val sessionId: String,
    val status: VotingStatus,
    val winnerCandidateId: String?,
    val voterCount: Int,
    val eligibleVoterCount: Int,
    val totals: List<CandidateVoteTotal>,
) {
    val everyoneVoted: Boolean get() = voterCount >= eligibleVoterCount

    fun totalFor(candidateId: String): CandidateVoteTotal? = totals.firstOrNull { it.candidateId == candidateId }

    /** Candidates sharing the highest weighted total; all of them when nobody has voted. */
    fun leaders(): List<String> {
        val best = totals.maxOfOrNull { it.totalWeight } ?: return emptyList()
        return totals.filter { best - it.totalWeight < WEIGHT_EPSILON }.map { it.candidateId }
    }
}

// A meetup event created in the caller's own Google Calendar; htmlLink opens it in Google Calendar
data class CalendarEventResult(val eventId: String, val htmlLink: String?)
