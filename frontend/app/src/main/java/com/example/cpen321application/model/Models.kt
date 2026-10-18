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
