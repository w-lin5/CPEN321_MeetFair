package com.example.cpen321application.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.cpen321application.ui.theme.Dimens

/** 
 * Standard screen layout: title at the top, optional back button, content below.
 * Keeps content clear of the status bar, nav bar and keyboard. 
 */
@Composable
fun ScreenColumn(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
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

/** Texts and test tags of a [SingleFieldFormScreen]. */
data class FormSpec(
    val title: String,
    val label: String,
    val submitLabel: String,
    val inputTag: String,
    val submitTag: String,
)

/** One text field plus a submit button; calls [onSuccess] once with the result when the submission succeeds. */
@Composable
fun <T> SingleFieldFormScreen(
    spec: FormSpec,
    state: FormState<T>,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onSuccess: (T) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.result) { state.result?.let(onSuccess) }
    
    ScreenColumn(spec.title, onBack) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(spec.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(spec.inputTag),
        )
    
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.ERROR_TEXT))
        }
    
        Button(
            onClick = { onSubmit(input) },
            enabled = !state.submitting && input.isNotBlank(),
            modifier = Modifier.testTag(spec.submitTag),
        ) { Text(spec.submitLabel) }
    }
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
