package com.example.cpen321application.ui.voting

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.example.cpen321application.AppContainer
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.cpen321application.data.ApiResult
import com.example.cpen321application.model.VotingResults
import androidx.compose.runtime.LaunchedEffect
import com.example.cpen321application.model.VotingSession

@Composable
fun VotingScreen(
    container: AppContainer,
    sessionId: String,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<VotingResults?>(null) }
    var session by remember { mutableStateOf<VotingSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        when (val result = container.votingRepository.getSession(sessionId)) {
            is ApiResult.Success -> {
                session = result.data
            }

            is ApiResult.Failure -> {
                message = result.error.message
            }
        }

        isLoading = false
    }

    if (isLoading) {
    Text("Loading voting session...")
    }

    session?.candidateIds?.forEach { candidateId ->
        Button(
            onClick = {
                scope.launch {
                    when (
                        val result = container.votingRepository.submitVote(
                            sessionId = sessionId,
                            candidateId = candidateId,
                        )
                    ) {
                        is ApiResult.Success -> {
                            message = "Vote submitted"
                        }

                        is ApiResult.Failure -> {
                            message = result.error.message
                        }
                    }
                }
            },
        ) {
            Text(candidateId)
        }
    }

    message?.let {
        Text(it)
    }

    Button(
    onClick = {
        scope.launch {
            when (val result = container.votingRepository.getResults(sessionId)) {
                is ApiResult.Success -> {
                    results = result.data
                }

                is ApiResult.Failure -> {
                    message = result.error.message
                }
            }
        }
    },
    ) {
        Text("View Results")
    }

    results?.totals?.forEach { total ->
    Text(
        text = "Candidate: ${total.candidateId}, " +
            "Votes: ${total.voteCount}, " +
            "Weight: ${total.totalWeight}",
        )
    }

    
}