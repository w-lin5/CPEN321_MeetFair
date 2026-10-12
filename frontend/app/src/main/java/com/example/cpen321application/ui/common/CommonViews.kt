package com.example.cpen321application.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.cpen321application.ui.theme.Dimens

/** 
 * Standard screen layout: title at the top, optional back button, content below.
 * Keeps content clear of the status bar, nav bar and keyboard. 
 */
@Composable
fun ScreenColumn(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TestTags.BACK_BUTTON)) { Text("Back") }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
fun LoadingView() {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.testTag(TestTags.LOADING))
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing)) {
        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.RETRY_BUTTON)) { Text("Retry") }
    }
}

@Composable
fun EmptyView(message: String) {
    Text(message, modifier = Modifier.testTag(TestTags.EMPTY))
}

/** Renders the matching view for each [UiState]; [content] is only invoked for [UiState.Success]. */
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    emptyMessage: String,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> LoadingView()
        UiState.Empty -> EmptyView(emptyMessage)
        is UiState.Error -> ErrorView(state.message, onRetry)
        is UiState.Success -> content(state.data)
    }
}
