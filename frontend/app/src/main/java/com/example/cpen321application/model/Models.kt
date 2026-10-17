package com.example.cpen321application.model

data class User(val id: String, val email: String, val name: String)

data class VotingSession(
    val id: String,
    val candidateIds: List<String>,
    val createdBy: String,
    val status: String,
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

data class VotingResults(
    val sessionId: String,
    val totals: List<CandidateVoteTotal>,
)
