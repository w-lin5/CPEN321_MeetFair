package com.example.cpen321application.ui.common

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PullToRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeRefreshesAndKeepsExistingContentWhileLoading() {
        val state = mutableStateOf<UiState<String>>(UiState.Success("Original group"))
        var requests = 0
        compose.setContent {
            MaterialTheme {
                UiStateContent(state.value, onRetry = { requests++; state.value = UiState.Loading }, emptyMessage = "Empty") {
                    LazyColumn(Modifier.fillMaxSize()) { item { Text(it) } }
                }
            }
        }
        compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Original group").assertIsDisplayed()
        compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, requests)
            state.value = UiState.Success("New member joined")
        }
        compose.onNodeWithText("New member joined").assertIsDisplayed()
    }

    @Test fun emptyListCanBeRefreshed() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                UiStateContent<String>(UiState.Empty, onRetry = { requests++ }, emptyMessage = "No groups yet") { Text(it) }
            }
        }
        compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun errorStateCanBeRefreshed() {
        var requests = 0
        compose.setContent {
            MaterialTheme {
                UiStateContent<String>(UiState.Error("Offline"), onRetry = { requests++ }, emptyMessage = "") { Text(it) }
            }
        }
        compose.onNodeWithTag("pull_to_refresh").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
