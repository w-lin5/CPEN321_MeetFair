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
    // Set once the organizer finalized the vote
    val winner: PlaceCandidate? = null,
)

// One group on the profile, with the user's Fair Credit there (seconds; negative = travelled less than average)
data class ProfileGroup(
    val groupId: String,
    val name: String,
    val role: GroupRole,
    val fairCredit: Long,
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

// One ledger row: a member's Fair Credit change for one settled meetup (all durations in seconds)
data class FairnessEntry(
    val userId: String,
    val meetupId: String,
    val actualSeconds: Long,
    val normalizedBurden: Long,
    val expectedBurden: Long,
    val delta: Long,
    val source: CommuteSource,
    val recordedAt: String,
)

data class MemberCredit(val userId: String, val name: String?, val balance: Long)

// GET /groups/:id/fairness: every member's balance and the group's ledger, newest first
data class GroupFairness(val groupId: String, val members: List<MemberCredit>, val ledger: List<FairnessEntry>) {
    fun balanceOf(userId: String?): Long = members.firstOrNull { it.userId == userId }?.balance ?: 0

    fun entriesFor(userId: String?): List<FairnessEntry> = ledger.filter { it.userId == userId }
}

// One recommended place; votes refer to it by [placeId] (its Google Places id)
data class PlaceCandidate(
    val placeId: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
)

data class Recommendation(val meetupId: String, val candidates: List<PlaceCandidate>)

// The caller's vote; [weight] is only set once it is locked
data class MyVote(val placeId: String, val locked: Boolean, val weight: Double?)

// voteCount counts every current pick; weight and share only count locked votes
data class CandidateTally(val placeId: String, val voteCount: Int, val weight: Double, val share: Double)

// Weights are floating point; totals closer than this are treated as equal
private const val WEIGHT_EPSILON = 1e-9

data class VoteTally(
    val meetupId: String,
    val candidates: List<CandidateTally>,
    val totalWeight: Double,
    val lockedCount: Int,
    val participantCount: Int,
) {
    val everyoneLocked: Boolean get() = lockedCount >= participantCount

    fun totalFor(placeId: String): CandidateTally? = candidates.firstOrNull { it.placeId == placeId }

    /** Candidates sharing the highest locked weight; all of them while nothing is locked. */
    fun leaders(): List<String> {
        val best = candidates.maxOfOrNull { it.weight } ?: return emptyList()
        return candidates.filter { best - it.weight < WEIGHT_EPSILON }.map { it.placeId }
    }
}

// GET /meetups/:id/votes: the shared tally plus the caller's own vote (null until they vote)
data class VotesSnapshot(val tally: VoteTally, val myVote: MyVote?)

// A meetup event created in the caller's own Google Calendar; htmlLink opens it in Google Calendar
data class CalendarEventResult(val eventId: String, val htmlLink: String?)
